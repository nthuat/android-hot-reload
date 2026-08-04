package dev.thuat.hotreload.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// This repo's own gradle-tooling-api dependency is 8.11.1 (see gradle/libs.versions.toml) — the
// exact version used to confirm the real-world defect this file guards against: a JDK 26 process
// running GradleCompiler against a Gradle-8.11.1 project fails with a bare "26.0.2" under
// "* What went wrong:" (see JdkPreflight.kt's unsupportedJvmHint doc for the confirmed cause
// chain). These tests exercise the pure version-ceiling logic without touching a real JVM/Gradle
// install.
class JdkPreflightTest {
    @Test
    fun `gradle 8-11-1 (this repo's own tooling-api version) caps at JDK 23`() {
        assertEquals(23, gradleJdkCeiling("8.11.1"))
    }

    @Test
    fun `gradle 8-3 first supports JDK 20`() {
        assertEquals(20, gradleJdkCeiling("8.3"))
        assertEquals(19, gradleJdkCeiling("8.2"))
    }

    @Test
    fun `gradle 8-5 through 8-7 cap at JDK 21`() {
        assertEquals(21, gradleJdkCeiling("8.5.1"))
        assertEquals(21, gradleJdkCeiling("8.7"))
    }

    @Test
    fun `gradle 9-5 (JetNews's version) caps at JDK 26`() {
        assertEquals(26, gradleJdkCeiling("9.5.0"))
    }

    @Test
    fun `gradle 9-0-x regresses to JDK 23, below 8-14's JDK 24`() {
        assertEquals(24, gradleJdkCeiling("8.14"))
        assertEquals(23, gradleJdkCeiling("9.0.0"))
    }

    @Test
    fun `unknown gradle version falls back to the conservative ceiling, not a guess`() {
        assertEquals(CONSERVATIVE_JDK_CEILING, gradleJdkCeiling(null))
        assertEquals(CONSERVATIVE_JDK_CEILING, gradleJdkCeiling("not-a-version"))
    }

    @Test
    fun `JDK above the ceiling for a known gradle version is an error`() {
        val hint = evaluateJdkAgainstGradle(26, "8.11.1")
        assertTrue(hint != null)
        assertTrue(hint!!.contains("JDK 26"))
        assertTrue(hint.contains("Gradle 8.11.1"))
        assertTrue(hint.contains("JDK 23"))
        assertTrue(hint.contains("--java-home"))
    }

    @Test
    fun `JDK at the ceiling for a known gradle version passes`() {
        assertNull(evaluateJdkAgainstGradle(23, "8.11.1"))
    }

    @Test
    fun `JDK below the ceiling for a known gradle version passes`() {
        assertNull(evaluateJdkAgainstGradle(21, "8.11.1"))
    }

    @Test
    fun `unknown gradle version still errors once the JDK exceeds the conservative ceiling`() {
        assertTrue(evaluateJdkAgainstGradle(CONSERVATIVE_JDK_CEILING + 1, null) != null)
    }

    @Test
    fun `unknown gradle version does not false-positive under the conservative ceiling`() {
        assertNull(evaluateJdkAgainstGradle(CONSERVATIVE_JDK_CEILING, null))
    }

    // The exact shape reproduced live: `* What went wrong:` followed by nothing but a bare
    // version number — see unsupportedJvmHint's doc for the confirmed Gradle-internal cause.
    @Test
    fun `bare version under What went wrong is recognized as an unsupported-JVM failure`() {
        val output = """
            FAILURE: Build failed with an exception.

            * What went wrong:
            26.0.2

            * Try:
            > Run with --stacktrace option to get the stack trace.
        """.trimIndent()
        val hint = unsupportedJvmHint(output, "8.11.1")
        assertTrue(hint != null)
        assertTrue(hint!!.contains("JDK 26"))
        assertTrue(hint.contains("Gradle 8.11.1"))
    }

    @Test
    fun `an ordinary compile error is not mistaken for an unsupported-JVM failure`() {
        val output = """
            FAILURE: Build failed with an exception.

            * What went wrong:
            Execution failed for task ':feature:compileDebugKotlin'.
            > Compilation error. See log for more details
        """.trimIndent()
        assertNull(unsupportedJvmHint(output, "8.11.1"))
    }

    @Test
    fun `output with no What went wrong section at all is not mistaken for an unsupported-JVM failure`() {
        assertNull(unsupportedJvmHint("some unrelated stderr noise", "8.11.1"))
    }

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `reads the gradle version out of the consumer project's own wrapper properties`() {
        val projectDir = tmp.newFolder("project").toPath()
        val wrapperDir = projectDir.resolve("gradle/wrapper")
        Files.createDirectories(wrapperDir)
        Files.writeString(
            wrapperDir.resolve("gradle-wrapper.properties"),
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.11.1-bin.zip\n",
        )
        assertEquals("8.11.1", readWrapperGradleVersion(projectDir))
    }

    // Not the same as "unknown" — GradleConnector itself falls back to the bundled Tooling API
    // version when a consumer project has no wrapper file (see BUNDLED_TOOLING_API_GRADLE_VERSION's
    // doc; confirmed empirically against this repo's own wrapper-less sample/ project). Treating
    // this as unknown-conservative instead would false-positive on exactly that setup.
    @Test
    fun `missing wrapper properties file falls back to the bundled tooling-api version, not unknown`() {
        val projectDir = tmp.newFolder("project-no-wrapper").toPath()
        assertEquals(BUNDLED_TOOLING_API_GRADLE_VERSION, readWrapperGradleVersion(projectDir))
    }

    @Test
    fun `a wrapper file with an unparseable version is genuinely unknown`() {
        val projectDir = tmp.newFolder("project-bad-wrapper").toPath()
        val wrapperDir = projectDir.resolve("gradle/wrapper")
        Files.createDirectories(wrapperDir)
        Files.writeString(wrapperDir.resolve("gradle-wrapper.properties"), "distributionUrl=not-a-url\n")
        assertNull(readWrapperGradleVersion(projectDir))
    }

    @Test
    fun `jdkPreflightCheck with an unreadable java-home override does not block`() {
        // No release file at this path — can't determine the override JDK's version, so this
        // must not fabricate a version and false-positive; it should skip the check entirely.
        val fakeJavaHome = tmp.newFolder("not-a-real-jdk").toPath()
        val projectDir = tmp.newFolder("project2").toPath()
        assertNull(jdkPreflightCheck(projectDir, fakeJavaHome))
    }
}
