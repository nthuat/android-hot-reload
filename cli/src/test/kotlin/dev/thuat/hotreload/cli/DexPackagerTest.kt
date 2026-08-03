package dev.thuat.hotreload.cli

import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    // Stands in for AGP's real mergeProjectDexDebug/mergeLibDexDebug output: a "bucket"
    // directory under app/build/intermediates/dex/debug/<task>/<n>/classes.dex, the shape
    // DexPackager scans for.
    private fun seedMergedDex(projectDir: Path, task: String, classFile: Path) {
        val bucketDir = projectDir.resolve("app/build/intermediates/dex/debug/$task/0")
        Files.createDirectories(bucketDir)
        D8.run(
            D8Command.builder()
                .addProgramFiles(classFile)
                .setMinApiLevel(26)
                .setOutput(bucketDir, OutputMode.DexIndexed)
                .build()
        )
    }

    @Test
    fun `extracts a class from the app's merged project dex output`() {
        val fixture = compileFixture()
        seedMergedDex(tmp.root.toPath(), "mergeProjectDexDebug", fixture.classFile)
        val out = tmp.root.toPath().resolve("dex")
        val dex = DexPackager(tmp.root.toPath()).dexClass(fixture, out)
        assertTrue(Files.exists(dex))
        assertEquals("Fixture.dex", dex.fileName.toString())
        val bytes = Files.readAllBytes(dex)
        assertEquals("dex\n", String(bytes, 0, 4))              // DEX magic
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("LFixture;"))
    }

    @Test
    fun `also finds a class in the app's merged library dex output`() {
        val fixture = compileFixture()
        seedMergedDex(tmp.root.toPath(), "mergeLibDexDebug", fixture.classFile)
        val out = tmp.root.toPath().resolve("dex")
        val dex = DexPackager(tmp.root.toPath()).dexClass(fixture, out)
        assertTrue(Files.exists(dex))
    }

    @Test
    fun `fails clearly when the class is missing from merged dex output`() {
        val fixture = compileFixture()
        val out = tmp.root.toPath().resolve("dex")
        val ex = assertFailsWith<IllegalStateException> { DexPackager(tmp.root.toPath()).dexClass(fixture, out) }
        assertTrue(ex.message!!.contains("Fixture"))
    }

    private fun compilePackaged(pkg: String, simpleName: String): ChangedClass {
        val pkgDir = tmp.root.toPath().resolve("src").resolve(pkg.replace('.', '/'))
        Files.createDirectories(pkgDir)
        val src = pkgDir.resolve("$simpleName.java")
        Files.write(src, "package $pkg; public class $simpleName { public int id() { return 1; } }".toByteArray())
        val outDir = tmp.root.toPath().resolve("classes")
        Files.createDirectories(outDir)
        val rc = ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", outDir.toString(), src.toString())
        assertEquals(0, rc)
        val binaryName = "$pkg.$simpleName"
        return ChangedClass(
            classFile = outDir.resolve(binaryName.replace('.', '/') + ".class"),
            binaryName = binaryName,
            descriptor = "L${binaryName.replace('.', '/')};",
        )
    }

    // F6: bare "<SimpleName>.dex" filename matching ignored the package, so two classes with
    // the same simple name in different packages collided — whichever the walk visited first
    // was extracted for BOTH, silently pushing the wrong module's bytes for one of them.
    @Test
    fun `distinguishes two classes with the same simple name in different packages`() {
        val classA = compilePackaged("a", "Util")
        val classB = compilePackaged("b", "Util")

        val bucketDir = tmp.root.toPath().resolve("app/build/intermediates/dex/debug/mergeProjectDexDebug/0")
        Files.createDirectories(bucketDir)
        D8.run(
            D8Command.builder()
                .addProgramFiles(classA.classFile, classB.classFile)
                .setMinApiLevel(26)
                .setOutput(bucketDir, OutputMode.DexIndexed)
                .build()
        )

        val out = tmp.root.toPath().resolve("dex")
        val dexA = DexPackager(tmp.root.toPath()).dexClass(classA, out)
        val dexB = DexPackager(tmp.root.toPath()).dexClass(classB, out)

        assertEquals("a_Util.dex", dexA.fileName.toString())
        assertEquals("b_Util.dex", dexB.fileName.toString())
        val bytesA = String(Files.readAllBytes(dexA), Charsets.ISO_8859_1)
        val bytesB = String(Files.readAllBytes(dexB), Charsets.ISO_8859_1)
        assertTrue(bytesA.contains("La/Util;"))
        assertTrue(bytesB.contains("Lb/Util;"))
        // The actual collision this test guards against: extracting a.Util must not silently
        // hand back b.Util's bytes (or vice versa).
        assertTrue(!bytesA.contains("Lb/Util;"))
        assertTrue(!bytesB.contains("La/Util;"))
    }
}
