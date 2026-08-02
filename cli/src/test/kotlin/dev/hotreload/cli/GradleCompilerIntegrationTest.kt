package dev.hotreload.cli

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertTrue

class GradleCompilerIntegrationTest {
    private val sample = Paths.get(System.getProperty("hotreload.sampleDir", "../sample")).toAbsolutePath().normalize()

    @Test
    fun `compile builds the app module's merged dex output`() {
        assumeTrue(Files.exists(sample.resolve("settings.gradle.kts")))
        val result = GradleCompiler(sample).compile()
        assertTrue(result.success, result.output)
        // DexPackager extracts changed classes from exactly these directories (see its
        // header comment); this is the output compile() exists to keep fresh.
        assertTrue(Files.exists(sample.resolve("app/build/intermediates/dex/debug/mergeProjectDexDebug")))
        assertTrue(Files.exists(sample.resolve("app/build/intermediates/dex/debug/mergeLibDexDebug")))
    }

    @Test
    fun `broken source in a dependency module surfaces as a compile failure`() {
        assumeTrue(Files.exists(sample.resolve("settings.gradle.kts")))
        // Greeting.kt lives in :feature, not :app — compile() has no per-module target, it
        // always runs :app's merge-dex tasks, which pull in :feature's compileDebugKotlin
        // transitively. This proves that transitive path actually surfaces the failure,
        // not just that a module name was accepted.
        val src = sample.resolve("feature/src/main/kotlin/dev/hotreload/sample/feature/Greeting.kt")
        val original = Files.readAllBytes(src)
        try {
            Files.write(src, (String(original) + "\nval broken: =").toByteArray())
            val result = GradleCompiler(sample).compile()
            assertTrue(!result.success)
            assertTrue(result.output.contains("Greeting.kt"), result.output)
        } finally {
            Files.write(src, original)
        }
    }
}
