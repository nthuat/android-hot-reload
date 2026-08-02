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
        val result = differ.diff(base, differ.snapshot(dirs), dirs)
        assertTrue(result.changed.isEmpty() && result.added.isEmpty() && result.removed.isEmpty())
    }

    @Test
    fun `modified class shows up with binary name and descriptor`() {
        writeClass("out", "com/foo/Bar.class", "AAAA")
        val differ = ClassDiffer()
        val dirs = listOf(tmp.root.toPath().resolve("out"))
        val base = differ.snapshot(dirs)
        writeClass("out", "com/foo/Bar.class", "BBBB")
        val result = differ.diff(base, differ.snapshot(dirs), dirs)
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
        val result = differ.diff(base, differ.snapshot(dirs), dirs)
        assertTrue(result.changed.isEmpty())
        assertEquals(listOf("com/foo/New.class"), result.added)
    }

    @Test
    fun `baseline store round-trips and defaults to empty`() {
        val store = BaselineStore(tmp.root.toPath().resolve(".hotreload/baseline.txt"))
        assertTrue(store.load().isEmpty())
        store.save(mapOf("a/B.class" to "abc123"))
        assertEquals(mapOf("a/B.class" to "abc123"), store.load())
    }
}
