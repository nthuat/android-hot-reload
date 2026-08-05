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
// The second shape is an entry that fails to serialize or deserialize, which produces no
// "problems" report at all. Observed for real: a CI e2e run went red because Gradle stored the
// entry and then failed reloading it to execute the build --
//
//   Error while reading task graph
//   > Exception while loading configuration for :feature: Could not load the value of field
//     `__buildFusService__` of task `:feature:compileDebugKotlin` of type `KotlinCompile`.
//
// a known Kotlin-plugin/configuration-cache interaction. The "reading task graph" / "saving task
// graph" halves of that message live in gradle-core-serialization-codecs-8.11.1.jar (Gradle
// renders them as "Error while " + the phrase), i.e. they come from the configuration cache's own
// serialization layer and cannot appear in a Kotlin or Java compile error -- which is what makes
// them safe to match on without re-flagging ordinary build failures.
internal fun isConfigurationCacheFailure(output: String): Boolean =
    output.contains("Configuration cache problems found in this build") ||
        output.contains("ConfigurationCacheProblemsException") ||
        output.contains("reading task graph") ||
        output.contains("saving task graph")
