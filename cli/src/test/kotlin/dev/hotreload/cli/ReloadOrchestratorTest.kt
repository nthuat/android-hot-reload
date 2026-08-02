package dev.hotreload.cli

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// See isKeyMetaClass's doc comment in ReloadOrchestrator.kt for the ordering-race this filter
// exists to prevent: pushing a $KeyMeta class for redefinition can spuriously fail with "class
// not loaded" depending on filesystem walk order, even though it never needs redefinition.
class ReloadOrchestratorTest {
    @Test
    fun `flags a facade's KeyMeta sibling`() {
        assertTrue(isKeyMetaClass("dev.hotreload.sample.feature.GreetingKt\$KeyMeta"))
    }

    @Test
    fun `does not flag the facade itself`() {
        assertFalse(isKeyMetaClass("dev.hotreload.sample.feature.GreetingKt"))
    }

    @Test
    fun `does not flag an ordinary nested composable lambda class`() {
        assertFalse(isKeyMetaClass("dev.hotreload.sample.feature.GreetingKt\$Greeting\$1\$2"))
    }

    @Test
    fun `does not flag an unrelated class that merely contains Meta`() {
        assertFalse(isKeyMetaClass("dev.hotreload.sample.MetaData"))
    }
}
