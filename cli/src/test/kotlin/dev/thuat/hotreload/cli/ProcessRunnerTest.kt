package dev.thuat.hotreload.cli

import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertTrue

// Reproduces the reported hang directly: a wedged `adb` invocation used to block
// RealProcessRunner.run() forever — proc.waitFor() had no timeout, and even a timed waitFor
// would've been moot since the old code read stdout/stderr fully (blocking until the process
// exits) before ever calling it. A short injected timeoutMs keeps this test itself fast rather
// than waiting out the real 30s production default.
class ProcessRunnerTest {
    @Test
    fun `run() times out and reports timedOut instead of hanging forever on a wedged process`() {
        val runner = RealProcessRunner()
        var result: ProcessResult? = null
        val elapsed = measureTimeMillis {
            result = runner.run(listOf("sleep", "30"), timeoutMs = 200)
        }
        assertTrue(elapsed < 5_000, "run() took far longer than the injected 200ms bound: ${elapsed}ms")
        assertTrue(result!!.timedOut, "expected timedOut=true, got: $result")
    }

    @Test
    fun `run() returns normally, untimed-out, for a fast command`() {
        val result = RealProcessRunner().run(listOf("true"), timeoutMs = 5_000)
        assertTrue(!result.timedOut)
        assertTrue(result.exitCode == 0)
    }
}
