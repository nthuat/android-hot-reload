package dev.thuat.hotreload.cli

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
        val destPath = "code_cache/$toRelPath"
        // Quote each path individually: the compound command below is itself parsed
        // as a script by an *inner* `sh -c` (see comment below), so any shell-special
        // character inside a path — notably `$`, which appears in every Kotlin inner
        // class name like "GreetingKt$KeyMeta.dex" — would otherwise be expanded as a
        // variable reference (to empty string) by that inner shell instead of being
        // treated as literal filename data.
        val remoteCmd = "mkdir -p ${shQuote(destDir)} && cp ${shQuote(fromDeviceTmp)} ${shQuote(destPath)}"
        // `adb shell` joins its trailing argv with spaces before handing it to the
        // remote `sh -c`, so an unquoted multi-word "sh -c <cmd>" loses its own
        // quoting boundary and the remote shell sees "-p"/"&&"/etc as separate
        // words instead of part of one script. Single-quote the compound command
        // (escaping embedded quotes) so it survives that join as one token.
        return adb("shell", "run-as", pkg, "sh", "-c", shQuote(remoteCmd))
    }

    private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun attachAgent(pkg: String, agentPathInAppSandbox: String): ProcessResult =
        adb("shell", "am", "attach-agent", pkg, agentPathInAppSandbox)

    fun forward(localPort: Int, abstractSocket: String): ProcessResult =
        adb("forward", "tcp:$localPort", "localabstract:$abstractSocket")

    fun isAppRunning(pkg: String): Boolean {
        val result = adb("shell", "pidof", pkg)
        return result.exitCode == 0 && result.stdout.trim().isNotEmpty()
    }

    fun appDataDir(pkg: String): String = "/data/data/$pkg"

    fun getprop(name: String): String = adb("shell", "getprop", name).stdout.trim()
}
