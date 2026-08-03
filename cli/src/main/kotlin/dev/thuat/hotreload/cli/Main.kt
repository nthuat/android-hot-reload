package dev.thuat.hotreload.cli

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import kotlin.io.path.relativeTo
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val cmd = args[0]
    val opts = args.drop(1).chunked(2).mapNotNull { pair ->
        if (pair.size == 2 && pair[0].startsWith("--")) pair[0].removePrefix("--") to pair[1] else null
    }.toMap()

    val projectDir = Paths.get(opts["project"] ?: fail("--project required")).toAbsolutePath().normalize()
    val pkg = opts["package"] ?: fail("--package required")
    val config = ReloadConfig(
        projectDir = projectDir,
        pkg = pkg,
        serial = opts["serial"],
        adbPath = opts["adb"] ?: defaultAdb(),
        agentSoDir = Paths.get(
            opts["agent-so-dir"]
                ?: Paths.get("").toAbsolutePath()
                    .resolve("agent/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib").toString()
        ),
        appModule = opts["app-module"] ?: ":app",
    )
    val orchestrator = ReloadOrchestrator(config)

    when (cmd) {
        "bootstrap" -> exitWith(orchestrator.bootstrap())
        "cycle" -> {
            val file = Paths.get(opts["file"] ?: fail("--file required"))
            exitWith(orchestrator.cycle(file))
        }
        "run" -> {
            val boot = orchestrator.bootstrap()
            if (boot !is CycleOutcome.Reloaded) exitWith(boot)
            println("hotreload ready — watching ${config.projectDir}")
            watchLoop(config.projectDir, orchestrator)
        }
        else -> usage()
    }
}

// Segment-based, not substring: the old `path.contains("src") && !path.contains("build")` check
// matched anywhere in the *absolute* path. A project checked out under a path containing "build"
// (e.g. ~/repos/build-tools/myapp) registered zero watchers — `run` then hangs forever with no
// indication why — and a path containing "src" (e.g. ~/src/myapp) happily registered .git/.idea
// as "src" dirs too. Pure so it's directly unit testable without a real filesystem/WatchService.
internal fun isWatchableDir(dir: Path, projectDir: Path): Boolean {
    if (dir == projectDir) return false
    val segments = dir.relativeTo(projectDir).map { it.toString() }
    return segments.none { it.startsWith(".") } &&   // skip .git, .gradle, .idea, .hotreload, ...
        segments.none { it == "build" } &&
        segments.any { it == "src" }
}

private fun watchLoop(projectDir: Path, orchestrator: ReloadOrchestrator): Nothing {
    val watcher = FileSystems.getDefault().newWatchService()
    Files.walk(projectDir).use { stream ->
        stream.filter { Files.isDirectory(it) && isWatchableDir(it, projectDir) }
            .forEach { it.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE) }
    }
    while (true) {
        val key = watcher.take()
        val dir = key.watchable() as Path
        val changedKt = key.pollEvents().mapNotNull { (it.context() as? Path)?.let(dir::resolve) }
            .filter { it.toString().endsWith(".kt") }
        key.reset()
        if (changedKt.isEmpty()) continue
        Thread.sleep(100)  // debounce editor write bursts
        report(orchestrator.cycle(changedKt.first()))
    }
}

// Short state-guarantee text per tier, for the CLI's post-reload line — see
// ComposeInvalidator.reload's three-tier fallback chain and the README's "Reload tiers" table.
private val tierGuarantee = mapOf(
    "tier1" to "remember state preserved",
    "tier2" to "UI state reset",
    "tier3" to "activity recreated",
    "tier-timeout" to "reload confirmation timed out",
)

private fun report(outcome: CycleOutcome) = when (outcome) {
    is CycleOutcome.Reloaded -> {
        val tierSuffix = outcome.tier?.let { " [$it — ${tierGuarantee[it] ?: "unknown"}]" } ?: ""
        println("✓ reloaded ${outcome.classes.size} class(es) in ${outcome.millis}ms$tierSuffix: ${outcome.classes.joinToString()}")
    }
    is CycleOutcome.NoChanges -> println("· no bytecode changes")
    is CycleOutcome.CompileError -> println("✗ compile error:\n${outcome.output}")
    is CycleOutcome.Incompatible -> println("✗ incompatible change: ${outcome.reason}\n  → run a full rebuild + reinstall, then 'hotreload bootstrap' again")
    is CycleOutcome.DeviceError -> println("✗ device/agent: ${outcome.reason}")
}

private fun exitWith(outcome: CycleOutcome): Nothing {
    report(outcome)
    exitProcess(
        when (outcome) {
            is CycleOutcome.Reloaded, CycleOutcome.NoChanges -> 0
            is CycleOutcome.CompileError -> 1
            is CycleOutcome.Incompatible -> 2
            is CycleOutcome.DeviceError -> 3
        }
    )
}

private fun defaultAdb(): String {
    val home = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: fail("set ANDROID_HOME or pass --adb")
    return "$home/platform-tools/adb"
}

private fun usage(): Nothing {
    println("usage: hotreload <bootstrap|cycle|run> --project <dir> --package <pkg> [--serial S] [--file f.kt] [--adb path] [--agent-so-dir dir] [--app-module :app]")
    exitProcess(64)
}

private fun fail(msg: String): Nothing {
    System.err.println("error: $msg")
    exitProcess(64)
}
