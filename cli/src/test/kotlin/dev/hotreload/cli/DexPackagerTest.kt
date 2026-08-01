package dev.hotreload.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DexPackagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun compileFixture(): ChangedClass {
        val src = tmp.root.toPath().resolve("Fixture.java")
        Files.write(src, "public class Fixture { public int answer() { return 42; } }".toByteArray())
        val rc = ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", tmp.root.absolutePath, src.toString())
        assertEquals(0, rc)
        return ChangedClass(tmp.root.toPath().resolve("Fixture.class"), "Fixture", "LFixture;")
    }

    @Test
    fun `produces a dex file with DEX magic containing the class`() {
        val out = tmp.root.toPath().resolve("dex")
        val dex = DexPackager().dexClass(compileFixture(), out)
        assertTrue(Files.exists(dex))
        assertEquals("Fixture.dex", dex.fileName.toString())
        val bytes = Files.readAllBytes(dex)
        assertEquals("dex\n", String(bytes, 0, 4))              // DEX magic
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("LFixture;"))
    }
}
