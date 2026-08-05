package dev.thuat.hotreload.cli

import org.gradle.tooling.GradleConnector
import java.io.ByteArrayOutputStream
import java.nio.file.Path

data class CompileResult(val success: Boolean, val output: String)

// Runs exactly one Tooling API build attempt, with --configuration-cache included or omitted per
// `withConfigurationCache`. The seam GradleCompilerTest substitutes a scripted fake for, to
// exercise compile()'s retry/fallback logic (see GradleCompiler.compile) without a real Gradle
// daemon. RealBuildRunner is the only production implementation.
fun interface BuildRunner {
    fun run(withConfigurationCache: Boolean): CompileResult
}

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
class GradleCompiler(
    projectDir: Path,
    appModule: String = ":app",
    javaHome: Path? = null,
    // --no-configuration-cache (Main.kt): skip straight to a plain build for a project already
    // known to be configuration-cache incompatible, instead of spending the one free retry
    // compile() otherwise pays each time to rediscover that itself (see isConfigurationCacheFailure).
    private val useConfigurationCache: Boolean = true,
    // Where the one-line fallback notice (see compile()) goes. stderr by default so it never
    // pollutes stdout — e2e/run-e2e.sh captures a cycle's stdout and greps it for the final
    // outcome line (see Progress.kt's doc) — and never gets mixed into a CompileError's own
    // output text, since it's a heads-up about the CLI's own retry, not part of the build result.
    private val log: (String) -> Unit = { System.err.println(it) },
    private val buildRunner: BuildRunner = RealBuildRunner(projectDir, appModule, javaHome),
) {
    // Sticky for the life of this GradleCompiler instance, and therefore — since
    // ReloadOrchestrator constructs exactly one GradleCompiler and `run`'s watch loop (Main.kt)
    // reuses that one orchestrator across every cycle — for the life of the process in watch
    // mode. `bootstrap`/`cycle` are already one-shot processes, so per-instance already means
    // per-process for them; there's nowhere further up to hoist this.
    @Volatile private var configurationCacheDisabled = false

    fun compile(): CompileResult {
        val tryFlag = useConfigurationCache && !configurationCacheDisabled
        val first = buildRunner.run(tryFlag)
        // Only ever retried when the flag was actually on for this attempt AND the failure looks
        // configuration-cache shaped (see isConfigurationCacheFailure) — a normal compile error
        // (broken source, a missing module, ...) must surface unchanged, not eat a second,
        // identically-failing build for nothing.
        if (first.success || !tryFlag || !isConfigurationCacheFailure(first.output)) return first

        configurationCacheDisabled = true
        log(
            "hotreload: configuration cache disabled for this project (a build failure looked " +
                "configuration-cache related); retrying without it. Pass --no-configuration-cache " +
                "to skip this check on future runs."
        )
        return buildRunner.run(false)
    }
}

class RealBuildRunner(
    private val projectDir: Path,
    private val appModule: String,
    private val javaHome: Path?,
) : BuildRunner {
    override fun run(withConfigurationCache: Boolean): CompileResult {
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
                    // Gradle re-runs its whole configuration phase on every build by default —
                    // on a multi-module project (Hilt + KSP) that dominates the reload, ~5x the
                    // dex-merge time itself. Confirmed this flag alone reuses a warm cache with
                    // no gradle.properties change needed in the CONSUMER project — it only
                    // affects the build our CLI launches, never the user's own ./gradlew. See
                    // GradleCompiler.compile for the fallback when a project's plugins turn out
                    // to be incompatible with it.
                    if (withConfigurationCache) build.withArguments("--configuration-cache")
                    build.run()
                    CompileResult(true, out.toString())
                } catch (e: Exception) {
                    CompileResult(false, out.toString() + "\n" + (e.message ?: e.toString()))
                }
            }
    }
}
