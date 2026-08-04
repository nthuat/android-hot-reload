package dev.thuat.hotreload.cli

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

    // --java-home evidence: a bogus path forwarded all the way to
    // BuildLauncher.setJavaHome causes the Tooling API connector to fail trying to use it — if
    // GradleCompiler's javaHome parameter were silently dropped instead of reaching the
    // connector, this would succeed exactly like the no-arg test above.
    @Test
    fun `java-home is forwarded to the tooling API connector`() {
        assumeTrue(Files.exists(sample.resolve("settings.gradle.kts")))
        val bogusJavaHome = Paths.get("/not/a/real/jdk-install")
        val result = GradleCompiler(sample, javaHome = bogusJavaHome).compile()
        assertTrue(!result.success, result.output)
    }

    // A valid --java-home still builds successfully — proves the parameter isn't just forwarded,
    // it's forwarded correctly (a real JDK still works, not just "any path breaks the build").
    @Test
    fun `a valid java-home still builds successfully`() {
        assumeTrue(Files.exists(sample.resolve("settings.gradle.kts")))
        val javaHome = Paths.get(System.getProperty("java.home"))
        val result = GradleCompiler(sample, javaHome = javaHome).compile()
        assertTrue(result.success, result.output)
    }

    @Test
    fun `broken source in a dependency module surfaces as a compile failure`() {
        assumeTrue(Files.exists(sample.resolve("settings.gradle.kts")))
        // Greeting.kt lives in :feature, not :app — compile() has no per-module target, it
        // always runs :app's merge-dex tasks, which pull in :feature's compileDebugKotlin
        // transitively. This proves that transitive path actually surfaces the failure,
        // not just that a module name was accepted.
        val src = sample.resolve("feature/src/main/kotlin/dev/thuat/hotreload/sample/feature/Greeting.kt")
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
