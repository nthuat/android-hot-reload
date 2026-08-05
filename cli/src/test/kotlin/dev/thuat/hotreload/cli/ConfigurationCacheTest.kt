package dev.thuat.hotreload.cli

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// See isConfigurationCacheFailure's doc for where these two anchor strings come from (confirmed
// against the real Gradle 8.11.1 configuration-cache plugin jar).
class ConfigurationCacheTest {
    @Test
    fun `recognizes Gradle's configuration cache problems message`() {
        val output = """
            * What went wrong:
            Configuration cache problems found in this build.

            1 problem was found storing the configuration cache.
            - Task `:app:someTask` of type `SomePlugin${'$'}SomeTask`: invocation of 'Task.project' at execution time is unsupported.
        """.trimIndent()
        assertTrue(isConfigurationCacheFailure(output))
    }

    @Test
    fun `recognizes the exception class name alone`() {
        assertTrue(
            isConfigurationCacheFailure(
                "org.gradle.internal.cc.impl.ConfigurationCacheProblemsException: boom"
            )
        )
    }

    @Test
    fun `does not flag an ordinary compile error`() {
        val output = """
            * What went wrong:
            Execution failed for task ':feature:compileDebugKotlin'.
            > Compilation error. See log for more details
        """.trimIndent()
        assertFalse(isConfigurationCacheFailure(output))
    }

    @Test
    fun `does not flag a benign mention of the configuration cache`() {
        // A successful build's own console noise (e.g. "Configuration cache entry reused") must
        // never be mistaken for the failure shape — this is exactly the happy path the feature
        // is supposed to be silent about.
        assertFalse(isConfigurationCacheFailure("Configuration cache entry reused."))
    }

    @Test
    fun `does not flag an empty or unrelated failure`() {
        assertFalse(isConfigurationCacheFailure(""))
        assertFalse(isConfigurationCacheFailure("some unrelated network timeout"))
    }
}
