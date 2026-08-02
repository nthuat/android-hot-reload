package dev.hotreload.cli

import org.gradle.tooling.GradleConnector
import java.io.ByteArrayOutputStream
import java.nio.file.Path

data class CompileResult(val success: Boolean, val output: String)

// Builds through dexing, not just compilation: DexPackager needs the app's merged dex
// output (mergeProjectDexDebug/mergeLibDexDebug) to already be fresh so it can extract a
// changed class's bytes from there instead of re-dexing it in isolation (see DexPackager's
// header comment for why). Gradle's task graph pulls in compileDebugKotlin for whichever
// module(s) actually changed as an upstream dependency, so compile errors still surface
// here the same way. `module` (the changed file's own module) is kept for signature/API
// stability and future per-module targeting; dexing itself always culminates at the app
// module (v1 assumes a conventional single top-level ":app", see DexPackager).
class GradleCompiler(private val projectDir: Path, private val appModule: String = ":app") {
    fun compile(module: String): CompileResult {
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
