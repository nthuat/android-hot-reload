package dev.thuat.hotreload.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassDifferTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun writeClass(dirName: String, relPath: String, content: String): java.nio.file.Path {
        val f = tmp.root.toPath().resolve(dirName).resolve(relPath)
        java.nio.file.Files.createDirectories(f.parent)
        java.nio.file.Files.write(f, content.toByteArray())
        return f
    }

    @Test
    fun `unchanged tree diffs to empty`() {
        writeClass("out", "com/foo/Bar.class", "AAAA")
        val differ = ClassDiffer()
        val dirs = listOf(tmp.root.toPath().resolve("out"))
        val base = differ.snapshot(dirs)
        val result = differ.diff(base, differ.snapshot(dirs))
        assertTrue(result.changed.isEmpty() && result.added.isEmpty() && result.removed.isEmpty())
    }

    @Test
    fun `modified class shows up with binary name and descriptor`() {
        writeClass("out", "com/foo/Bar.class", "AAAA")
        val differ = ClassDiffer()
        val dirs = listOf(tmp.root.toPath().resolve("out"))
        val base = differ.snapshot(dirs)
        writeClass("out", "com/foo/Bar.class", "BBBB")
        val result = differ.diff(base, differ.snapshot(dirs))
        assertEquals(1, result.changed.size)
        assertEquals("com.foo.Bar", result.changed[0].binaryName)
        assertEquals("Lcom/foo/Bar;", result.changed[0].descriptor)
    }

    @Test
    fun `new class file is reported as added not changed`() {
        writeClass("out", "com/foo/Bar.class", "AAAA")
        val differ = ClassDiffer()
        val dirs = listOf(tmp.root.toPath().resolve("out"))
        val base = differ.snapshot(dirs)
        writeClass("out", "com/foo/New.class", "CCCC")
        val result = differ.diff(base, differ.snapshot(dirs))
        assertTrue(result.changed.isEmpty())
        assertEquals(listOf(tmp.root.toPath().resolve("out/com/foo/New.class").toString()), result.added)
    }

    // F2: two modules can legitimately share a relative path. The old bare-relPath key
    // collapsed both copies into one map entry on snapshot (last-wins) and, on diff, resolved
    // ChangedClass.classFile by "whichever classDir has this file" (first-wins) — either
    // masking one module's edit or extracting/redefining the wrong module's bytes.
    @Test
    fun `same relative path in two module dirs is tracked independently, not collapsed`() {
        writeClass("moduleA/out", "com/x/Util.class", "AAAA")
        writeClass("moduleB/out", "com/x/Util.class", "ZZZZ")
        val differ = ClassDiffer()
        val dirA = tmp.root.toPath().resolve("moduleA/out")
        val dirB = tmp.root.toPath().resolve("moduleB/out")
        val dirs = listOf(dirA, dirB)
        val base = differ.snapshot(dirs)

        // Edit only moduleA's copy; moduleB's identical-relPath class is untouched and must
        // not show up as changed, and moduleA's edit must resolve to moduleA's classFile.
        writeClass("moduleA/out", "com/x/Util.class", "BBBB")
        val result = differ.diff(base, differ.snapshot(dirs))

        assertEquals(1, result.changed.size)
        assertEquals(dirA.resolve("com/x/Util.class"), result.changed[0].classFile)
        assertEquals("com.x.Util", result.changed[0].binaryName)
    }

    @Test
    fun `baseline store round-trips and defaults to empty`() {
        val store = BaselineStore(tmp.root.toPath().resolve(".hotreload/baseline.txt"))
        assertTrue(store.load().isEmpty())
        val dir = tmp.root.toPath().resolve("out")
        val snapshot = mapOf(ClassKey(dir, "a/B.class") to "abc123")
        store.save(snapshot)
        assertEquals(snapshot, store.load())
    }

    @Test
    fun `baseline store keeps two dirs' same-relPath entries distinct`() {
        val store = BaselineStore(tmp.root.toPath().resolve(".hotreload/baseline.txt"))
        val dirA = tmp.root.toPath().resolve("moduleA/out")
        val dirB = tmp.root.toPath().resolve("moduleB/out")
        val snapshot = mapOf(
            ClassKey(dirA, "com/x/Util.class") to "aaa",
            ClassKey(dirB, "com/x/Util.class") to "zzz",
        )
        store.save(snapshot)
        assertEquals(snapshot, store.load())
    }
}
