package dev.thuat.hotreload.cli

import java.util.concurrent.TimeUnit

// Every adb invocation goes through here. A wedged device (offline emulator, dead adb server)
// can leave `adb` itself hanging with zero CPU forever — reproduced live: `adb shell pidof <pkg>`
// blocked indefinitely with no output, no error, no timeout, until kill -9. 30s comfortably
// covers the slowest legitimate call (`adb push` of a dex on a slow device is a few seconds)
// while still bounding the wedged case to something a human isn't left guessing about.
const val DEFAULT_ADB_TIMEOUT_MS = 30_000L

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    // True when the process didn't exit within the timeout and was destroyed forcibly — distinct
    // from a normal non-zero exitCode so callers can surface "device may be offline" rather than
    // a generic "command failed" (exitCode is meaningless in this case; see RealProcessRunner).
    val timedOut: Boolean = false,
)

interface ProcessRunner {
    fun run(args: List<String>, timeoutMs: Long = DEFAULT_ADB_TIMEOUT_MS): ProcessResult
}

class RealProcessRunner : ProcessRunner {
    override fun run(args: List<String>, timeoutMs: Long): ProcessResult {
        val proc = ProcessBuilder(args).start()
        // Drain stdout/stderr on their own threads rather than reading synchronously before
        // waitFor(): a hung process never closes its pipes, so a blocking readText() call would
        // wait forever regardless of any timeout applied afterwards — the exact hang this whole
        // change exists to close. Reading concurrently with waitFor(timeout) lets the timeout
        // actually bound the call.
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread { proc.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } }
            .apply { isDaemon = true; start() }
        val errThread = Thread { proc.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
            .apply { isDaemon = true; start() }

        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return ProcessResult(-1, stdout.toString(), stderr.toString(), timedOut = true)
        }
        outThread.join(1_000)
        errThread.join(1_000)
        return ProcessResult(proc.exitValue(), stdout.toString(), stderr.toString())
    }
}
