package dev.thuat.hotreload.cli

// This CLI build's own version, baked into the jar at build time from cli/build.gradle.kts's own
// `version` (see that file's processResources block for how) — never a hand-maintained literal
// that could itself drift from what this build actually is. ReloadConfig.cliVersion defaults to
// this and is what ReloadOrchestrator.checkRuntimeVersion compares against the on-device
// runtime's self-reported version (Protocol.pingRuntimeVersionOf) before ever sending LOAD_DEX —
// see that function's doc for the comparison rule and why a mismatch must not be a silent no-op.
object CliVersion {
    val VERSION: String = readVersion()

    private fun readVersion(): String =
        CliVersion::class.java.getResourceAsStream("/hotreload-cli-version.txt")
            ?.bufferedReader()?.readText()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: error("hotreload-cli-version.txt missing or empty on the classpath — broken CLI build")
}
