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
class GradleCompiler(private val projectDir: Path, private val appModule: String = ":app") {
    fun compile(): CompileResult {
        val out = ByteArrayOutputStream()
        return GradleConnector.newConnector()
            .forProjectDirectory(projectDir.toFile())
            .connect()
            .use { connection ->
                try {
                    connection.newBuild()
                        .forTasks("$appModule:mergeProjectDexDebug", "$appModule:mergeLibDexDebug")
                        .setStandardOutput(out)
                        .setStandardError(out)
                        .run()
                    CompileResult(true, out.toString())
                } catch (e: Exception) {
                    CompileResult(false, out.toString() + "\n" + (e.message ?: e.toString()))
                }
            }
    }
}
