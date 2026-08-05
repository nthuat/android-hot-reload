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

    // The bug this closes: `adb forward` is a single GLOBAL per-device mapping. bootstrap() set
    // it, but cycle() never did — it just trusted whatever the forward happened to point at, so
    // bootstrapping a *second* app on the same device silently repointed it and every later
    // cycle for the first app sent its LOAD_DEX to the second app's agent. Reproduced live (see
    // the fix report): failed safely only because the two apps' data dirs differed. cycle() now
    // re-issues the forward for its own package on every call, before anything else.
    @Test
    fun `cycle re-issues the adb forward for its own package before compiling`() {
        val projectDir = tmp.newFolder("project-cycle-forward").toPath()
        val agentSoDir = tmp.newFolder("agent-so-cycle-forward").toPath()
        val config = configFor(projectDir, agentSoDir)

        val runner = SequencedRunner(
            listOf(
                deviceReady,                // get-state
                ProcessResult(0, "", ""),   // adb forward
                // Nothing is listening on config.localPort, so the identity ping right after
                // fails and cycle() returns before ever compiling — this test only cares that
                // the forward call itself happened, with the right argv.
            )
        )
        val orchestrator = ReloadOrchestrator(config, runner)

        val outcome = orchestrator.cycle(projectDir.resolve("app/src/main/kotlin/Foo.kt"))

        assertTrue(outcome is CycleOutcome.DeviceError)
        assertEquals(
            listOf("adb", "forward", "tcp:${config.localPort}", "localabstract:hotreload-agent-${config.pkg}"),
            runner.calls.getOrNull(1),
            "expected the second adb call (right after get-state) to be the forward for this cycle's own package",
        )
    }

    // Belt-and-braces layer on top of the forward re-issue above: even with the forward pointed
    // at the right socket name, verify the far end actually names this package in its PING reply
    // before doing any compile/dex/push work, let alone LOAD_DEX. Catches any other port/forward
    // confusion, not just the one the forward re-issue closes.
    @Test
    fun `cycle reports DeviceError when the agent's ping reply names a different package, and never reaches LOAD_DEX`() {
        val projectDir = tmp.newFolder("project-wrong-agent").toPath()
        val agentSoDir = tmp.newFolder("agent-so-wrong-agent").toPath()

        val fakeAgent = java.net.ServerSocket(0)
        val serverThread = kotlin.concurrent.thread {
            runCatching {
                fakeAgent.accept().use { s ->
                    val input = java.io.DataInputStream(s.getInputStream())
                    val len = input.readInt()
                    val body = ByteArray(len); input.readFully(body)
                    assertEquals(Protocol.CMD_PING, body[0])
                    val detail = "pong:dev.thuat.hotreload.sample.OTHER".toByteArray()
                    s.getOutputStream().write(
                        java.nio.ByteBuffer.allocate(4 + 1 + detail.size)
                            .putInt(1 + detail.size).put(Protocol.STATUS_OK).put(detail).array()
                    )
                    s.getOutputStream().flush()
                }
            }
        }

        val config = ReloadConfig(
            projectDir = projectDir,
            pkg = "dev.thuat.hotreload.sample",
            serial = null,
            adbPath = "adb",
            agentSoDir = agentSoDir,
            localPort = fakeAgent.localPort,
        )
        val runner = SequencedRunner(
            listOf(
                deviceReady,               // get-state
                ProcessResult(0, "", ""),  // adb forward
            )
        )
        val orchestrator = ReloadOrchestrator(config, runner)

        val outcome = orchestrator.cycle(projectDir.resolve("app/src/main/kotlin/Foo.kt"))
        serverThread.join(2_000)
        fakeAgent.close()

        assertTrue(outcome is CycleOutcome.DeviceError)
        val reason = (outcome as CycleOutcome.DeviceError).reason
        assertTrue(reason.contains(config.pkg), "expected the expected package in the reason, got: $reason")
        assertTrue(reason.contains("dev.thuat.hotreload.sample.OTHER"), "expected the actual package in the reason, got: $reason")
        assertFalse(Files.exists(projectDir.resolve(".hotreload/baseline.txt")), "baseline must not be saved on identity mismatch")
        assertEquals(
            2, runner.calls.size,
            "expected cycle to stop right after the identity check — no compile/dex/push, no LOAD_DEX attempted: ${runner.calls}",
        )
    }

    // --- Runtime version handshake (see ReloadOrchestrator.checkRuntimeVersion's doc for the
    // exact-match rule and why an unknown version is a warning, not a failure) ---

    @Test
    fun `checkRuntimeVersion returns null when versions match exactly`() {
        assertNull(checkRuntimeVersion("0.1.6", "0.1.6"))
    }

    @Test
    fun `checkRuntimeVersion returns null for an unknown or absent runtime version`() {
        assertNull(checkRuntimeVersion("0.1.6", null))
        assertNull(checkRuntimeVersion("0.1.6", Protocol.UNKNOWN_RUNTIME_VERSION))
    }

    @Test
    fun `checkRuntimeVersion returns a DeviceError naming both versions on a genuine mismatch`() {
        val error = checkRuntimeVersion("0.1.6", "0.1.5")
        assertTrue(error is CycleOutcome.DeviceError)
        assertTrue((error as CycleOutcome.DeviceError).reason.contains("0.1.6"), error.reason)
        assertTrue(error.reason.contains("0.1.5"), error.reason)
    }

    @Test
    fun `checkRuntimeVersion tells the user to reinstall the app, the only thing that fixes it`() {
        // The runtime library is compiled INTO the APK, so the on-device version only changes when
        // the app is rebuilt and reinstalled. The original message instead suggested
        // `hotReloadInstallCli` (which only downloads the CLI, moving the versions further apart)
        // and "pin the plugin version to X" (already done -- that is what put the CLI ahead of the
        // device). Reproduced live on Jetcaster: following the advice verbatim changed nothing.
        val reason = (checkRuntimeVersion("0.1.8", "0.1.7") as CycleOutcome.DeviceError).reason
        assertTrue(reason.contains("reinstall"), reason)
        assertTrue(reason.contains("assembleDebug"), reason)
        assertTrue(reason.contains("adb install"), reason)
        assertFalse(reason.contains("hotReloadInstallCli"), reason)
    }

    @Test
    fun `unknownRuntimeVersionWarning is null when the runtime version is known`() {
        assertNull(unknownRuntimeVersionWarning("0.1.6", "0.1.6"))
    }

    @Test
    fun `unknownRuntimeVersionWarning names the CLI version when the runtime version is unknown or absent`() {
        assertTrue(unknownRuntimeVersionWarning("0.1.6", null)!!.contains("0.1.6"))
        assertTrue(unknownRuntimeVersionWarning("0.1.6", Protocol.UNKNOWN_RUNTIME_VERSION)!!.contains("0.1.6"))
    }

    // Fakes exactly one PING round trip with the given reply `detail` — the same shape as the
    // "wrong package" identity test above, parameterized on the detail so it can simulate a
    // specific runtime-version field without a real device or agent .so.
    private fun fakePingServer(detail: String): java.net.ServerSocket {
        val server = java.net.ServerSocket(0)
        kotlin.concurrent.thread {
            runCatching {
                server.accept().use { s ->
                    val input = java.io.DataInputStream(s.getInputStream())
                    val len = input.readInt()
                    val body = ByteArray(len); input.readFully(body)
                    assertEquals(Protocol.CMD_PING, body[0])
                    val detailBytes = detail.toByteArray()
                    s.getOutputStream().write(
                        java.nio.ByteBuffer.allocate(4 + 1 + detailBytes.size)
                            .putInt(1 + detailBytes.size).put(Protocol.STATUS_OK).put(detailBytes).array()
                    )
                    s.getOutputStream().flush()
                }
            }
        }
        return server
    }

    // Minimal ":app" module with an (empty) debug kotlin-classes dir, just enough for
    // allClassDirs()/ClassDiffer.snapshot to succeed so bootstrap's fast path can reach
    // store.save() — see ModuleResolverTest for the same module-shape convention.
    private fun withEmptyAppModule(projectDir: java.nio.file.Path) {
        val moduleDir = projectDir.resolve("app")
        Files.createDirectories(moduleDir.resolve("build/tmp/kotlin-classes/debug"))
        Files.createFile(moduleDir.resolve("build.gradle.kts"))
    }

    @Test
    fun `bootstrap proceeds with no warning when the on-device runtime version matches the CLI's`() {
        val projectDir = tmp.newFolder("project-version-match").toPath()
        withEmptyAppModule(projectDir)
        val agentSoDir = tmp.newFolder("agent-so-version-match").toPath()
        val server = fakePingServer("pong:dev.thuat.hotreload.sample:0.1.6")

        val runner = SequencedRunner(
            listOf(
                deviceReady,                    // get-state
                ProcessResult(0, "1234\n", ""), // pidof (isAppRunning)
                ProcessResult(0, "", ""),       // adb forward
            )
        )
        val config = ReloadConfig(
            projectDir = projectDir, pkg = "dev.thuat.hotreload.sample", serial = null,
            adbPath = "adb", agentSoDir = agentSoDir, localPort = server.localPort, cliVersion = "0.1.6",
        )
        val outcome = ReloadOrchestrator(config, runner).bootstrap()
        server.close()

        assertTrue(outcome is CycleOutcome.Reloaded)
        assertNull((outcome as CycleOutcome.Reloaded).warning)
        assertTrue(Files.exists(projectDir.resolve(".hotreload/baseline.txt")))
    }

    @Test
    fun `bootstrap reports DeviceError naming both versions on a runtime mismatch, and never pushes the agent so`() {
        val projectDir = tmp.newFolder("project-version-mismatch").toPath()
        val agentSoDir = tmp.newFolder("agent-so-version-mismatch").toPath()
        val server = fakePingServer("pong:dev.thuat.hotreload.sample:0.1.5")

        val runner = SequencedRunner(
            listOf(
                deviceReady,
                ProcessResult(0, "1234\n", ""),
                ProcessResult(0, "", ""),
            )
        )
        val config = ReloadConfig(
            projectDir = projectDir, pkg = "dev.thuat.hotreload.sample", serial = null,
            adbPath = "adb", agentSoDir = agentSoDir, localPort = server.localPort, cliVersion = "0.1.6",
        )
        val outcome = ReloadOrchestrator(config, runner).bootstrap()
        server.close()

        assertTrue(outcome is CycleOutcome.DeviceError)
        val reason = (outcome as CycleOutcome.DeviceError).reason
        assertTrue(reason.contains("0.1.6"), reason)
        assertTrue(reason.contains("0.1.5"), reason)
        assertFalse(Files.exists(projectDir.resolve(".hotreload/baseline.txt")))
        assertEquals(
            3, runner.calls.size,
            "expected bootstrap to stop right after the version-mismatched ping — no agent .so push: ${runner.calls}",
        )
    }

    @Test
    fun `bootstrap proceeds with a warning when the on-device runtime predates the version handshake`() {
        val projectDir = tmp.newFolder("project-version-unknown").toPath()
        withEmptyAppModule(projectDir)
        val agentSoDir = tmp.newFolder("agent-so-version-unknown").toPath()
        // Old two-field "pong:<pkg>" shape — no runtime-version field at all.
        val server = fakePingServer("pong:dev.thuat.hotreload.sample")

        val runner = SequencedRunner(
            listOf(
                deviceReady,
                ProcessResult(0, "1234\n", ""),
                ProcessResult(0, "", ""),
            )
        )
        val config = ReloadConfig(
            projectDir = projectDir, pkg = "dev.thuat.hotreload.sample", serial = null,
            adbPath = "adb", agentSoDir = agentSoDir, localPort = server.localPort, cliVersion = "0.1.6",
        )
        val outcome = ReloadOrchestrator(config, runner).bootstrap()
        server.close()

        assertTrue(outcome is CycleOutcome.Reloaded)
        val warning = (outcome as CycleOutcome.Reloaded).warning
        assertTrue(warning != null && warning.contains("0.1.6"), "expected a warning naming the CLI version, got: $warning")
        assertTrue(Files.exists(projectDir.resolve(".hotreload/baseline.txt")))
    }

    @Test
    fun `cycle reports DeviceError naming both versions on a runtime mismatch, and never reaches LOAD_DEX`() {
        val projectDir = tmp.newFolder("project-cycle-version-mismatch").toPath()
        val agentSoDir = tmp.newFolder("agent-so-cycle-version-mismatch").toPath()
        val server = fakePingServer("pong:dev.thuat.hotreload.sample:0.1.5")

        val runner = SequencedRunner(listOf(deviceReady, ProcessResult(0, "", "")))
        val config = ReloadConfig(
            projectDir = projectDir, pkg = "dev.thuat.hotreload.sample", serial = null,
            adbPath = "adb", agentSoDir = agentSoDir, localPort = server.localPort, cliVersion = "0.1.6",
        )
        val outcome = ReloadOrchestrator(config, runner).cycle(projectDir.resolve("app/src/main/kotlin/Foo.kt"))
        server.close()

        assertTrue(outcome is CycleOutcome.DeviceError)
        val reason = (outcome as CycleOutcome.DeviceError).reason
        assertTrue(reason.contains("0.1.6"), reason)
        assertTrue(reason.contains("0.1.5"), reason)
        assertEquals(
            2, runner.calls.size,
            "expected cycle to stop right after the version-mismatched identity check, no compile/push/LOAD_DEX: ${runner.calls}",
        )
    }

    @Test
    fun `cycle proceeds past the identity check when the runtime version matches, reaching the compile stage`() {
        val projectDir = tmp.newFolder("project-cycle-version-match").toPath()
        val agentSoDir = tmp.newFolder("agent-so-cycle-version-match").toPath()
        val server = fakePingServer("pong:dev.thuat.hotreload.sample:0.1.6")

        val runner = SequencedRunner(listOf(deviceReady, ProcessResult(0, "", "")))
        val config = ReloadConfig(
            projectDir = projectDir, pkg = "dev.thuat.hotreload.sample", serial = null,
            adbPath = "adb", agentSoDir = agentSoDir, localPort = server.localPort, cliVersion = "0.1.6",
        )
        // The changed file maps to no gradle module in this bare project dir, so a CompileError
        // (rather than the DeviceError above) proves cycle() got past the version/identity gate.
        val outcome = ReloadOrchestrator(config, runner).cycle(projectDir.resolve("app/src/main/kotlin/Foo.kt"))
        server.close()

        assertTrue(outcome is CycleOutcome.CompileError)
    }

    @Test
    fun `cycle proceeds past the identity check when the runtime version is unknown, reaching the compile stage`() {
        val projectDir = tmp.newFolder("project-cycle-version-unknown").toPath()
        val agentSoDir = tmp.newFolder("agent-so-cycle-version-unknown").toPath()
        val server = fakePingServer("pong:dev.thuat.hotreload.sample")

        val runner = SequencedRunner(listOf(deviceReady, ProcessResult(0, "", "")))
        val config = ReloadConfig(
            projectDir = projectDir, pkg = "dev.thuat.hotreload.sample", serial = null,
            adbPath = "adb", agentSoDir = agentSoDir, localPort = server.localPort, cliVersion = "0.1.6",
        )
        val outcome = ReloadOrchestrator(config, runner).cycle(projectDir.resolve("app/src/main/kotlin/Foo.kt"))
        server.close()

        assertTrue(outcome is CycleOutcome.CompileError)
    }

    // Port derivation: bootstrap() and a later, separate `cycle` process must agree on the same
    // local port with no shared state between them — see derivePort's doc in
    // ReloadOrchestrator.kt for why a hash of the package name is used instead of scanning for a
    // free port or letting adb assign one.
    @Test
    fun `derivePort is deterministic for the same package`() {
        assertEquals(derivePort("com.example.orderbook"), derivePort("com.example.orderbook"))
    }

    @Test
    fun `derivePort differs for two different packages`() {
        assertTrue(derivePort("com.example.orderbook") != derivePort("dev.thuat.hotreload.sample"))
    }
}
