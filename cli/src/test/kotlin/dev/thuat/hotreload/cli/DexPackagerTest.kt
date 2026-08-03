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

    private fun compileFixture(name: String = "Fixture"): ChangedClass {
        val src = tmp.root.toPath().resolve("$name.java")
        Files.write(src, "public class $name { public int answer() { return 42; } }".toByteArray())
        val rc = ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", tmp.root.absolutePath, src.toString())
        assertEquals(0, rc)
        return ChangedClass(tmp.root.toPath().resolve("$name.class"), name, "L$name;")
    }

    // Stands in for AGP's real mergeProjectDexDebug/mergeLibDexDebug output: a "bucket"
    // directory under app/build/intermediates/dex/debug/<task>/<n>/classes.dex, the shape
    // DexPackager scans for.
    private fun seedMergedDex(projectDir: Path, task: String, bucket: Int, classFiles: List<Path>) {
        val bucketDir = projectDir.resolve("app/build/intermediates/dex/debug/$task/$bucket")
        Files.createDirectories(bucketDir)
        D8.run(
            D8Command.builder()
                .addProgramFiles(classFiles)
                .setMinApiLevel(26)
                .setOutput(bucketDir, OutputMode.DexIndexed)
                .build()
        )
    }

    @Test
    fun `extracts a class from the app's merged project dex output`() {
        val fixture = compileFixture()
        seedMergedDex(tmp.root.toPath(), "mergeProjectDexDebug", 0, listOf(fixture.classFile))
        val out = tmp.root.toPath().resolve("dex")
        val result = DexPackager(tmp.root.toPath()).dexClasses(listOf(fixture), out)
        val dex = result.getValue(fixture)
        assertTrue(Files.exists(dex))
        assertEquals("Fixture.dex", dex.fileName.toString())
        val bytes = Files.readAllBytes(dex)
        assertEquals("dex\n", String(bytes, 0, 4))              // DEX magic
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("LFixture;"))
    }

    @Test
    fun `also finds a class in the app's merged library dex output`() {
        val fixture = compileFixture()
        seedMergedDex(tmp.root.toPath(), "mergeLibDexDebug", 0, listOf(fixture.classFile))
        val out = tmp.root.toPath().resolve("dex")
        val result = DexPackager(tmp.root.toPath()).dexClasses(listOf(fixture), out)
        assertTrue(Files.exists(result.getValue(fixture)))
    }

    @Test
    fun `fails clearly when a class is missing from merged dex output`() {
        val fixture = compileFixture()
        val out = tmp.root.toPath().resolve("dex")
        val ex = assertFailsWith<IllegalStateException> {
            DexPackager(tmp.root.toPath()).dexClasses(listOf(fixture), out)
        }
        assertTrue(ex.message!!.contains("Fixture"))
    }

    // F1 fix: a batch of classes that all live in the SAME bucket must resolve from one D8
    // split of that bucket, not one split per class.
    @Test
    fun `resolves multiple classes from a single bucket split`() {
        val a = compileFixture("Alpha")
        val b = compileFixture("Beta")
        seedMergedDex(tmp.root.toPath(), "mergeProjectDexDebug", 0, listOf(a.classFile, b.classFile))
        val out = tmp.root.toPath().resolve("dex")
        val result = DexPackager(tmp.root.toPath()).dexClasses(listOf(a, b), out)
        assertEquals(setOf(a, b), result.keys)
        assertTrue(Files.exists(result.getValue(a)))
        assertTrue(Files.exists(result.getValue(b)))
        assertTrue(String(Files.readAllBytes(result.getValue(a)), Charsets.ISO_8859_1).contains("LAlpha;"))
        assertTrue(String(Files.readAllBytes(result.getValue(b)), Charsets.ISO_8859_1).contains("LBeta;"))
    }

    // Classes spread across two different buckets must both resolve out of one dexClasses()
    // call, each from its own bucket's split.
    @Test
    fun `resolves classes spread across two buckets`() {
        val a = compileFixture("Gamma")
        val b = compileFixture("Delta")
        seedMergedDex(tmp.root.toPath(), "mergeProjectDexDebug", 0, listOf(a.classFile))
        seedMergedDex(tmp.root.toPath(), "mergeProjectDexDebug", 1, listOf(b.classFile))
        val out = tmp.root.toPath().resolve("dex")
        val result = DexPackager(tmp.root.toPath()).dexClasses(listOf(a, b), out)
        assertEquals(setOf(a, b), result.keys)
        assertTrue(Files.exists(result.getValue(a)))
        assertTrue(Files.exists(result.getValue(b)))
    }

    // A batch where one class exists in a bucket and another doesn't must surface the missing
    // one via the error path — it isn't enough for *some* of the batch to resolve.
    @Test
    fun `reports a class present in no bucket even when others in the batch resolve`() {
        val found = compileFixture("Epsilon")
        val missing = compileFixture("Zeta")
        seedMergedDex(tmp.root.toPath(), "mergeProjectDexDebug", 0, listOf(found.classFile))
        val out = tmp.root.toPath().resolve("dex")
        val ex = assertFailsWith<IllegalStateException> {
            DexPackager(tmp.root.toPath()).dexClasses(listOf(found, missing), out)
        }
        assertTrue(ex.message!!.contains("Zeta"))
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
        val result = DexPackager(tmp.root.toPath()).dexClasses(listOf(classA, classB), out)
        val dexA = result.getValue(classA)
        val dexB = result.getValue(classB)

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
