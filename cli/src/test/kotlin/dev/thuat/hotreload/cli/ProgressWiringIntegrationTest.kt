package dev.thuat.hotreload.cli

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.concurrent.thread
import kotlin.test.assertTrue
import kotlin.test.assertEquals

// Proves ReloadOrchestrator.cycle() actually calls the onPhase callback at the right points in
// its REAL code path (not just that Progress.kt's rendering is correct in isolation, which
// ProgressTest.kt already covers with a fake sink). Uses this repo's own sample/ project — same
// one e2e/run-e2e.sh and GradleCompilerIntegrationTest build against — for a real compile, since
// GradleCompiler isn't mockable (concrete class, no seam). Skipped when sample/ isn't present,
// same guard GradleCompilerIntegrationTest uses.
class ProgressWiringIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sample = Paths.get(System.getProperty("hotreload.sampleDir", "../sample")).toAbsolutePath().normalize()
    private val pkg = "dev.thuat.hotreload.sample"

    private class SequencedRunner(private val results: List<ProcessResult>) : ProcessRunner {
        private var i = 0
        override fun run(args: List<String>, timeoutMs: Long): ProcessResult {
            val result = results.getOrElse(i) { results.last() }
            i++
            return result
        }
    }

    private val deviceReady = ProcessResult(0, "device\n", "")

    // Replies STATUS_OK to every PING it receives, forever (until closed) -- unlike the
    // one-shot fake servers elsewhere in ReloadOrchestratorTest, this test needs two separate
    // ping round trips: one to prime the baseline via bootstrap()'s fast path, one for cycle()'s
    // identity check.
    private fun persistentPingServer(detail: String): ServerSocket {
        val server = ServerSocket(0)
        thread {
            while (!server.isClosed) {
                runCatching {
                    server.accept().use { s ->
                        val input = DataInputStream(s.getInputStream())
                        val len = input.readInt()
                        val body = ByteArray(len)
                        input.readFully(body)
                        val detailBytes = detail.toByteArray()
                        s.getOutputStream().write(
                            ByteBuffer.allocate(4 + 1 + detailBytes.size)
                                .putInt(1 + detailBytes.size).put(Protocol.STATUS_OK).put(detailBytes).array()
                        )
                        s.getOutputStream().flush()
                    }
                }
            }
        }
        return server
    }

    @Test
    fun `cycle fires onPhase for keysnapshot, compile, and diff in order around a real no-op compile`() {
        assumeTrue(Files.exists(sample.resolve("settings.gradle.kts")))

        val server = persistentPingServer("pong:$pkg")
        val config = ReloadConfig(
            projectDir = sample,
            pkg = pkg,
            serial = null,
            adbPath = "adb",
            agentSoDir = tmp.newFolder("unused-agent-so").toPath(),
            localPort = server.localPort,
        )

        // Prime the baseline against the sample's already-built classes, exactly like a real
        // `bootstrap` would (its ping-fast-path saves the snapshot without touching the device
        // further, since the fake agent above always replies OK).
        val bootstrapRunner = SequencedRunner(
            listOf(deviceReady, ProcessResult(0, "1234\n", ""), ProcessResult(0, "", ""))
        )
        val primed = ReloadOrchestrator(config, bootstrapRunner).bootstrap()
        assertTrue(primed is CycleOutcome.Reloaded, "priming bootstrap failed: $primed")

        val phases = mutableListOf<Pair<String, Int?>>()
        val cycleRunner = SequencedRunner(listOf(deviceReady, ProcessResult(0, "", "")))
        val orchestrator = ReloadOrchestrator(config, cycleRunner, onPhase = { phase, count -> phases += phase to count })

        // Nothing was edited since priming, so this exercises a real compile that changes
        // nothing -- exactly the phases onPhase should see are keysnapshot, compile, diff, then
        // NoChanges before dex/push/redefine would ever fire.
        val outcome = orchestrator.cycle(sample.resolve("app/src/main/kotlin/dev/thuat/hotreload/sample/MainActivity.kt"))
        server.close()

        assertTrue(outcome is CycleOutcome.NoChanges, "expected no bytecode changes since nothing was edited, got: $outcome")
        assertEquals(listOf("keysnapshot", "compile", "diff"), phases.map { it.first })
    }
}
