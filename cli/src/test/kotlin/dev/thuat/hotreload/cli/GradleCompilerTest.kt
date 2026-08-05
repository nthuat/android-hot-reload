package dev.thuat.hotreload.cli

import org.junit.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Unit tests for GradleCompiler.compile()'s configuration-cache retry/fallback logic, driven
// through a scripted fake BuildRunner instead of a real Gradle daemon (see BuildRunner's doc —
// GradleCompilerIntegrationTest covers the real Tooling API path). `projectDir` is never touched
// by these tests since the fake BuildRunner replaces RealBuildRunner entirely.
class GradleCompilerTest {
    private val configCacheFailure = CompileResult(false, "Configuration cache problems found in this build.")
    private val ordinaryFailure = CompileResult(false, "Execution failed for task ':app:compileDebugKotlin'.")
    private val success = CompileResult(true, "BUILD SUCCESSFUL")

    private class ScriptedRunner(private val results: List<CompileResult>) : BuildRunner {
        val calls = mutableListOf<Boolean>()
        private var i = 0
        override fun run(withConfigurationCache: Boolean): CompileResult {
            calls += withConfigurationCache
            return results.getOrElse(i) { results.last() }.also { i++ }
        }
    }

    private fun compiler(
        runner: BuildRunner,
        useConfigurationCache: Boolean = true,
        log: MutableList<String> = mutableListOf(),
    ) = GradleCompiler(
        projectDir = Paths.get("/unused"),
        useConfigurationCache = useConfigurationCache,
        log = { log += it },
        buildRunner = runner,
    )

    @Test
    fun `the flag is passed by default on a successful build`() {
        val runner = ScriptedRunner(listOf(success))
        val result = compiler(runner).compile()
        assertTrue(result.success)
        assertEquals(listOf(true), runner.calls)
    }

    @Test
    fun `a configuration-cache-shaped failure triggers exactly one retry without the flag`() {
        val runner = ScriptedRunner(listOf(configCacheFailure, success))
        val log = mutableListOf<String>()
        val result = compiler(runner, log = log).compile()
        assertTrue(result.success)
        // First attempt with the flag, second (and only) retry without it.
        assertEquals(listOf(true, false), runner.calls)
        assertEquals(1, log.size)
    }

    @Test
    fun `a normal build failure does not retry and is surfaced unchanged`() {
        val runner = ScriptedRunner(listOf(ordinaryFailure))
        val log = mutableListOf<String>()
        val result = compiler(runner, log = log).compile()
        assertFalse(result.success)
        assertEquals(ordinaryFailure, result)
        assertEquals(listOf(true), runner.calls)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `once the fallback has triggered, later builds in the same process skip the flag`() {
        val runner = ScriptedRunner(listOf(configCacheFailure, success, success, success))
        val log = mutableListOf<String>()
        val gradleCompiler = compiler(runner, log = log)

        gradleCompiler.compile() // triggers the fallback
        gradleCompiler.compile()
        gradleCompiler.compile()

        // First cycle: flag on, then the one retry with it off. Later cycles: flag never
        // retried, straight to a single build without it — no repeated failed attempt per save.
        assertEquals(listOf(true, false, false, false), runner.calls)
        assertEquals(1, log.size)
    }

    @Test
    fun `--no-configuration-cache suppresses the flag entirely`() {
        val runner = ScriptedRunner(listOf(success))
        val result = compiler(runner, useConfigurationCache = false).compile()
        assertTrue(result.success)
        assertEquals(listOf(false), runner.calls)
    }

    @Test
    fun `--no-configuration-cache means a configuration-cache-shaped failure is never retried`() {
        // Can't happen in practice (the failure shape shouldn't occur without the flag), but
        // proves compile() only ever retries when it actually turned the flag on itself.
        val runner = ScriptedRunner(listOf(configCacheFailure))
        val log = mutableListOf<String>()
        val result = compiler(runner, useConfigurationCache = false, log = log).compile()
        assertFalse(result.success)
        assertEquals(listOf(false), runner.calls)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `a second build failure after the fallback retry is surfaced unchanged`() {
        val stillFails = CompileResult(false, "still broken without the cache either")
        val runner = ScriptedRunner(listOf(configCacheFailure, stillFails))
        val result = compiler(runner).compile()
        assertFalse(result.success)
        assertEquals(stillFails, result)
        assertEquals(listOf(true, false), runner.calls)
    }
}
