package dev.thuat.hotreload.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

// findApplicationModules is a text scan, not a Gradle evaluation (see its doc for why). These
// cover the two ways a real project declares the application plugin: the literal id, and a
// version-catalog alias -- the latter reproduced against compose-samples/Jetcaster, where every
// module uses `alias(libs.plugins.android.application)` and the literal id appears nowhere but
// gradle/libs.versions.toml, so the scan found no application modules at all and the CLI could
// only tell the user to go run `./gradlew projects` themselves.
class AppModuleHintTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun project(
        settings: String,
        modules: Map<String, String>,
        catalog: String? = null,
    ): Path {
        val root = tmp.root.toPath()
        root.resolve("settings.gradle.kts").writeText(settings)
        catalog?.let {
            root.resolve("gradle").createDirectories()
            root.resolve("gradle/libs.versions.toml").writeText(it)
        }
        modules.forEach { (path, build) ->
            val dir = root.resolve(path.removePrefix(":").replace(':', '/'))
            dir.createDirectories()
            dir.resolve("build.gradle.kts").writeText(build)
        }
        return root
    }

    @Test
    fun `finds a module applying the literal plugin id`() {
        val root = project(
            settings = """include(":app", ":core")""",
            modules = mapOf(
                ":app" to """plugins { id("com.android.application") }""",
                ":core" to """plugins { id("com.android.library") }""",
            ),
        )
        assertEquals(listOf(":app"), findApplicationModules(root))
    }

    @Test
    fun `finds a module applying the plugin through a version catalog alias`() {
        // The Jetcaster shape.
        val root = project(
            settings = """include(":mobile", ":core:data")""",
            modules = mapOf(
                ":mobile" to """
                    plugins {
                        alias(libs.plugins.android.application)
                        alias(libs.plugins.ksp)
                    }
                """.trimIndent(),
                ":core:data" to """plugins { alias(libs.plugins.android.library) }""",
            ),
            catalog = """
                [plugins]
                android-application = { id = "com.android.application", version.ref = "agp" }
                android-library = { id = "com.android.library", version.ref = "agp" }
                ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
            """.trimIndent(),
        )
        assertEquals(listOf(":mobile"), findApplicationModules(root))
    }

    @Test
    fun `does not treat a library alias as an application module`() {
        val root = project(
            settings = """include(":core")""",
            modules = mapOf(":core" to """plugins { alias(libs.plugins.android.library) }"""),
            catalog = """
                [plugins]
                android-application = { id = "com.android.application", version.ref = "agp" }
                android-library = { id = "com.android.library", version.ref = "agp" }
            """.trimIndent(),
        )
        assertEquals(emptyList(), findApplicationModules(root))
    }

    @Test
    fun `suggests the catalog-alias module in the hint message`() {
        val root = project(
            settings = """include(":mobile")""",
            modules = mapOf(":mobile" to """plugins { alias(libs.plugins.android.application) }"""),
            catalog = """
                [plugins]
                android-application = { id = "com.android.application", version.ref = "agp" }
            """.trimIndent(),
        )
        val hint = appModuleNotFoundHint(":app", root)
        assertEquals(
            "no ':app' module in this project. Application modules found: :mobile. " +
                "Pass --app-module :mobile",
            hint,
        )
    }

    @Test
    fun `falls back cleanly when there is no catalog`() {
        val root = project(
            settings = """include(":mobile")""",
            modules = mapOf(":mobile" to """plugins { alias(libs.plugins.android.application) }"""),
        )
        assertEquals(emptyList(), findApplicationModules(root))
    }

    @Test
    fun `returns nothing when settings are missing`() {
        assertEquals(emptyList(), findApplicationModules(tmp.root.toPath()))
        Files.exists(tmp.root.toPath())
    }
}
