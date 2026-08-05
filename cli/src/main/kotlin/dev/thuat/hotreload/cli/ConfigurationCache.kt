package dev.thuat.hotreload.cli

// Detects Gradle's own "configuration cache problems" failure shape in a build's captured
// output/exception text, so GradleCompiler.compile can retry once without --configuration-cache
// (see its doc) instead of guessing at which failures are cache-related.
//
// Anchored on Gradle's own exception message text, not a loose "configuration cache" keyword
// match that could also fire on an unrelated log line that merely mentions the feature by name
// (e.g. a warning about a *reused* entry). Confirmed against
// org.gradle.internal.cc.impl.ConfigurationCacheProblemsException's constructor in
// gradle-configuration-cache-8.11.1.jar: its message is the literal string
// "Configuration cache problems found in this build." — this is what ends up under Gradle's own
// "* What went wrong:" console header, which GradleConnector's captured stdout/stderr preserves
// verbatim. The class name itself is checked too, in case only a bare exception message (no
// console formatting) ever reaches CompileResult.output. A failure that matches neither is always
// a normal build failure and must be surfaced unchanged, never silently retried — see the
// feature's spec: "if you cannot confidently classify a failure... treat it as a normal build
// failure."
internal fun isConfigurationCacheFailure(output: String): Boolean =
    output.contains("Configuration cache problems found in this build") ||
        output.contains("ConfigurationCacheProblemsException")
