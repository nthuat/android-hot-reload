package dev.thuat.hotreload.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// See isKeyMetaClass's doc comment in ReloadOrchestrator.kt for the ordering-race this filter
// exists to prevent: pushing a $KeyMeta class for redefinition can spuriously fail with "class
// not loaded" depending on filesystem walk order, even though it never needs redefinition.
class ReloadOrchestratorTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `flags a facade's KeyMeta sibling`() {
        assertTrue(isKeyMetaClass("dev.thuat.hotreload.sample.feature.GreetingKt\$KeyMeta"))
    }

    @Test
    fun `does not flag the facade itself`() {
        assertFalse(isKeyMetaClass("dev.thuat.hotreload.sample.feature.GreetingKt"))
    }

    @Test
    fun `does not flag an ordinary nested composable lambda class`() {
        assertFalse(isKeyMetaClass("dev.thuat.hotreload.sample.feature.GreetingKt\$Greeting\$1\$2"))
    }

    @Test
    fun `does not flag an unrelated class that merely contains Meta`() {
        assertFalse(isKeyMetaClass("dev.thuat.hotreload.sample.MetaData"))
    }

    // Replays the exact hole F1 closes: a failed `adb push` (or run-as copy/attach-agent/
    // forward) used to be silently ignored, so bootstrap would report success (or worse,
    // report an unrelated ping failure) while leaving the device in an unknown state and the
    // CLI's own bookkeeping unaware anything went wrong.
    private class SequencedRunner(private val results: List<ProcessResult>) : ProcessRunner {
        val calls = mutableListOf<List<String>>()
        private var i = 0
        override fun run(args: List<String>): ProcessResult {
            calls += args
            val result = results.getOrElse(i) { results.last() }
            i++
            return result
        }
    }

    // bootstrap() opens a real (loopback) TCP connection for its ping fast-path check — not
    // mocked via ProcessRunner, since it isn't an adb call. Using an OS-assigned free port
    // (bound momentarily, then released) rather than the real default port keeps this test
    // from spuriously observing an unrelated, already-forwarded agent connection that happens
    // to be listening on the tool's usual port on the machine running the tests.
    private fun unusedLoopbackPort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun configFor(projectDir: java.nio.file.Path, agentSoDir: java.nio.file.Path) = ReloadConfig(
        projectDir = projectDir,
        pkg = "dev.thuat.hotreload.sample",
        serial = null,
        adbPath = "adb",
        agentSoDir = agentSoDir,
        localPort = unusedLoopbackPort(),
    )

    // bootstrap()'s adb call sequence: pidof (isAppRunning) -> forward -> [fast-path ping,
    // never an adb call — always fails here since no real listener exists] -> getprop -> push
    // -> runAsCopy -> attachAgent -> ping retries. See ReloadOrchestrator.bootstrap's doc for
    // why the fast-path ping-first check exists (re-pushing agent.so onto an already-running,
    // already-attached agent corrupts its live mmap'd code — reproduced on-device).

    @Test
    fun `bootstrap reports DeviceError and does not save baseline when adb push fails`() {
        val projectDir = tmp.newFolder("project").toPath()
        val agentSoDir = tmp.newFolder("agent-so").toPath()
        val abiDir = agentSoDir.resolve("x86_64")
        Files.createDirectories(abiDir)
        Files.write(abiDir.resolve("libhotreload_agent.so"), byteArrayOf(1, 2, 3))

        val runner = SequencedRunner(
            listOf(
                ProcessResult(0, "1234\n", ""),                        // pidof (isAppRunning)
                ProcessResult(0, "", ""),                              // adb forward
                ProcessResult(0, "x86_64\n", ""),                      // getprop abi
                ProcessResult(1, "", "adb: error: failed to copy"),    // push agent.so -> FAILS
            )
        )
        val orchestrator = ReloadOrchestrator(configFor(projectDir, agentSoDir), runner)

        val outcome = orchestrator.bootstrap()

        assertTrue(outcome is CycleOutcome.DeviceError)
        assertTrue(
            (outcome as CycleOutcome.DeviceError).reason.contains("adb: error: failed to copy"),
            "expected the adb stderr to surface in the DeviceError reason, got: ${outcome.reason}",
        )
        assertFalse(Files.exists(projectDir.resolve(".hotreload/baseline.txt")))
        // Only pidof, forward, getprop, and the failing push should have run.
        assertTrue(runner.calls.size == 4, "expected exactly 4 adb calls before bailing, got ${runner.calls.size}: ${runner.calls}")
    }

    @Test
    fun `bootstrap reports DeviceError and does not save baseline when run-as copy fails`() {
        val projectDir = tmp.newFolder("project2").toPath()
        val agentSoDir = tmp.newFolder("agent-so2").toPath()
        val abiDir = agentSoDir.resolve("arm64-v8a")
        Files.createDirectories(abiDir)
        Files.write(abiDir.resolve("libhotreload_agent.so"), byteArrayOf(1, 2, 3))

        val runner = SequencedRunner(
            listOf(
                ProcessResult(0, "1234\n", ""),                     // pidof
                ProcessResult(0, "", ""),                           // adb forward
                ProcessResult(0, "arm64-v8a\n", ""),                 // getprop abi
                ProcessResult(0, "", ""),                            // push agent.so -> ok
                ProcessResult(1, "", "run-as: package not debuggable"), // runAsCopy -> FAILS
            )
        )
        val orchestrator = ReloadOrchestrator(configFor(projectDir, agentSoDir), runner)

        val outcome = orchestrator.bootstrap()

        assertTrue(outcome is CycleOutcome.DeviceError)
        assertTrue((outcome as CycleOutcome.DeviceError).reason.contains("run-as: package not debuggable"))
        assertFalse(Files.exists(projectDir.resolve(".hotreload/baseline.txt")))
    }
}
