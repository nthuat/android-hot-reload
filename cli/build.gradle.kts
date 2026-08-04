import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.application.tasks.CreateStartScripts

plugins { alias(libs.plugins.kotlin.jvm); application }

// Kept in lockstep with runtime/build.gradle.kts's and gradle-plugin/build.gradle.kts's `version`
// (same release, same tag — see CliInstallSupport.releaseTag). This is the single source of truth
// CliVersion.kt reads from at build time (see the processResources block below) to compare
// against the on-device runtime's self-reported version — not a second hand-maintained literal
// that could itself drift from this one.
version = "0.1.6"

application {
    mainClass.set("dev.thuat.hotreload.cli.MainKt")
}

// Bakes this build's own `version` (above) into a classpath resource read by CliVersion.kt, so
// the CLI's notion of its own version can't drift from what this build actually is (see
// ReloadOrchestrator.checkRuntimeVersion, which compares it against the on-device runtime's PING
// reply before ever sending LOAD_DEX — the fix for a version-mismatched pair silently no-op'ing a
// reload). `expand` is Gradle's built-in Copy-task templating (Groovy SimpleTemplateEngine syntax
// in the resource file), not a new dependency.
tasks.named<Copy>("processResources") {
    filesMatching("hotreload-cli-version.txt") {
        expand("version" to project.version.toString())
    }
}

dependencies {
    implementation(libs.gradle.tooling.api)
    implementation(libs.r8)
    implementation(libs.asm.tree)
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16") // tooling API logs
    testImplementation(libs.junit4)
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(17) }

// Lets Main.kt find the bundled agent .so files at "$APP_HOME/agent/<abi>/..." with zero
// configuration, as long as the CLI was launched via its own generated start script (bin/cli).
// Plain env var export, not a "-Dhotreload.home=..." JVM system property: Gradle's Unix
// start-script template runs DEFAULT_JVM_OPTS/JAVA_OPTS through an xargs+sed+eval pipeline that
// backslash-escapes every '$' (anti shell-injection hardening), so a JVM arg referencing
// "$APP_HOME" comes out the other end as the four literal characters "$APP_HOME" instead of an
// expanded path — confirmed by running the installed CLI. Exporting a plain env var ahead of
// that pipeline sidesteps it entirely. Absent when run another way (e.g. `gradle run` from a dev
// checkout) — Main.kt falls back to a CWD-relative default in that case.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val unixScriptFile = file(unixScript)
        val windowsScriptFile = file(windowsScript)
        unixScriptFile.writeText(
            unixScriptFile.readText().replaceFirst(
                "DEFAULT_JVM_OPTS=", "export HOTRELOAD_HOME=\"\$APP_HOME\"\nDEFAULT_JVM_OPTS="
            )
        )
        windowsScriptFile.writeText(
            windowsScriptFile.readText().replaceFirst(
                "set DEFAULT_JVM_OPTS=", "set HOTRELOAD_HOME=%APP_HOME%\nset DEFAULT_JVM_OPTS="
            )
        )
    }
}

// Pin distZip/distTar to a version-independent "cli.zip"/"cli.tar" with a "cli/" root directory,
// no matter what `version` (above) is set to. Both consumers of this archive hard-code that exact
// layout: install.sh greps for a literal "cli/bin/cli$" entry and moves "extracted/cli" into
// place, and InstallCliTask.unzip unpacks entry names verbatim, expecting the top-level entry to
// land exactly at its outputDir. Without this, the application plugin's default naming bakes
// `version` into both the archive file name and the root directory inside it (e.g.
// "cli-0.1.6.zip" containing "cli-0.1.6/") -- which is exactly what shipped a broken v0.1.6
// release: a stale cli.zip (no version) sat in the GitHub Release next to a correctly-built-but-
// never-uploaded cli-0.1.6.zip, and nothing caught the mismatch. distributionBaseName pins the
// root directory name; archiveVersion.set("") on each archive task drops the version suffix from
// both the file name and, since it shares the same base-name+version convention, the root
// directory too (confirmed by inspecting the built zip's own entries, not just the file name).
distributions {
    main {
        distributionBaseName.set("cli")
    }
}
tasks.named<Zip>("distZip") { archiveVersion.set("") }
tasks.named<Tar>("distTar") { archiveVersion.set("") }

// Ship the agent's built .so files inside the CLI's own distribution, laid out by ABI
// (<installDir>/agent/<abi>/libhotreload_agent.so), so a fresh consumer never has to locate or
// configure an --agent-so-dir manually — see Main.kt's resolveAgentSoDir.
distributions {
    main {
        contents {
            from(project(":agent").layout.buildDirectory.dir(
                "intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib"
            )) {
                into("agent")
            }
        }
    }
}

// The merged-native-libs dir above is a plain path reference, not a task-output provider, so
// Gradle won't infer the dependency on its own — installDist/distZip/distTar would otherwise
// copy whatever (possibly stale or absent) contents happen to be on disk.
listOf("installDist", "distZip", "distTar").forEach { taskName ->
    tasks.named(taskName) { dependsOn(":agent:assembleDebug") }
}
