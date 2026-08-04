package dev.thuat.hotreload.cli

import org.gradle.tooling.GradleConnector
import java.io.ByteArrayOutputStream
import java.nio.file.Path

data class CompileResult(val success: Boolean, val output: String)

// Builds through dexing, not just compilation: DexPackager needs the app's merged dex
// output (mergeProjectDexDebug/mergeLibDexDebug) to already be fresh so it can extract a
// changed class's bytes from there instead of re-dexing it in isolation (see DexPackager's
// header comment for why). There's no per-module target here: Gradle's task graph pulls in
// compileDebugKotlin for whichever module(s) actually changed as an upstream dependency of
// the app module's merge-dex tasks, so compile errors surface regardless of which module a
// change lives in. v1 assumes a conventional single top-level app module (default ":app",
// same assumption DexPackager makes) rather than targeting the changed file's own module.
// javaHome (--java-home, Main.kt): points the Tooling API's build daemon at a specific JDK
// instead of the connector's default (the CLI's own JVM) — lets a user work around a JDK too new
// for the consumer project's Gradle version (see JdkPreflight.kt) without touching their shell.
class GradleCompiler(private val projectDir: Path, private val appModule: String = ":app", private val javaHome: Path? = null) {
    fun compile(): CompileResult {
        val out = ByteArrayOutputStream()
        return GradleConnector.newConnector()
            .forProjectDirectory(projectDir.toFile())
            .connect()
            .use { connection ->
                try {
                    val build = connection.newBuild()
                        .forTasks("$appModule:mergeProjectDexDebug", "$appModule:mergeLibDexDebug")
                        .setStandardOutput(out)
                        .setStandardError(out)
                    if (javaHome != null) build.setJavaHome(javaHome.toFile())
                    build.run()
                    CompileResult(true, out.toString())
                } catch (e: Exception) {
                    CompileResult(false, out.toString() + "\n" + (e.message ?: e.toString()))
                }
            }
    }
}
