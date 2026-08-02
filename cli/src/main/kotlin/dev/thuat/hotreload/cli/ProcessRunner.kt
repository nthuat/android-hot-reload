package dev.thuat.hotreload.cli

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

interface ProcessRunner {
    fun run(args: List<String>): ProcessResult
}

class RealProcessRunner : ProcessRunner {
    override fun run(args: List<String>): ProcessResult {
        val proc = ProcessBuilder(args).start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        return ProcessResult(code, stdout, stderr)
    }
}
