package dev.hotreload.cli

import java.nio.file.Path

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

class ReloadOrchestrator(private val config: ReloadConfig) {
    private val adb = Adb(config.adbPath, config.serial)
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
        val abi = adb.getprop("ro.product.cpu.abi")
        val so = config.agentSoDir.resolve(abi).resolve("libhotreload_agent.so")
        if (!java.nio.file.Files.exists(so)) {
            return CycleOutcome.DeviceError("agent .so for abi '$abi' not found at $so — run ./gradlew :agent:assembleDebug")
        }
        adb.push(so, "/data/local/tmp/hotreload/agent.so")
        adb.runAsCopy(config.pkg, "/data/local/tmp/hotreload/agent.so", "hotreload/agent.so")
        adb.attachAgent(config.pkg, "${adb.appDataDir(config.pkg)}/code_cache/hotreload/agent.so")
        adb.forward(config.localPort, "hotreload-agent")

        val ping = runCatching { AgentClient("localhost", config.localPort).use { it.ping() } }
        if (ping.getOrNull()?.status != Protocol.STATUS_OK) {
            return CycleOutcome.DeviceError("agent ping failed: ${ping.exceptionOrNull()?.message ?: ping.getOrNull()?.detail}")
        }
        store.save(differ.snapshot(allClassDirs()))
        return CycleOutcome.Reloaded(emptyList(), 0)  // bootstrap ok; nothing reloaded yet
    }

    fun cycle(changedFile: Path): CycleOutcome {
        val start = System.currentTimeMillis()
        resolver.moduleOf(changedFile)
            ?: return CycleOutcome.CompileError("cannot map $changedFile to a gradle module")

        val compileResult = compiler.compile()
        if (!compileResult.success) return CycleOutcome.CompileError(compileResult.output)

        val current = differ.snapshot(allClassDirs())
        val diff = differ.diff(store.load(), current, allClassDirs())
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
        // which only runs from ComposeInvalidator.reload() after a *different* class's
        // RedefineClasses call succeeds). If the walk yields the KeyMeta class before its
        // facade, the agent's FindLoadedClass lookup for it comes up empty — nothing has
        // touched it yet — and the push fails with "class not loaded", a spurious rebuild-
        // required exit that depends on filesystem walk order (passed on APFS by luck; not
        // guaranteed on any other filesystem or a future JDK's Files.walk ordering).
        val toRedefine = diff.changed.filterNot { isKeyMetaClass(it.binaryName) }

        val dexDir = config.projectDir.resolve(".hotreload/dex")
        var tier: String? = null
        for (changed in toRedefine) {
            val dex = dexer.dexClass(changed, dexDir)
            val simpleName = dex.fileName.toString()
            adb.push(dex, "/data/local/tmp/hotreload/$simpleName")
            adb.runAsCopy(config.pkg, "/data/local/tmp/hotreload/$simpleName", "hotreload/$simpleName")
            val devicePath = "${adb.appDataDir(config.pkg)}/code_cache/hotreload/$simpleName"
            val reply = runCatching {
                AgentClient("localhost", config.localPort).use { it.loadDex(changed.descriptor, devicePath) }
            }.getOrElse { return CycleOutcome.DeviceError("agent connection failed: ${it.message}") }
            if (reply.status != Protocol.STATUS_OK) {
                return CycleOutcome.Incompatible(reply.detail)
            }
            parseTier(reply.detail)?.let { tier = it }
        }
        store.save(current)
        return CycleOutcome.Reloaded(toRedefine.map { it.binaryName }, System.currentTimeMillis() - start, tier)
    }

    // Agent appends " | tierN" to a successful LOAD_DEX reply's detail (see agent.cpp
    // NotifyRuntime) once ComposeInvalidator.reload() reports back which tier fired. Take the
    // *last* pushed class's tier as the cycle's reported tier: with several classes redefined
    // from one edit, the last reload() call reflects the composition's final settled state.
    private fun parseTier(detail: String): String? =
        detail.substringAfterLast(" | ", "").takeIf { it.startsWith("tier") }
}
