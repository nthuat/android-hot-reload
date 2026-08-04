package dev.thuat.hotreload.cli

import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class ReloadConfig(
    val projectDir: Path,
    val pkg: String,
    val serial: String?,
    val adbPath: String,
    val agentSoDir: Path,   // dir containing <abi>/libhotreload_agent.so
    val appModule: String = ":app",
    // Per-package by default (see derivePort doc) — not a single fixed port — so two
    // independently-run hotreload sessions on one machine (two consumer projects, or an e2e run
    // against the sample app) don't collide. `--port` (Main.kt) overrides this explicitly.
    val localPort: Int = derivePort(pkg),
    // --java-home (Main.kt): points the Tooling API's build daemon at a specific JDK instead of
    // the CLI's own JVM (GradleCompiler.compile's default) — the fix for a JDK too new for the
    // consumer project's Gradle version (see JdkPreflight.kt) without touching the user's shell.
    val javaHome: Path? = null,
    // This CLI build's own version, compared against the on-device runtime's self-reported
    // version on every bootstrap()/cycle() (see checkRuntimeVersion). Defaults to the real build
    // value (CliVersion.VERSION) but overridable so tests can simulate a specific CLI version
    // without needing a real build artifact on the classpath.
    val cliVersion: String = CliVersion.VERSION,
)

sealed class CycleOutcome {
    // `skipped`: binary names of classes that were part of this cycle's changed set but weren't
    // currently loaded on-device, so the agent left them untouched instead of failing the whole
    // batch (see agent.cpp HandleLoadDex's doc for why this is safe — e.g. a
    // ComposableSingletons$...Kt$lambda-N$1 holder for a @Preview-only lambda). `classes` is
    // empty and `skipped` non-empty exactly when every changed class in the batch was skipped —
    // Main.kt reports that case as "nothing applied" rather than a normal reload.
    data class Reloaded(
        val classes: List<String>,
        val millis: Long,
        val tier: String? = null,
        val skipped: List<String> = emptyList(),
        // Per-phase wall time for this cycle, insertion-ordered (compile, diff, dex, push,
        // redefine) — empty for bootstrap's synthetic Reloaded(0ms) result. Diagnostic-only
        // (see Main.kt's report()); lets a slow cycle be attributed to a phase without
        // guesswork instead of re-deriving it from scratch each time (see F1: a redundant
        // per-class D8 split loop hid behind one 24s number until this was measured directly).
        val phaseMillis: Map<String, Long> = emptyMap(),
        // Non-fatal, user-visible heads-up (see checkRuntimeVersion) — currently only "the
        // on-device runtime's version is unknown, predates this handshake". Null on every
        // ordinary reload. Main.kt's report() prints it ahead of the normal reload line.
        val warning: String? = null,
    ) : CycleOutcome()
    data class CompileError(val output: String) : CycleOutcome()
    data class Incompatible(val reason: String) : CycleOutcome()
    data class DeviceError(val reason: String) : CycleOutcome()
    // Environment problem, not the user's source or the device: a JDK too new for the consumer
    // project's Gradle version (see JdkPreflight.kt). Kept distinct from DeviceError — that name
    // stops making sense for "your JDK is wrong" — while sharing its exit code (3), since both
    // are "the CLI can't proceed for reasons outside the user's changed code".
    data class EnvironmentError(val reason: String) : CycleOutcome()
    object NoChanges : CycleOutcome()
}

// Compose's `generateFunctionKeyMetaClasses` compiler option (enabled by the gradle-plugin on
// debug builds) emits a `<Facade>$KeyMeta` sibling class per source file. Its @FunctionKeyMeta
// offset annotations shift on every composable body edit, so it shows up in ClassDiffer's
// `changed` set alongside the real facade class on essentially every reload — but it must never
// be pushed for redefinition. Top-level (not file-private) and pure so it's directly unit
// testable without standing up an orchestrator.
internal fun isKeyMetaClass(binaryName: String): Boolean =
    binaryName.substringAfterLast('.').contains("\$KeyMeta")

// Abstract socket name the agent binds and the CLI forwards to. Per-package (not a fixed global
// name): two instrumented apps on the same device would otherwise collide on one socket, and the
// CLI could end up talking to the wrong app's agent. The agent independently derives the exact
// same name from its own process name (see agent.cpp) — no handshake needed to agree on it.
internal fun agentSocketName(pkg: String): String = "hotreload-agent-$pkg"

// Deterministic per-package local port: `bootstrap` and a later, separate `cycle` process must
// agree on the same port with no shared state between them (no lockfile, no daemon), so this
// can't scan for a free port or ask adb to assign one (adb forward tcp:0 ...) — either would
// need somewhere to persist the chosen port for the next process to find. Hashing the package
// name is the simplest thing that's deterministic across processes. Masked to the low 12 bits:
// a 4096-port range starting at 46837 (through 50932). Collision ceiling: two packages whose
// hashCode()s happen to agree in their low 12 bits (~1/4096 per pair) would still collide on one
// port — same failure mode this whole fix is closing, just far rarer. `--port` (Main.kt)
// overrides this if that ever bites in practice.
internal fun derivePort(pkg: String, basePort: Int = 46837): Int =
    basePort + (pkg.hashCode() and 0x0FFF)

// Agent appends " | tierN" to a successful LOAD_DEX reply's detail (see agent.cpp NotifyRuntime)
// once ComposeInvalidator.reload() reports back which tier fired for the whole batch. Always the
// last " | "-delimited segment (see Protocol.kt's detail-format doc), so this keeps working
// whether or not a "skipped" segment precedes it. Top-level and pure for direct unit testing.
internal fun parseTier(detail: String): String? =
    detail.substringAfterLast(" | ", "").takeIf { it.startsWith("tier") }

// Parses the optional "skipped <N>: <d1>, <d2>, ..." segment out of a LOAD_DEX reply detail (see
// Protocol.kt's detail-format doc) into the list of skipped class descriptors. Returns an empty
// list when no class was skipped. Top-level and pure for direct unit testing.
internal fun parseSkippedDescriptors(detail: String): List<String> {
    val segment = detail.split(" | ").firstOrNull { it.startsWith("skipped ") } ?: return emptyList()
    val descriptors = segment.substringAfter(": ", "")
    return if (descriptors.isEmpty()) emptyList() else descriptors.split(", ")
}

// Compares the on-device runtime's self-reported version (Protocol.pingRuntimeVersionOf's result
// on a PING reply) against this CLI's own version. EXACT match, not a compatible range: the wire
// protocol has already changed once mid-series (0.1.5 added Compose group keys to LOAD_DEX) with
// no forward- or backward-compatibility shim on either side, so there is no verified "compatible
// range" to encode — inventing one would just be a range nobody has actually tested, which is
// worse than no range at all. Re-evaluate this rule (and this comment) if the protocol ever grows
// a real compatibility window.
//
// Returns a DeviceError only on a genuine mismatch (both versions known and different) — callers
// must run this before any compile/dex/push/LOAD_DEX work, exactly like verifyAgentIdentity's
// package check, so a mismatched pair fails loudly up front instead of the CLI printing
// "✓ reloaded" over an agent that silently skipped the recompose call (see the fix report: 0.1.5's
// new LOAD_DEX signature made an older runtime's ComposeInvalidator.reload lookup fail invisibly
// on-device). An unknown runtime version (null — no second field in the PING reply — or the
// literal Protocol.UNKNOWN_RUNTIME_VERSION) is deliberately NOT a mismatch: hard-failing every
// already-published runtime the moment this feature ships would make the CLI unusable against
// anything already out there, so it's surfaced as a warning string instead and the caller
// proceeds. Top-level and pure for direct unit testing.
internal fun checkRuntimeVersion(cliVersion: String, runtimeVersion: String?): CycleOutcome.DeviceError? =
    when {
        runtimeVersion == null || runtimeVersion == Protocol.UNKNOWN_RUNTIME_VERSION -> null
        runtimeVersion != cliVersion -> CycleOutcome.DeviceError(
            "runtime version mismatch: this CLI is $cliVersion but the on-device runtime library " +
                "is $runtimeVersion. Align them by running './gradlew hotReloadInstallCli' in the " +
                "consumer project, or pin the plugin version to $cliVersion " +
                "(e.g. HOTRELOAD_VERSION=v$cliVersion with install.sh)"
        )
        else -> null
    }

// The user-visible warning attached to a Reloaded outcome when the on-device runtime's version is
// unknown (see checkRuntimeVersion's doc for why that's a warning, not a failure). Null when the
// version is known (whether it matched or checkRuntimeVersion already turned a mismatch into a
// DeviceError). Top-level and pure for direct unit testing.
internal fun unknownRuntimeVersionWarning(cliVersion: String, runtimeVersion: String?): String? =
    if (runtimeVersion == null || runtimeVersion == Protocol.UNKNOWN_RUNTIME_VERSION) {
        "on-device runtime version unknown (predates this handshake). This CLI is $cliVersion; " +
            "verify the plugin version matches it if reload behaves oddly"
    } else {
        null
    }

// `am attach-agent` hands the request off to ART rather than blocking until Agent_OnAttach has
// actually run, so the first ping after a fresh attach can arrive before the agent is listening.
// 10 * 300ms = 3s of slack, well under what a human would notice as "bootstrap hung".
private const val BOOTSTRAP_PING_RETRIES = 10
private const val BOOTSTRAP_PING_RETRY_DELAY_MS = 300L

class ReloadOrchestrator(private val config: ReloadConfig, runner: ProcessRunner = RealProcessRunner()) {
    private val adb = Adb(config.adbPath, config.serial, runner)
    private val resolver = ModuleResolver(config.projectDir)
    private val differ = ClassDiffer()
    private val store = BaselineStore(config.projectDir.resolve(".hotreload/baseline.txt"))
    private val compiler = GradleCompiler(config.projectDir, config.appModule, config.javaHome)
    private val dexer = DexPackager(config.projectDir, config.appModule)

    // Modules with no class output are skipped, not fatal — com.android.test (baseline-profile,
    // benchmark) modules and resource-only libraries legitimately have none. Only an empty
    // aggregate means the layout probe is wrong, and that is worth failing loudly on: it is what
    // made the AGP 9 breakage present as "no bytecode changes" forever instead of an error.
    private fun allClassDirs(): List<java.nio.file.Path> {
        val modules = resolver.allModules()
        val dirs = modules.flatMap(resolver::classDirsOf)
        check(dirs.isNotEmpty()) {
            "no compiled-class output found in any module. Looked for:\n" +
                modules.flatMap(resolver::classDirCandidatesFor).joinToString("\n") { "  - $it" } +
                "\n(checked AGP 8 + Kotlin-Gradle-Plugin, AGP 9 built-in-Kotlin, and javac layouts). " +
                "Build the app first (./gradlew ${config.appModule}:assembleDebug), or this project's " +
                "toolchain uses a layout this tool doesn't know about yet."
        }
        return dirs
    }

    fun bootstrap(): CycleOutcome {
        deviceNotReadyError()?.let { return it }
        if (!adb.isAppRunning(config.pkg)) {
            return CycleOutcome.DeviceError("${config.pkg} is not running. Launch the app first")
        }
        adb.forward(config.localPort, agentSocketName(config.pkg)).failureOrNull("adb forward")?.let { return it }

        // Skip re-pushing/re-copying/re-attaching entirely if a previous bootstrap already has
        // a responsive agent running. This isn't just an optimization to save a few adb round
        // trips: `adb push` + the run-as `cp` overwrite the exact file the already-running
        // process has mmap'd as executable code (`cp` truncates-then-rewrites in place, same
        // inode, not an atomic rename-into-place). A not-yet-faulted-in code page in the *live*
        // process can then read back mismatched bytes the next time it's touched — reproduced
        // on-device as a SIGSEGV in ordinary, unrelated code (getsockopt/memset/read) shortly
        // after a second bootstrap call re-pushed the identical .so over an already-attached
        // agent. Pinging first, and returning immediately on success, means an already-running
        // agent's backing file is never touched again.
        pingAgent()?.takeIf { it.status == Protocol.STATUS_OK }?.let { reply ->
            val runtimeVersion = Protocol.pingRuntimeVersionOf(reply.detail)
            checkRuntimeVersion(config.cliVersion, runtimeVersion)?.let { return it }
            store.save(differ.snapshot(allClassDirs()))
            return CycleOutcome.Reloaded(
                emptyList(), 0,
                warning = unknownRuntimeVersionWarning(config.cliVersion, runtimeVersion),
            )
        }

        val abi = adb.getprop("ro.product.cpu.abi")
        val so = config.agentSoDir.resolve(abi).resolve("libhotreload_agent.so")
        if (!Files.exists(so)) {
            return CycleOutcome.DeviceError(
                "agent .so for abi '$abi' not found at $so\n" +
                    "  → rebuild the tool with ./gradlew :cli:installDist, or pass --agent-so-dir <dir>"
            )
        }

        adb.push(so, "/data/local/tmp/hotreload/agent.so").failureOrNull("push agent.so")?.let { return it }
        adb.runAsCopy(config.pkg, "/data/local/tmp/hotreload/agent.so", "hotreload/agent.so")
            .failureOrNull("copy agent.so into app sandbox")?.let { return it }
        adb.attachAgent(config.pkg, "${adb.appDataDir(config.pkg)}/code_cache/hotreload/agent.so")
            .failureOrNull("attach-agent")?.let { return it }

        // `am attach-agent` can return before ART has actually invoked Agent_OnAttach on the
        // target process (it hands the request off rather than blocking on it), so the first
        // ping right after a fresh attach can race a not-yet-listening agent. Retry briefly
        // instead of failing on that benign startup race.
        var lastDetail: String? = null
        repeat(BOOTSTRAP_PING_RETRIES) { attempt ->
            val reply = pingAgent()
            if (reply?.status == Protocol.STATUS_OK) {
                val runtimeVersion = Protocol.pingRuntimeVersionOf(reply.detail)
                checkRuntimeVersion(config.cliVersion, runtimeVersion)?.let { return it }
                store.save(differ.snapshot(allClassDirs()))
                return CycleOutcome.Reloaded(  // bootstrap ok; nothing reloaded yet
                    emptyList(), 0,
                    warning = unknownRuntimeVersionWarning(config.cliVersion, runtimeVersion),
                )
            }
            lastDetail = reply?.detail
            if (attempt < BOOTSTRAP_PING_RETRIES - 1) Thread.sleep(BOOTSTRAP_PING_RETRY_DELAY_MS)
        }
        return CycleOutcome.DeviceError("agent ping failed: ${lastDetail ?: "no reply"}")
    }

    private fun pingAgent(): Reply? =
        runCatching { AgentClient("localhost", config.localPort).use { it.ping() } }.getOrNull()

    // Result of verifyAgentIdentity: `error` is non-null on any hard failure (wrong/no agent, or
    // a genuine runtime version mismatch — see checkRuntimeVersion) and callers must bail out
    // immediately, exactly like the old Boolean-shaped check. `warning`, only ever populated
    // alongside a null `error`, threads the unknown-runtime-version heads-up (see
    // unknownRuntimeVersionWarning) through to whatever CycleOutcome.Reloaded cycle() eventually
    // builds — computed here, once, right after the one PING this function already sends, rather
    // than re-pinging later just to read it again.
    private data class IdentityCheck(val error: CycleOutcome.DeviceError?, val warning: String? = null)

    // The agent names its own package in every PING reply (see Protocol.pingPackageOf /
    // agent.cpp's g_pkg_name — the same string it already uses to build its per-package socket
    // name), and now its own runtime library's version (see Protocol.pingRuntimeVersionOf).
    // Checked here, before cycle() does any compile/dex/push work or LOAD_DEX, so a stale/wrong
    // `adb forward` mapping OR a mismatched runtime library is caught by protocol content, not
    // just by the forward re-pointing above — a second (and third) line of defense against ever
    // redefining classes in the wrong running app, or against a version-skewed pair silently
    // no-op'ing a reload (see checkRuntimeVersion's doc for that failure mode).
    private fun verifyAgentIdentity(): IdentityCheck {
        val reply = pingAgent()
            ?: return IdentityCheck(
                CycleOutcome.DeviceError(
                    "no agent responded on port ${config.localPort} for ${config.pkg}; run 'bootstrap' first"
                )
            )
        val actualPkg = Protocol.pingPackageOf(reply.detail)
        val identityError = when {
            actualPkg == null -> CycleOutcome.DeviceError(
                "agent ping reply did not name a package (got '${reply.detail}'); run 'bootstrap' again"
            )
            actualPkg != config.pkg ->
                CycleOutcome.DeviceError(
                    "wrong app: expected ${config.pkg}'s agent but reached ${actualPkg}'s agent on port " +
                        "${config.localPort} (stale 'adb forward' mapping from another bootstrapped app); " +
                        "run 'bootstrap' again for ${config.pkg}"
                )
            else -> null
        }
        if (identityError != null) return IdentityCheck(identityError)

        val runtimeVersion = Protocol.pingRuntimeVersionOf(reply.detail)
        val versionError = checkRuntimeVersion(config.cliVersion, runtimeVersion)
        if (versionError != null) return IdentityCheck(versionError)
        return IdentityCheck(error = null, warning = unknownRuntimeVersionWarning(config.cliVersion, runtimeVersion))
    }

    // Bounds bootstrap()/cycle() to a cheap, fast-failing check before any compile/adb-transfer
    // work: `adb get-state` fails outright when the serial is gone, and reports "offline"/
    // "unauthorized" for a wedged device without ever touching the app.
    private fun deviceNotReadyError(): CycleOutcome.DeviceError? {
        val result = adb.getState()
        result.failureOrNull("adb get-state")?.let { return it }
        val state = result.stdout.trim()
        return if (state == "device") null else CycleOutcome.DeviceError(
            "device not ready (state: ${state.ifEmpty { "unknown" }}); check `adb devices`, " +
                "and re-run `bootstrap` after restarting the app"
        )
    }

    fun cycle(changedFile: Path): CycleOutcome {
        // A device can die between cycles (this is exactly the bug that motivated this check —
        // an emulator went offline mid-session and the CLI hung indefinitely instead of failing
        // fast). Cheap enough to check before sinking a full compile into a dead device.
        deviceNotReadyError()?.let { return it }

        // `adb forward tcp:PORT ...` is a single GLOBAL, per-device mapping — bootstrap() sets
        // it, but until this fix cycle() never did, so it just trusted whatever the forward
        // currently pointed at. bootstrapping a *second* app on the same device (a different
        // project, or an e2e run against the sample app) silently repoints it, and every
        // subsequent cycle for the first app would then send its LOAD_DEX to the second app's
        // agent — reproduced live (see the fix's report): it failed safely only because the two
        // apps' data dirs differed; with a same-named loaded class it would have redefined the
        // WRONG RUNNING APP. `adb forward` is idempotent and cheap (one local round trip, no
        // device-side work if already pointed here), so just re-issue it for this cycle's own
        // package every time instead of trusting a mapping some other process may have moved.
        adb.forward(config.localPort, agentSocketName(config.pkg)).failureOrNull("adb forward")?.let { return it }

        // Belt-and-braces on top of the forward fix above: verify the far end is actually this
        // package's agent (and speaks a matching runtime version) before doing any compile/dex/
        // push work, let alone LOAD_DEX. Catches any other port/forward confusion, not just the
        // one just closed above.
        val identity = verifyAgentIdentity()
        identity.error?.let { return it }

        val start = System.currentTimeMillis()
        resolver.moduleOf(changedFile)
            ?: return CycleOutcome.CompileError("cannot map $changedFile to a gradle module")

        var t = System.currentTimeMillis()
        val compileResult = compiler.compile()
        val compileMs = System.currentTimeMillis() - t
        if (!compileResult.success) {
            // Preflight (Main.kt, before this cycle ever started) already ruled out the CLI's own
            // JVM being too new — this catches the late case: a project-pinned org.gradle.java.home
            // or toolchain resolving to a different, unsupported JDK that preflight couldn't have
            // seen coming. Raw output is always preserved; the hint is only ever appended.
            val hint = unsupportedJvmHint(compileResult.output, readWrapperGradleVersion(config.projectDir))
            return if (hint != null) {
                CycleOutcome.EnvironmentError("${compileResult.output}\n\n$hint")
            } else {
                CycleOutcome.CompileError(compileResult.output)
            }
        }

        t = System.currentTimeMillis()
        val current = differ.snapshot(allClassDirs())
        val diff = differ.diff(store.load(), current)
        val diffMs = System.currentTimeMillis() - t
        if (diff.added.isNotEmpty() || diff.removed.isNotEmpty()) {
            return CycleOutcome.Incompatible(
                "structural change (added: ${diff.added}, removed: ${diff.removed}); full rebuild needed"
            )
        }
        if (diff.changed.isEmpty()) return CycleOutcome.NoChanges

        // Never push $KeyMeta classes for redefinition (see isKeyMetaClass doc). They never
        // need it: ComposeInvalidator.keysForClass reads keys from the *originally loaded*
        // KeyMeta class, content-stable across body edits (same keys, only source offsets
        // move) — and pushing it is actively harmful. ClassDiffer.snapshot walks the
        // filesystem in directory order, which is not guaranteed to be facade-before-KeyMeta.
        // The KeyMeta class itself loads lazily (only Class.forName'd inside keysForClass,
        // which only runs from ComposeInvalidator.reload() after RedefineClasses succeeds).
        // If the walk yields the KeyMeta class before its facade, the agent's FindLoadedClass
        // lookup for it comes up empty — nothing has touched it yet — and the push fails with
        // "class not loaded", a spurious rebuild-required exit that depends on filesystem walk
        // order (passed on APFS by luck; not guaranteed on any other filesystem or a future
        // JDK's Files.walk ordering).
        val toRedefine = diff.changed.filterNot { isKeyMetaClass(it.binaryName) }
        if (toRedefine.isEmpty()) return CycleOutcome.NoChanges

        val dexDir = config.projectDir.resolve(".hotreload/dex")
        // Batch call: splits each merged-dex bucket at most once for the whole cycle instead of
        // once per changed class (see DexPackager.dexClasses doc — this is F1's fix, was ~20
        // redundant full-bucket D8 splits per cycle). A class dexer.dexClasses can't find throws
        // (uncaught here, same as the old per-class dexClass()'s behavior) — matches today's
        // "missing class surfaces as an error" contract rather than a soft DeviceError.
        t = System.currentTimeMillis()
        val dexed = dexer.dexClasses(toRedefine, dexDir)
        val dexMs = System.currentTimeMillis() - t

        t = System.currentTimeMillis()
        val records = mutableListOf<LoadDexEntry>()
        // Push every dex file first, THEN send one LOAD_DEX for the whole batch (see
        // agentSocketName / Protocol.RECORD_SEP docs): the agent redefines all of them in one
        // JVMTI RedefineClasses(n, defs) call, which JVMTI applies atomically. Sending N
        // separate single-class messages (old behavior) meant a mid-sequence rejection left
        // earlier classes already swapped — old/new code mixed in one running app, with no way
        // back — which is exactly the "never leaves the app corrupted" guarantee this tool
        // promises not to break.
        for (changed in toRedefine) {
            val dex = dexed.getValue(changed)
            // Content-hash-prefixed device filename: guarantees this cycle's dex never
            // silently reuses a path a previous, possibly-failed cycle already wrote. Without
            // this, a failed push/copy below (or even before F1's exit-code checks existed at
            // all) could leave a *previous* cycle's dex sitting at a fixed path; the agent
            // would redefine that stale file, reply OK, and the CLI would advance the baseline
            // over permanently-invisible stale code. Old dex files under code_cache/hotreload
            // are not cleaned up — they accumulate for the life of the app's debug process,
            // which is acceptable for v1 (code_cache is app-private and wiped by `pm clear`/
            // uninstall, and a dev session's edit count is small); add housekeeping if a long
            // session's file count ever becomes a problem.
            val deviceName = "${contentHashPrefix(dex)}-${dex.fileName}"
            adb.push(dex, "/data/local/tmp/hotreload/$deviceName").failureOrNull("push ${dex.fileName}")?.let { return it }
            adb.runAsCopy(config.pkg, "/data/local/tmp/hotreload/$deviceName", "hotreload/$deviceName")
                .failureOrNull("copy ${dex.fileName} into app sandbox")?.let { return it }
            val devicePath = "${adb.appDataDir(config.pkg)}/code_cache/hotreload/$deviceName"
            // Extracted here, not batched separately: KeyMetaExtractor.keysFor reads the exact
            // same already-compiled .class file dexClasses just split from, so this is a cheap
            // in-memory ASM pass, not a second compile. See Protocol.RECORD_SEP's doc for what an
            // empty result means on the device side (falls back to its own lookup, tier2, tier3).
            val keys = KeyMetaExtractor.keysFor(changed)
            records += LoadDexEntry(changed.descriptor, devicePath, keys)
        }
        val pushMs = System.currentTimeMillis() - t

        t = System.currentTimeMillis()
        val reply = runCatching {
            AgentClient("localhost", config.localPort).use { it.loadDex(records) }
        }.getOrElse { return CycleOutcome.DeviceError(agentFailureMessage(it)) }
        val redefineMs = System.currentTimeMillis() - t

        val phaseMillis = linkedMapOf(
            "compile" to compileMs,
            "diff" to diffMs,
            "dex" to dexMs,
            "push" to pushMs,
            "redefine" to redefineMs,
        )

        return when (reply.status) {
            Protocol.STATUS_OK -> {
                store.save(current)
                val skippedDescriptors = parseSkippedDescriptors(reply.detail).toSet()
                val (skipped, redefined) = toRedefine.partition { it.descriptor in skippedDescriptors }
                CycleOutcome.Reloaded(
                    classes = redefined.map { it.binaryName },
                    millis = System.currentTimeMillis() - start,
                    tier = parseTier(reply.detail),
                    skipped = skipped.map { it.binaryName },
                    phaseMillis = phaseMillis,
                    warning = identity.warning,
                )
            }
            Protocol.STATUS_ERROR -> CycleOutcome.DeviceError(reply.detail)
            else -> CycleOutcome.Incompatible(reply.detail)
        }
    }

    private fun contentHashPrefix(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }.take(12)
}

// exitCode != 0 on any of adb push / run-as copy / attach-agent / forward means the *previous*
// cycle's device-side state (agent binary, or a stale dex) is left in place while the CLI has
// already moved on in its own bookkeeping — the exact silent-stale-code hole this closes.
// Non-zero always maps to DeviceError with the command's stderr (falling back to stdout, since
// some adb failures write there instead) so it's surfaced loudly rather than swallowed. A timed
// out call (see ProcessRunner.DEFAULT_ADB_TIMEOUT_MS) gets its own message naming the timeout
// rather than a meaningless exit code — this is the fix for the hang reproduced live: adb wedged
// against an offline device used to block forever with no output, no error, no timeout.
private fun ProcessResult.failureOrNull(action: String): CycleOutcome.DeviceError? = when {
    timedOut -> CycleOutcome.DeviceError(
        "$action timed out after ${DEFAULT_ADB_TIMEOUT_MS / 1000}s, device may be offline or " +
            "unresponsive; check `adb devices`, and re-run `bootstrap` after restarting the app"
    )
    exitCode != 0 -> CycleOutcome.DeviceError("$action failed (exit $exitCode): ${stderr.trim().ifEmpty { stdout.trim() }}")
    else -> null
}

// Distinguishes an agent-socket timeout (device/app alive but the agent never replied within
// DEFAULT_READ_TIMEOUT_MS) from any other connection failure (e.g. nothing listening because the
// agent isn't attached), so the user gets a message naming which side stalled.
private fun agentFailureMessage(cause: Throwable): String =
    if (cause is SocketTimeoutException) {
        "agent socket timed out after ${DEFAULT_READ_TIMEOUT_MS / 1000}s, device/app may be " +
            "unresponsive; check `adb devices`, and re-run `bootstrap` after restarting the app"
    } else {
        "agent connection failed: ${cause.message}"
    }
