package dev.thuat.hotreload.cli

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
    val localPort: Int = 46837,
)

sealed class CycleOutcome {
    data class Reloaded(val classes: List<String>, val millis: Long, val tier: String? = null) : CycleOutcome()
    data class CompileError(val output: String) : CycleOutcome()
    data class Incompatible(val reason: String) : CycleOutcome()
    data class DeviceError(val reason: String) : CycleOutcome()
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
    private val compiler = GradleCompiler(config.projectDir, config.appModule)
    private val dexer = DexPackager(config.projectDir, config.appModule)

    private fun allClassDirs() = resolver.allModules().flatMap(resolver::classDirsOf)

    fun bootstrap(): CycleOutcome {
        if (!adb.isAppRunning(config.pkg)) {
            return CycleOutcome.DeviceError("${config.pkg} is not running — launch the app first")
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
        if (pingAgent()?.status == Protocol.STATUS_OK) {
            store.save(differ.snapshot(allClassDirs()))
            return CycleOutcome.Reloaded(emptyList(), 0)
        }

        val abi = adb.getprop("ro.product.cpu.abi")
        val so = config.agentSoDir.resolve(abi).resolve("libhotreload_agent.so")
        if (!Files.exists(so)) {
            return CycleOutcome.DeviceError("agent .so for abi '$abi' not found at $so — run ./gradlew :agent:assembleDebug")
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
                store.save(differ.snapshot(allClassDirs()))
                return CycleOutcome.Reloaded(emptyList(), 0)  // bootstrap ok; nothing reloaded yet
            }
            lastDetail = reply?.detail
            if (attempt < BOOTSTRAP_PING_RETRIES - 1) Thread.sleep(BOOTSTRAP_PING_RETRY_DELAY_MS)
        }
        return CycleOutcome.DeviceError("agent ping failed: ${lastDetail ?: "no reply"}")
    }

    private fun pingAgent(): Reply? =
        runCatching { AgentClient("localhost", config.localPort).use { it.ping() } }.getOrNull()

    fun cycle(changedFile: Path): CycleOutcome {
        val start = System.currentTimeMillis()
        resolver.moduleOf(changedFile)
            ?: return CycleOutcome.CompileError("cannot map $changedFile to a gradle module")

        val compileResult = compiler.compile()
        if (!compileResult.success) return CycleOutcome.CompileError(compileResult.output)

        val current = differ.snapshot(allClassDirs())
        val diff = differ.diff(store.load(), current)
        if (diff.added.isNotEmpty() || diff.removed.isNotEmpty()) {
            return CycleOutcome.Incompatible(
                "structural change (added: ${diff.added}, removed: ${diff.removed}) — full rebuild needed"
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
        val records = mutableListOf<Pair<String, String>>()
        // Push every dex file first, THEN send one LOAD_DEX for the whole batch (see
        // agentSocketName / Protocol.RECORD_SEP docs): the agent redefines all of them in one
        // JVMTI RedefineClasses(n, defs) call, which JVMTI applies atomically. Sending N
        // separate single-class messages (old behavior) meant a mid-sequence rejection left
        // earlier classes already swapped — old/new code mixed in one running app, with no way
        // back — which is exactly the "never leaves the app corrupted" guarantee this tool
        // promises not to break.
        for (changed in toRedefine) {
            val dex = dexer.dexClass(changed, dexDir)
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
            records += changed.descriptor to devicePath
        }

        val reply = runCatching {
            AgentClient("localhost", config.localPort).use { it.loadDex(records) }
        }.getOrElse { return CycleOutcome.DeviceError("agent connection failed: ${it.message}") }

        return when (reply.status) {
            Protocol.STATUS_OK -> {
                store.save(current)
                CycleOutcome.Reloaded(toRedefine.map { it.binaryName }, System.currentTimeMillis() - start, parseTier(reply.detail))
            }
            Protocol.STATUS_ERROR -> CycleOutcome.DeviceError(reply.detail)
            else -> CycleOutcome.Incompatible(reply.detail)
        }
    }

    // Agent appends " | tierN" to a successful LOAD_DEX reply's detail (see agent.cpp
    // NotifyRuntime) once ComposeInvalidator.reload() reports back which tier fired for the
    // whole batch.
    private fun parseTier(detail: String): String? =
        detail.substringAfterLast(" | ", "").takeIf { it.startsWith("tier") }

    private fun contentHashPrefix(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }.take(12)
}

// exitCode != 0 on any of adb push / run-as copy / attach-agent / forward means the *previous*
// cycle's device-side state (agent binary, or a stale dex) is left in place while the CLI has
// already moved on in its own bookkeeping — the exact silent-stale-code hole this closes.
// Non-zero always maps to DeviceError with the command's stderr (falling back to stdout, since
// some adb failures write there instead) so it's surfaced loudly rather than swallowed.
private fun ProcessResult.failureOrNull(action: String): CycleOutcome.DeviceError? =
    if (exitCode != 0) {
        CycleOutcome.DeviceError("$action failed (exit $exitCode): ${stderr.trim().ifEmpty { stdout.trim() }}")
    } else {
        null
    }
