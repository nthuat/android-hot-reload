package dev.thuat.hotreload.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        override fun run(args: List<String>, timeoutMs: Long): ProcessResult {
            calls += args
            val result = results.getOrElse(i) { results.last() }
            i++
            return result
        }
    }

    private val deviceReady = ProcessResult(0, "device\n", "")

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
                deviceReady,                                            // get-state (device ready check)
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
        // Only get-state, pidof, forward, getprop, and the failing push should have run.
        assertTrue(runner.calls.size == 5, "expected exactly 5 adb calls before bailing, got ${runner.calls.size}: ${runner.calls}")
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
                deviceReady,                                          // get-state
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

    // Replays the reproduced-live hang: the device went `offline` mid-session and every adb call
    // (including `adb shell pidof`) would otherwise block forever. `adb get-state` is checked
    // first, before any adb calls that would talk to the (dead) app, so bootstrap fails fast.
    @Test
    fun `bootstrap fails fast with DeviceError when device is offline, before any other adb call`() {
        val projectDir = tmp.newFolder("project-offline").toPath()
        val agentSoDir = tmp.newFolder("agent-so-offline").toPath()

        val runner = SequencedRunner(listOf(ProcessResult(0, "offline\n", "")))
        val orchestrator = ReloadOrchestrator(configFor(projectDir, agentSoDir), runner)

        val outcome = orchestrator.bootstrap()

        assertTrue(outcome is CycleOutcome.DeviceError)
        assertTrue(
            (outcome as CycleOutcome.DeviceError).reason.contains("offline"),
            "expected the device state to surface in the DeviceError reason, got: ${outcome.reason}",
        )
        assertFalse(Files.exists(projectDir.resolve(".hotreload/baseline.txt")))
        assertEquals(1, runner.calls.size, "expected only the get-state check to run, got: ${runner.calls}")
    }

    // Same early exit, but on cycle() — a device can die *between* cycles (exactly what
    // happened live: the emulator wedged mid-session, after a previous successful bootstrap).
    @Test
    fun `cycle fails fast with DeviceError when device is offline, before compiling`() {
        val projectDir = tmp.newFolder("project-offline-cycle").toPath()
        val agentSoDir = tmp.newFolder("agent-so-offline-cycle").toPath()

        val runner = SequencedRunner(listOf(ProcessResult(0, "offline\n", "")))
        val orchestrator = ReloadOrchestrator(configFor(projectDir, agentSoDir), runner)

        val outcome = orchestrator.cycle(projectDir.resolve("app/src/main/kotlin/Foo.kt"))

        assertTrue(outcome is CycleOutcome.DeviceError)
        assertTrue((outcome as CycleOutcome.DeviceError).reason.contains("offline"))
        assertEquals(1, runner.calls.size, "expected only the get-state check to run, got: ${runner.calls}")
    }

    // Replays the actual reproduced hang one step further in: a wedged adb call (e.g. `adb push`
    // against an offline device) used to block RealProcessRunner forever with no timeout at all.
    // Simulated here via a fake ProcessRunner that reports a timed-out call, the same shape
    // RealProcessRunner now returns once its own waitFor(timeout) expires (see ProcessRunner.kt).
    @Test
    fun `bootstrap reports DeviceError naming the timeout when an adb call times out, and does not save baseline`() {
        val projectDir = tmp.newFolder("project-timeout").toPath()
        val agentSoDir = tmp.newFolder("agent-so-timeout").toPath()
        val abiDir = agentSoDir.resolve("x86_64")
        Files.createDirectories(abiDir)
        Files.write(abiDir.resolve("libhotreload_agent.so"), byteArrayOf(1, 2, 3))

        val runner = SequencedRunner(
            listOf(
                deviceReady,                                    // get-state
                ProcessResult(0, "1234\n", ""),                 // pidof
                ProcessResult(0, "", ""),                       // adb forward
                ProcessResult(0, "x86_64\n", ""),                // getprop abi
                ProcessResult(-1, "", "", timedOut = true),      // push agent.so -> TIMES OUT
            )
        )
        val orchestrator = ReloadOrchestrator(configFor(projectDir, agentSoDir), runner)

        val outcome = orchestrator.bootstrap()

        assertTrue(outcome is CycleOutcome.DeviceError)
        assertTrue(
            (outcome as CycleOutcome.DeviceError).reason.contains("timed out"),
            "expected the DeviceError to name the timeout, got: ${outcome.reason}",
        )
        assertFalse(Files.exists(projectDir.resolve(".hotreload/baseline.txt")))
    }

    // Covers the skip-unloaded-classes fix: editing a Compose file that has a @Preview used to
    // fail the whole reload, because the ComposableSingletons$...Kt$lambda-N$1 holder classes
    // Compose emits for preview-only lambdas are never loaded at runtime and so came back
    // "class not loaded" from the agent. These parse the exact "<result>[ | skipped <N>: ...]
    // [ | tierN]" detail format agent.cpp (HandleLoadDex/ServeClient) emits — see Protocol.kt's
    // detail-format doc, which this must match byte-for-byte.

    @Test
    fun `plain reply with no skipped segment still parses tier correctly`() {
        val detail = "Lcom/example/FooKt;: redefined | tier1"
        assertEquals("tier1", parseTier(detail))
        assertTrue(parseSkippedDescriptors(detail).isEmpty())
    }

    @Test
    fun `reply detail with skipped classes parses both the tier and the skipped descriptors`() {
        val detail = "Lcom/example/FooKt;: redefined | skipped 2: " +
            "Lcom/example/ComposableSingletons\$FooKt\$lambda-1\$1;, Lcom/example/ComposableSingletons\$FooKt\$lambda-2\$1; | tier1"

        assertEquals("tier1", parseTier(detail))
        assertEquals(
            listOf(
                "Lcom/example/ComposableSingletons\$FooKt\$lambda-1\$1;",
                "Lcom/example/ComposableSingletons\$FooKt\$lambda-2\$1;",
            ),
            parseSkippedDescriptors(detail),
        )
    }

    @Test
    fun `all-skipped reply has no tier since the runtime is never notified, but still lists every skipped class`() {
        val detail = "nothing redefined: all 2 class(es) not loaded | skipped 2: " +
            "Lcom/example/ComposableSingletons\$FooKt\$lambda-1\$1;, Lcom/example/ComposableSingletons\$FooKt\$lambda-2\$1;"

        assertNull(parseTier(detail))
        assertEquals(2, parseSkippedDescriptors(detail).size)
    }

    // Mirrors cycle()'s STATUS_OK branch: toRedefine is partitioned by descriptor membership in
    // the parsed skipped set to build CycleOutcome.Reloaded.classes/.skipped — the actual
    // parsing this test exercises is what makes a partially-skipped batch report both a
    // populated `skipped` list (exit 0, normal reload line) and, in the all-skipped case, an
    // empty `classes` list (exit 0, "nothing applied" line — see Main.kt's report()).
    @Test
    fun `partitioning toRedefine by parsed skipped descriptors yields the Reloaded outcome shape`() {
        val toRedefine = listOf(
            ChangedClass(Paths.get("Foo.class"), "com.example.FooKt", "Lcom/example/FooKt;"),
            ChangedClass(
                Paths.get("Lambda1.class"),
                "com.example.ComposableSingletons\$FooKt\$lambda-1\$1",
                "Lcom/example/ComposableSingletons\$FooKt\$lambda-1\$1;",
            ),
        )
        val detail = "Lcom/example/FooKt;: redefined | skipped 1: Lcom/example/ComposableSingletons\$FooKt\$lambda-1\$1; | tier1"

        val skippedDescriptors = parseSkippedDescriptors(detail).toSet()
        val (skipped, redefined) = toRedefine.partition { it.descriptor in skippedDescriptors }

        assertEquals(listOf("com.example.FooKt"), redefined.map { it.binaryName })
        assertEquals(listOf("com.example.ComposableSingletons\$FooKt\$lambda-1\$1"), skipped.map { it.binaryName })
    }

    @Test
    fun `fully-skipped batch partitions to empty redefined and full skipped, matching the nothing-applied outcome`() {
        val toRedefine = listOf(
            ChangedClass(
                Paths.get("Lambda1.class"),
                "com.example.ComposableSingletons\$FooKt\$lambda-1\$1",
                "Lcom/example/ComposableSingletons\$FooKt\$lambda-1\$1;",
            ),
        )
        val detail = "nothing redefined: all 1 class(es) not loaded | skipped 1: Lcom/example/ComposableSingletons\$FooKt\$lambda-1\$1;"

        val skippedDescriptors = parseSkippedDescriptors(detail).toSet()
        val (skipped, redefined) = toRedefine.partition { it.descriptor in skippedDescriptors }

        assertTrue(redefined.isEmpty())
        assertEquals(1, skipped.size)
        assertNull(parseTier(detail))
    }
}
