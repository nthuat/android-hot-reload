package dev.thuat.hotreload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

// The Tooling API's GradleConnector uses the CLI's own JVM to run the consumer project's build
// daemon by default (see GradleCompiler) — no separate JDK selection happens unless --java-home
// is passed. Every JDK a Gradle release doesn't yet recognize makes `JavaVersion.toVersion(...)`
// (internal to Gradle, see unsupportedJvmHint's doc) throw `IllegalArgumentException(rawVersion)`,
// which surfaces at the console as a bare version number under "* What went wrong:" — no mention
// of Java, Gradle, or what to do about it. This file exists to catch that before it happens
// (jdkPreflightCheck, run once at CLI startup) and to recognize it after the fact if it still
// slips through (unsupportedJvmHint, e.g. a project-pinned org.gradle.java.home or toolchain that
// resolves to an unsupported JDK the CLI's own JVM version wouldn't have predicted).

// ---- Gradle version -> max supported daemon JDK ceiling ------------------------------------
//
// Sourced from Gradle's own compatibility matrix (docs.gradle.org/current/userguide/
// compatibility.html, "Java" section, checked 2026-08-04). Bucketed by the Gradle minor version
// that FIRST added support for a given JDK feature release:
//   8.3 -> JDK 20      8.5  -> JDK 21      8.8  -> JDK 22      8.10  -> JDK 23
//   8.14 -> JDK 24      9.0.x -> JDK 23 (a real regression vs 8.14 — 9.0 shipped before Gradle's
//   own JDK support caught back up)      9.1 -> JDK 25      9.4 -> JDK 26
// Below 8.3, or any Gradle version we can't identify at all, gets CONSERVATIVE_JDK_CEILING
// instead of a guess (see its own doc).
internal fun gradleJdkCeiling(gradleVersion: String?): Int {
    val (major, minor) = gradleVersion?.let(::parseMajorMinor) ?: return CONSERVATIVE_JDK_CEILING
    val score = major * 100 + minor
    return when {
        score < 803 -> CONSERVATIVE_JDK_CEILING
        score < 805 -> 20
        score < 808 -> 21
        score < 810 -> 22
        score < 814 -> 23
        score < 900 -> 24
        score < 901 -> 23
        score < 904 -> 25
        else -> 26
    }
}

// The oldest boundary in the table above (just below the 8.3 bump to JDK 20). Used when a
// project's Gradle version is genuinely unknown (gradleJdkCeiling(null), or a wrapper file whose
// version couldn't be parsed — see readWrapperGradleVersion) and for any Gradle version older
// than our table starts — deliberately the floor of what we know about rather than an optimistic
// guess, per the "fall back to a conservative ceiling rather than guessing high" requirement.
// NOT used for "no wrapper file at all" — that's a known version, see BUNDLED_TOOLING_API_GRADLE_VERSION.
internal const val CONSERVATIVE_JDK_CEILING = 19

internal fun parseMajorMinor(version: String): Pair<Int, Int>? {
    val match = Regex("""^(\d+)\.(\d+)""").find(version) ?: return null
    return match.groupValues[1].toInt() to match.groupValues[2].toInt()
}

// Must track gradle/libs.versions.toml's `toolingApi` entry: when a consumer project has no
// wrapper file at all, GradleConnector doesn't guess or refuse — it falls back to running the
// exact Gradle version the Tooling API library itself was built for (confirmed both from Gradle's
// own docs and empirically: pointing GradleCompiler at a wrapper-less project downloaded and ran
// precisely gradle-8.11.1-bin.zip). So "no wrapper file" is NOT the same as "unknown version" —
// it's a specific, known version, and treating it as unknown here would wrongly reject JDKs this
// exact setup already handles fine (e.g. this repo's own sample/, which has no wrapper of its own
// and relies on this fallback).
internal const val BUNDLED_TOOLING_API_GRADLE_VERSION = "8.11.1"

// Reads the CONSUMER project's own gradle/wrapper/gradle-wrapper.properties (never this tool's
// own) to learn which Gradle version their build actually runs — the version whose JDK ceiling
// governs (see gradleJdkCeiling). Falls back to BUNDLED_TOOLING_API_GRADLE_VERSION when no
// wrapper file exists at all (see its doc). Null only when a wrapper file exists but its version
// couldn't be parsed out of it — a genuinely unknown case, since Gradle would then try to use
// whatever unparseable version it names rather than the bundled default.
internal fun readWrapperGradleVersion(projectDir: Path): String? {
    val propsFile = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties")
    if (!Files.exists(propsFile)) return BUNDLED_TOOLING_API_GRADLE_VERSION
    val props = Properties()
    Files.newInputStream(propsFile).use { props.load(it) }
    val url = props.getProperty("distributionUrl") ?: return null
    return Regex("""gradle-(\d+\.\d+(?:\.\d+)?)-(?:bin|all)""").find(url)?.groupValues?.get(1)
}

// Feature version (e.g. 21, matching Runtime.Version.feature()) of an arbitrary JDK installation,
// read from its `release` file — present on every JDK 9+ distribution (JEP 223), so no subprocess
// spawn needed. Null if unreadable/unparseable.
internal fun jdkFeatureVersionAt(javaHome: Path): Int? {
    val release = javaHome.resolve("release")
    if (!Files.exists(release)) return null
    val versionLine = Files.readAllLines(release).firstOrNull { it.startsWith("JAVA_VERSION=") } ?: return null
    val versionString = versionLine.substringAfter("=").trim('"')
    return runCatching { Runtime.Version.parse(versionString).feature() }.getOrNull()
}

// The JDK feature version that will actually run the build daemon: --java-home's JDK if given
// (read from disk, not assumed), otherwise the CLI process's own JVM. Null only when --java-home
// was given but its `release` file couldn't be read/parsed — deliberately doesn't fall back to
// the CLI's own JVM version in that case, since that's not the JDK that would actually be used;
// better to skip the preflight check (and let a real Gradle failure speak for itself, if any)
// than to check the wrong JDK and either false-positive or mask a real problem.
internal fun detectJdkFeature(javaHomeOverride: Path?): Int? =
    if (javaHomeOverride != null) jdkFeatureVersionAt(javaHomeOverride) else Runtime.version().feature()

// Pure core: does this JDK/Gradle-version pairing work? Returns an actionable hint if not, null
// if it's fine (including when the Gradle version is unknown and the JDK is still within the
// conservative ceiling). Kept separate from I/O (detectJdkFeature, readWrapperGradleVersion) so
// the version-ceiling logic itself is directly unit testable.
internal fun evaluateJdkAgainstGradle(jdkFeature: Int, gradleVersion: String?): String? {
    val ceiling = gradleJdkCeiling(gradleVersion)
    return if (jdkFeature <= ceiling) null else buildJdkHint(jdkFeature, gradleVersion, ceiling)
}

internal fun buildJdkHint(jdkFeature: Int, gradleVersion: String?, ceiling: Int): String =
    "JDK $jdkFeature is too new for " +
        (gradleVersion?.let { "Gradle $it" } ?: "this project's Gradle version (couldn't be determined)") +
        ", which supports up to JDK $ceiling for the build daemon.\n" +
        "  → macOS: export JAVA_HOME=\$(/usr/libexec/java_home -v 21)\n" +
        "  → or: pass --java-home <path to a JDK $ceiling or older>"

// Run once at CLI startup, before any device or compile work (bootstrap/cycle/run all funnel
// through Main.kt's arg parsing first) — this is the single most likely first-run failure for a
// new user, since most default JDKs are 22+ now, and it's cheap enough to always check.
internal fun jdkPreflightCheck(projectDir: Path, javaHomeOverride: Path?): CycleOutcome.EnvironmentError? {
    val feature = detectJdkFeature(javaHomeOverride) ?: return null
    val hint = evaluateJdkAgainstGradle(feature, readWrapperGradleVersion(projectDir)) ?: return null
    return CycleOutcome.EnvironmentError(hint)
}

// Catches the case preflight can't: the CLI's own JVM (or --java-home) passed the ceiling check,
// but the project itself repoints the daemon to a different, unsupported JDK (org.gradle.java.home
// in gradle.properties, a toolchain requirement, etc.) — the actual JDK only becomes visible once
// Gradle has already tried and failed to start with it.
//
// The shape: Gradle's own `org.gradle.api.JavaVersion.toVersion(String)` throws
// `IllegalArgumentException(rawVersionString)` — just the bare version, no other text — when it
// doesn't recognize a JDK feature release as new as the one it was given. Confirmed against this
// tool's own gradle-tooling-api 8.11.1 dependency with a real JDK 26: the cause chain ends in
// `java.lang.IllegalArgumentException: 26.0.2`, and that bare "26.0.2" is exactly what the
// console prints under "* What went wrong:" — the defect this whole file exists to fix. Returns
// the same actionable hint if this shape is detected, null otherwise; never modifies the raw
// output itself, only appended by the caller.
internal fun unsupportedJvmHint(output: String, gradleVersion: String?): String? {
    val whatWentWrong = Regex("""\* What went wrong:\s*\n\s*(.+)""").find(output)?.groupValues?.get(1)?.trim()
        ?: return null
    if (!Regex("""^\d+(\.\d+){1,2}$""").matches(whatWentWrong)) return null
    val feature = runCatching { Runtime.Version.parse(whatWentWrong).feature() }.getOrNull() ?: return null
    return buildJdkHint(feature, gradleVersion, gradleJdkCeiling(gradleVersion))
}
