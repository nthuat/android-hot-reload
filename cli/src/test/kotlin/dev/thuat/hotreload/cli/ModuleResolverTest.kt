package dev.thuat.hotreload.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModuleResolverTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun project(): java.nio.file.Path {
        val root = tmp.root.toPath()
        Files.createFile(root.resolve("settings.gradle.kts"))
        for (m in listOf("app", "feature")) {
            Files.createDirectories(root.resolve("$m/src/main/kotlin"))
            Files.createFile(root.resolve("$m/build.gradle.kts"))
        }
        return root
    }

    @Test
    fun `maps source file to its module gradle path`() {
        val root = project()
        val src = root.resolve("feature/src/main/kotlin/Foo.kt")
        assertEquals(":feature", ModuleResolver(root).moduleOf(src))
    }

    @Test
    fun `file outside any module resolves to null`() {
        val root = project()
        assertNull(ModuleResolver(root).moduleOf(root.resolve("README.md")))
    }

    @Test
    fun `discovers all modules and their class dirs`() {
        val root = project()
        val resolver = ModuleResolver(root)
        assertEquals(listOf(":app", ":feature"), resolver.allModules().sorted())
        assertEquals(
            listOf(root.resolve("feature/build/tmp/kotlin-classes/debug")),
            resolver.classDirsOf(":feature"),
        )
    }
}
