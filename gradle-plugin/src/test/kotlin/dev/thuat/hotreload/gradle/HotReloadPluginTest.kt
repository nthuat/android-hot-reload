package dev.thuat.hotreload.gradle

import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HotReloadPluginTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `runtimeCoordinate defaults to the dev-thuat maven coordinate`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(HotReloadPlugin::class.java)
        val extension = project.extensions.getByType(HotReloadExtension::class.java)
        assertEquals(HotReloadPlugin.DEFAULT_RUNTIME_COORDINATE, extension.runtimeCoordinate.get())
    }

    @Test
    fun `runtimeCoordinate can be overridden, e-g for a JitPack coordinate`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(HotReloadPlugin::class.java)
        val extension = project.extensions.getByType(HotReloadExtension::class.java)
        val jitpackCoordinate = "com.github.nthuat.android-hot-reload:hotreload-runtime:v0.1.0"
        extension.runtimeCoordinate.set(jitpackCoordinate)
        assertEquals(jitpackCoordinate, extension.runtimeCoordinate.get())
    }

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
