package dev.thuat.hotreload.gradle

import java.io.File
import org.gradle.api.GradleException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Content generation is pure (no I/O); [writeTo] does real (but network-free) file I/O. */
class HotReloadWrapperScriptTest {
    @get:Rule val tmp = TemporaryFolder()

    private val projectDir = File("/abs/project")

    @Test
    fun `content starts with the shebang on line 1, so the file is directly executable`() {
        val lines = HotReloadWrapperScript.content(projectDir, "com.example.app", "0.1.5").lines()
        assertEquals("#!/bin/sh", lines.first())
    }

    @Test
    fun `content carries the marker comment used by the clobber guard`() {
        val script = HotReloadWrapperScript.content(projectDir, "com.example.app", "0.1.5")
        assertTrue(script.contains(HotReloadWrapperScript.MARKER))
    }

    @Test
    fun `content names the plugin version it was generated for`() {
        val script = HotReloadWrapperScript.content(projectDir, "com.example.app", "0.1.5")
        assertTrue(script.contains("0.1.5"))
    }

    @Test
    fun `content execs the installed cli relative to the script's own location`() {
        val script = HotReloadWrapperScript.content(projectDir, "com.example.app", "0.1.5")
        assertTrue(script.contains("script_dir/build/hotreload/cli/bin/cli"))
        assertTrue(script.contains("dirname -- \"\$0\""))
    }

    @Test
    fun `content bakes in project and package before the passthrough args`() {
        val script = HotReloadWrapperScript.content(projectDir, "com.example.app", "0.1.5")
        val execLine = script.lines().single { it.trimStart().startsWith("exec ") }
        val project = execLine.indexOf("--project")
        val pkg = execLine.indexOf("--package")
        val passthrough = execLine.indexOf("\"\$@\"")
        assertTrue(
            project in 0 until pkg && pkg in 0 until passthrough,
            "expected --project, then --package, then \"\$@\" (last one wins), got: $execLine",
        )
        assertTrue(execLine.contains("--project \"/abs/project\""))
        assertTrue(execLine.contains("--package \"com.example.app\""))
    }

    @Test
    fun `blank application id falls back to a placeholder package`() {
        val script = HotReloadWrapperScript.content(projectDir, "", "0.1.5")
        assertTrue(script.contains("--package \"your.app.package\""))
    }

    @Test
    fun `passes all caller args through unconditionally`() {
        val script = HotReloadWrapperScript.content(projectDir, "com.example.app", "0.1.5")
        assertTrue(script.contains("\"\$@\""))
    }

    @Test
    fun `sdk dir present sets ANDROID_HOME only when unset and points at the resolved path`() {
        val sdkDir = File("/abs/android-sdk")
        val script = HotReloadWrapperScript.content(projectDir, "com.example.app", "0.1.5", sdkDir)
        assertTrue(
            script.contains(": \"\${ANDROID_HOME:=/abs/android-sdk}\"\nexport ANDROID_HOME\n"),
            "expected a conditional-assign + export of ANDROID_HOME, got:\n$script",
        )
    }

    @Test
    fun `sdk dir absent adds no SDK line, rest of script identical to sdk dir present`() {
        val withSdk = HotReloadWrapperScript.content(projectDir, "com.example.app", "0.1.5", File("/abs/android-sdk"))
        val withoutSdk = HotReloadWrapperScript.content(projectDir, "com.example.app", "0.1.5", null)
        // No executable SDK line (the header prose still mentions ANDROID_HOME generically --
        // see the byte-for-byte comparison below, which is the precise assertion).
        assertTrue(withoutSdk.lines().none { it == "export ANDROID_HOME" || it.startsWith(": \"\${ANDROID_HOME:=") })
        val withoutSdkNoDefaultArg = HotReloadWrapperScript.content(projectDir, "com.example.app", "0.1.5")
        assertEquals(withoutSdk, withoutSdkNoDefaultArg)
        // Only the two SDK lines differ; everything else (header, exec line, project/package) is
        // byte-for-byte the same in both.
        val sdkLines = setOf(": \"\${ANDROID_HOME:=/abs/android-sdk}\"", "export ANDROID_HOME")
        assertEquals(
            withoutSdk.lines(),
            withSdk.lines().filterNot { it in sdkLines },
        )
    }

    @Test
    fun `a user-exported ANDROID_HOME is not overridden by the generated line`() {
        val script = HotReloadWrapperScript.content(projectDir, "com.example.app", "0.1.5", File("/abs/android-sdk"))
        val sdkLine = script.lines().single { it.startsWith(": ") }
        // Must be the conditional-assign form ("${ANDROID_HOME:=...}"), not a plain
        // ANDROID_HOME=... assignment -- a plain assignment would clobber a caller's own export.
        assertEquals(": \"\${ANDROID_HOME:=/abs/android-sdk}\"", sdkLine)
        assertTrue(sdkLine.contains(":="), "expected the POSIX \${VAR:=default} form, got: $sdkLine")
    }

    @Test
    fun `app module present is baked in before the passthrough args`() {
        val script = HotReloadWrapperScript.content(projectDir, "com.example.app", "0.1.5", appModule = ":mobile")
        val execLine = script.lines().single { it.trimStart().startsWith("exec ") }
        val pkg = execLine.indexOf("--package")
        val appModuleIdx = execLine.indexOf("--app-module")
        val passthrough = execLine.indexOf("\"\$@\"")
        assertTrue(
            pkg in 0 until appModuleIdx && appModuleIdx in 0 until passthrough,
            "expected --package, then --app-module, then \"\$@\" (last one wins), got: $execLine",
        )
        assertTrue(execLine.contains("--app-module \":mobile\""))
    }

    @Test
    fun `app module absent adds no --app-module flag, letting the CLI's own default apply`() {
        val script = HotReloadWrapperScript.content(projectDir, "com.example.app", "0.1.5")
        val execLine = script.lines().single { it.trimStart().startsWith("exec ") }
        assertTrue(!execLine.contains("--app-module"))
    }

    @Test
    fun `blank app module is treated the same as absent`() {
        val script = HotReloadWrapperScript.content(projectDir, "com.example.app", "0.1.5", appModule = "")
        val execLine = script.lines().single { it.trimStart().startsWith("exec ") }
        assertTrue(!execLine.contains("--app-module"))
    }

    @Test
    fun `an explicit caller --app-module still wins over the baked-in one (last occurrence wins)`() {
        // The wrapper always appends "$@" after its own baked-in flags (see the ordering test
        // above) -- this documents *why* that ordering is sufficient: Main.kt's arg parser folds
        // repeated --flag pairs into a map via toMap(), which keeps the LAST occurrence of a
        // key. A caller-passed "--app-module :tv" landing after the baked-in "--app-module
        // :mobile" therefore overrides it, exactly like --project/--package already do.
        val args = listOf("run", "--app-module", ":mobile", "--app-module", ":tv")
        val resolved = args.drop(1).chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
        assertEquals(":tv", resolved["app-module"])
    }

    @Test
    fun `isOwnFile is true only for content carrying the marker`() {
        assertTrue(HotReloadWrapperScript.isOwnFile(HotReloadWrapperScript.content(projectDir, "p", "0.1.5")))
        assertEquals(false, HotReloadWrapperScript.isOwnFile("#!/bin/sh\necho hi\n"))
    }

    @Test
    fun `writeTo creates an executable file when none exists yet`() {
        val file = HotReloadWrapperScript.writeTo(tmp.root, "com.example.app", "0.1.5")
        assertEquals("hotreload", file.name)
        assertTrue(file.canExecute())
        assertTrue(file.readText().contains("com.example.app"))
    }

    @Test
    fun `writeTo overwrites a file it generated previously`() {
        HotReloadWrapperScript.writeTo(tmp.root, "com.example.app", "0.1.5")
        val second = HotReloadWrapperScript.writeTo(tmp.root, "com.example.app", "0.1.6")
        assertTrue(second.readText().contains("0.1.6"))
    }

    @Test
    fun `writeTo refuses to clobber a pre-existing file with no marker`() {
        File(tmp.root, "hotreload").writeText("#!/bin/sh\necho \"my own script, do not touch\"\n")
        val error = assertFailsWith<GradleException> {
            HotReloadWrapperScript.writeTo(tmp.root, "com.example.app", "0.1.5")
        }
        assertTrue(error.message!!.contains("wasn't generated by this task"))
        assertEquals(
            "echo \"my own script, do not touch\"",
            File(tmp.root, "hotreload").readLines()[1],
        ) // untouched
    }
}
