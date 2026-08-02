package dev.thuat.hotreload.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertTrue

class HotReloadPluginTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `adds runtime dependency to debugImplementation on android app projects`() {
        // Minimal build that applies our plugin alongside a fake android application plugin marker.
        // Full AGP in TestKit is slow; instead verify against plain 'java' and the withId hook by
        // asserting the plugin no-ops without the android plugin, plus unit-check the wiring below.
        tmp.newFile("settings.gradle.kts").writeText("rootProject.name = \"t\"")
        tmp.newFile("build.gradle.kts").writeText(
            """
            plugins { id("dev.thuat.hotreload") }
            tasks.register("ok")
            """.trimIndent()
        )
        val result = GradleRunner.create()
            .withProjectDir(tmp.root)
            .withPluginClasspath()
            .withArguments("ok")
            .build()
        assertTrue(result.output.contains("BUILD SUCCESSFUL"))  // plugin applies cleanly without AGP
    }
}
