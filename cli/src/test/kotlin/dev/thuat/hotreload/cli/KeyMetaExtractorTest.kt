package dev.thuat.hotreload.cli

import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Fixtures under src/test/resources/keymeta-fixtures are REAL compiled .class files (not
// hand-written byte arrays), copied from this repo's own sample build output (Compose ~1.7,
// Kotlin 2.1) and from a genuine Compose 1.11.4 / Kotlin 2.4.10 build (Google's JetNews,
// AppDrawerKt — see the fix report for how it was built) — see keymeta-fixtures.md in that
// directory for exact provenance and how to regenerate them.
class KeyMetaExtractorTest {
    private fun fixture(name: String): Path =
        Paths.get("src/test/resources/keymeta-fixtures", name)

    // Compose ~1.7 shape: a single @FunctionKeyMeta on the `<Facade>$KeyMeta` holder CLASS
    // (RUNTIME retention there — reflectable, which is why the on-device fallback still works
    // for this generation). GreetingKt.kt has exactly one composable.
    @Test
    fun `extracts a single class-level key from a legacy KeyMeta holder`() {
        val keys = KeyMetaExtractor.extractKeys(fixture("legacy-single-GreetingKt\$KeyMeta.class"))
        assertEquals(listOf(181946586), keys)
    }

    // Compose ~1.7 shape with >1 composable in the same file: the compiler wraps repeated
    // annotations on the same element in a generated `FunctionKeyMeta$Container(value = [...])`
    // rather than emitting two direct entries — this must be unwrapped too.
    @Test
    fun `extracts multiple keys from a Container-wrapped legacy KeyMeta holder`() {
        val keys = KeyMetaExtractor.extractKeys(fixture("legacy-container-MainActivityKt\$KeyMeta.class"))
        assertEquals(setOf(-439294691, 1502181635), keys.toSet())
        assertEquals(2, keys.size)
    }

    // Compose 1.11+ shape: no holder class at all. @FunctionKeyMeta sits directly on each
    // composable's own compiled method, BINARY retention (RuntimeInvisibleAnnotations) — this is
    // the whole point of the fix, since that retention makes it unreadable via on-device
    // reflection regardless of Compose version.
    @Test
    fun `extracts every method-level key from a real Compose 1_11 class with no holder`() {
        val keys = KeyMetaExtractor.extractKeys(fixture("compose111-method-level-AppDrawerKt.class"))
        val expected = setOf(892941463, -1691715257, -1691242527, 1307325754, -973502026, 793611218)
        assertEquals(expected, keys.toSet())
    }

    @Test
    fun `an ordinary class with no FunctionKeyMeta annotations yields no keys`() {
        assertTrue(KeyMetaExtractor.extractKeys(fixture("none-MainActivity.class")).isEmpty())
    }

    // keysFor is what ReloadOrchestrator actually calls: given the CHANGED class (the file
    // facade itself, which on Compose ~1.7 carries none of its own keys — they're all on the
    // sibling holder), it must find that sibling on disk and read it. legacy-pair/ keeps both
    // real files under their true compiler-emitted names (GreetingKt.class next to
    // GreetingKt$KeyMeta.class) since keysFor's sibling lookup is name-sensitive, exactly like a
    // real build's classDir.
    @Test
    fun `keysFor finds a legacy sibling holder next to the facade class it was asked about`() {
        val facade = fixture("legacy-pair/GreetingKt.class")
        val changed = ChangedClass(
            classFile = facade,
            binaryName = "dev.thuat.hotreload.sample.feature.GreetingKt",
            descriptor = "Ldev/thuat/hotreload/sample/feature/GreetingKt;",
        )
        assertEquals(listOf(181946586), KeyMetaExtractor.keysFor(changed))
    }

    @Test
    fun `keysFor returns empty for a class with no keys and no legacy sibling on disk`() {
        val changed = ChangedClass(
            classFile = fixture("none-MainActivity.class"),
            binaryName = "dev.thuat.hotreload.sample.MainActivity",
            descriptor = "Ldev/thuat/hotreload/sample/MainActivity;",
        )
        assertTrue(KeyMetaExtractor.keysFor(changed).isEmpty())
    }

    // Extraction failing must degrade to "no keys" rather than throw — the CLI still pushes the
    // redefine and the runtime falls back to its own lookup / tier2, never an aborted cycle.
    @Test
    fun `extractKeys on garbage bytes returns empty instead of throwing`() {
        assertEquals(emptyList(), KeyMetaExtractor.extractKeys(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `extractKeys on a nonexistent path returns empty instead of throwing`() {
        assertEquals(emptyList(), KeyMetaExtractor.extractKeys(Paths.get("does/not/exist.class")))
    }
}
