package dev.hotreload.cli

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertTrue

class GradleCompilerIntegrationTest {
    private val sample = Paths.get(System.getProperty("hotreload.sampleDir", "../sample")).toAbsolutePath().normalize()

    @Test
    fun `compiles feature module and produces kotlin-classes output`() {
        assumeTrue(Files.exists(sample.resolve("settings.gradle.kts")))
        val result = GradleCompiler(sample).compile(":feature")
        assertTrue(result.success, result.output)
        assertTrue(Files.exists(sample.resolve("feature/build/tmp/kotlin-classes/debug")))
    }

    @Test
    fun `broken source yields failure with compiler output`() {
        assumeTrue(Files.exists(sample.resolve("settings.gradle.kts")))
        val src = sample.resolve("feature/src/main/kotlin/dev/hotreload/sample/feature/Greeting.kt")
        val original = Files.readAllBytes(src)
        try {
            Files.write(src, (String(original) + "\nval broken: =").toByteArray())
            val result = GradleCompiler(sample).compile(":feature")
            assertTrue(!result.success)
            assertTrue(result.output.contains("Greeting.kt"), result.output)
        } finally {
            Files.write(src, original)
        }
    }
}
