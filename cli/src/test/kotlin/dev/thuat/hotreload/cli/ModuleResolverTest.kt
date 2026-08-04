package dev.thuat.hotreload.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `discovers all modules`() {
        val root = project()
        assertEquals(listOf(":app", ":feature"), ModuleResolver(root).allModules().sorted())
    }

    @Test
    fun `classDirsOf finds AGP 8 + Kotlin-Gradle-Plugin layout`() {
        val root = project()
        val dir = root.resolve("feature/build/tmp/kotlin-classes/debug")
        Files.createDirectories(dir)
        assertEquals(listOf(dir), ModuleResolver(root).classDirsOf(":feature"))
    }

    @Test
    fun `classDirsOf finds AGP 9 built-in-Kotlin layout`() {
        val root = project()
        val dir = root.resolve("feature/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
        Files.createDirectories(dir)
        assertEquals(listOf(dir), ModuleResolver(root).classDirsOf(":feature"))
    }

    @Test
    fun `classDirsOf finds javac layout`() {
        val root = project()
        val dir = root.resolve("feature/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes")
        Files.createDirectories(dir)
        assertEquals(listOf(dir), ModuleResolver(root).classDirsOf(":feature"))
    }

    @Test
    fun `classDirsOf returns every existing candidate when more than one layout is present`() {
        val root = project()
        val kgp = root.resolve("feature/build/tmp/kotlin-classes/debug")
        val agp9 = root.resolve("feature/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
        Files.createDirectories(kgp)
        Files.createDirectories(agp9)
        assertEquals(setOf(kgp, agp9), ModuleResolver(root).classDirsOf(":feature").toSet())
    }

    @Test
    fun `classDirsOf returns empty for a module with no class output rather than failing`() {
        // com.android.test modules (baseline-profile, benchmark) and resource-only libraries
        // legitimately produce no classes; failing here killed the whole run on real projects.
        val root = project()
        assertTrue(ModuleResolver(root).classDirsOf(":feature").isEmpty())
    }

    @Test
    fun `classDirCandidatesFor names every layout probed, for aggregate error reporting`() {
        val root = project()
        val candidates = ModuleResolver(root).classDirCandidatesFor(":feature").map { it.toString() }
        assertTrue(candidates.any { it.contains("build/tmp/kotlin-classes/debug") })
        assertTrue(candidates.any { it.contains("built_in_kotlinc") })
        assertTrue(candidates.any { it.contains("javac") })
        assertTrue(candidates.all { it.contains("feature") })
    }
}
