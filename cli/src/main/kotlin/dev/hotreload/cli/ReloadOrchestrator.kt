package dev.hotreload.cli

import java.nio.file.Path

class ReloadConfig(
    val projectDir: Path,
    val pkg: String,
    val serial: String?,
    val adbPath: String,
    val agentSoDir: Path,   // dir containing <abi>/libhotreload_agent.so
    val localPort: Int = 46837,
)

sealed class CycleOutcome {
    data class Reloaded(val classes: List<String>, val millis: Long) : CycleOutcome()
    data class CompileError(val output: String) : CycleOutcome()
    data class Incompatible(val reason: String) : CycleOutcome()
    data class DeviceError(val reason: String) : CycleOutcome()
    object NoChanges : CycleOutcome()
}

class ReloadOrchestrator(private val config: ReloadConfig) {
    private val adb = Adb(config.adbPath, config.serial)
    private val resolver = ModuleResolver(config.projectDir)
    private val differ = ClassDiffer()
    private val store = BaselineStore(config.projectDir.resolve(".hotreload/baseline.txt"))
    private val compiler = GradleCompiler(config.projectDir)
    private val dexer = DexPackager(config.projectDir)

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

        val dexDir = config.projectDir.resolve(".hotreload/dex")
        for (changed in diff.changed) {
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
        }
        store.save(current)
        return CycleOutcome.Reloaded(diff.changed.map { it.binaryName }, System.currentTimeMillis() - start)
    }
}
