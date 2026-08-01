package dev.hotreload.cli

import java.nio.file.Path

class Adb(
    private val adbPath: String,
    private val serial: String?,
    private val runner: ProcessRunner = RealProcessRunner(),
) {
    private fun adb(vararg args: String): ProcessResult {
        val base = buildList {
            add(adbPath)
            serial?.let { add("-s"); add(it) }
            addAll(args)
        }
        return runner.run(base)
    }

    fun push(local: Path, remotePath: String): ProcessResult =
        adb("push", local.toString(), remotePath)

    fun runAsCopy(pkg: String, fromDeviceTmp: String, toRelPath: String): ProcessResult {
        val destDir = toRelPath.substringBeforeLast('/', "")
            .let { if (it.isEmpty()) "code_cache" else "code_cache/$it" }
        return adb(
            "shell", "run-as", pkg, "sh", "-c",
            "mkdir -p $destDir && cp $fromDeviceTmp code_cache/$toRelPath",
        )
    }

    fun attachAgent(pkg: String, agentPathInAppSandbox: String): ProcessResult =
        adb("shell", "am", "attach-agent", pkg, agentPathInAppSandbox)

    fun forward(localPort: Int, abstractSocket: String): ProcessResult =
        adb("forward", "tcp:$localPort", "localabstract:$abstractSocket")

    fun isAppRunning(pkg: String): Boolean {
        val result = adb("shell", "pidof", pkg)
        return result.exitCode == 0 && result.stdout.trim().isNotEmpty()
    }

    fun appDataDir(pkg: String): String = "/data/data/$pkg"
}
