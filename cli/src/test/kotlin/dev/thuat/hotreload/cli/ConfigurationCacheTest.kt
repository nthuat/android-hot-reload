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
    fun `recognizes an entry that fails to deserialize`() {
        // Verbatim from the CI e2e run that went red on the sample project: Gradle stored the
        // entry, then failed reloading it because Kotlin's build-FUS build service could not be
        // deserialized. Nothing in this output matches the "problems found" shape, so before this
        // case the CLI surfaced a configuration-cache defect as a plain compile error and never
        // retried. See isConfigurationCacheFailure's doc.
        val output = """
            * What went wrong:
            Error while reading task graph
            > Exception while loading configuration for :feature: Could not load the value of field
              `__buildFusService__` of task `:feature:compileDebugKotlin` of type
              `org.jetbrains.kotlin.gradle.tasks.KotlinCompile`.
        """.trimIndent()
        assertTrue(isConfigurationCacheFailure(output))
    }

    @Test
    fun `recognizes a failure storing the task graph`() {
        assertTrue(isConfigurationCacheFailure("Error while saving task graph\n> boom"))
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
