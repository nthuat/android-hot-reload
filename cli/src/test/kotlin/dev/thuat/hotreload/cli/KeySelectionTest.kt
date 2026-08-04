package dev.thuat.hotreload.cli

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Covers the Jetcaster no-op fix: see KeySelection.kt's doc for the failure mechanism this
// closes (new keys sent to invalidateGroupsWithKey don't match the OLD keys the running app's
// slot table actually holds, on a structural edit that renumbers a composable's group key).
class KeySelectionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String): Path =
        java.nio.file.Paths.get("src/test/resources/keymeta-fixtures", name)

    @Test
    fun `keysSnapshot maps a real Compose class file to its extracted keys by absolute path`() {
        val dir = tmp.newFolder("classes").toPath()
        val target = dir.resolve("AppDrawerKt.class")
        Files.copy(fixture("compose111-method-level-AppDrawerKt.class"), target)

        val snapshot = keysSnapshot(listOf(dir))

        val expected = setOf(892941463, -1691715257, -1691242527, 1307325754, -973502026, 793611218)
        assertEquals(expected, snapshot.getValue(target).toSet())
    }

    @Test
    fun `keysSnapshot omits classes with no FunctionKeyMeta keys, keeping the map small`() {
        val dir = tmp.newFolder("classes-none").toPath()
        val target = dir.resolve("MainActivity.class")
        Files.copy(fixture("none-MainActivity.class"), target)

        val snapshot = keysSnapshot(listOf(dir))

        assertTrue(snapshot.isEmpty(), "expected no entry for a class with no keys, got: $snapshot")
    }

    // The actual bug: a structural edit renumbers AppDrawerKt's keys, so the freshly-compiled
    // class's keys (what KeyMetaExtractor.keysFor reads today) no longer overlap the OLD keys
    // still live in the on-device slot table. resolvedKeysFor must send the union of both, not
    // just the new set, so the invalidation actually reaches the composition holding the old
    // keys — exactly what was missing when the CLI reported tier1 while the UI never moved.
    @Test
    fun `resolvedKeysFor unions old on-device keys with the freshly compiled ones, even when they're disjoint`() {
        val newClassFile = fixture("compose111-method-level-AppDrawerKt.class")
        val changed = ChangedClass(
            classFile = newClassFile,
            binaryName = "com.example.jetnews.ui.AppDrawerKt",
            descriptor = "Lcom/example/jetnews/ui/AppDrawerKt;",
        )
        // Simulates the OLD build's keys for this same class path, before this cycle's compile
        // overwrote it — deliberately disjoint from the new fixture's real keys, the way a
        // structural edit renumbers them.
        val oldKeys = mapOf(newClassFile to listOf(11111, 22222))

        val resolved = resolvedKeysFor(changed, oldKeys)

        val newKeys = setOf(892941463, -1691715257, -1691242527, 1307325754, -973502026, 793611218)
        assertEquals(newKeys + setOf(11111, 22222), resolved.toSet())
        assertEquals(resolved.size, resolved.distinct().size, "expected no duplicate keys in the union")
    }

    @Test
    fun `resolvedKeysFor falls back to new-only keys when the class has no pre-compile entry`() {
        val changed = ChangedClass(
            classFile = fixture("compose111-method-level-AppDrawerKt.class"),
            binaryName = "com.example.jetnews.ui.AppDrawerKt",
            descriptor = "Lcom/example/jetnews/ui/AppDrawerKt;",
        )

        val resolved = resolvedKeysFor(changed, emptyMap())

        val newKeys = setOf(892941463, -1691715257, -1691242527, 1307325754, -973502026, 793611218)
        assertEquals(newKeys, resolved.toSet())
    }

    @Test
    fun `resolvedKeysFor is empty when neither the old nor new snapshot has any keys`() {
        val changed = ChangedClass(
            classFile = fixture("none-MainActivity.class"),
            binaryName = "dev.thuat.hotreload.sample.MainActivity",
            descriptor = "Ldev/thuat/hotreload/sample/MainActivity;",
        )

        assertTrue(resolvedKeysFor(changed, emptyMap()).isEmpty())
    }

    // keysFor's own legacy $KeyMeta sibling lookup (Compose ~1.7 shape) must be mirrored on the
    // OLD side too, using the fixture pair keysFor's own test already relies on.
    @Test
    fun `resolvedKeysFor also unions the legacy KeyMeta sibling's old keys`() {
        val facade = fixture("legacy-pair/GreetingKt.class")
        val siblingKeyMeta = fixture("legacy-pair/GreetingKt\$KeyMeta.class")
        val changed = ChangedClass(
            classFile = facade,
            binaryName = "dev.thuat.hotreload.sample.feature.GreetingKt",
            descriptor = "Ldev/thuat/hotreload/sample/feature/GreetingKt;",
        )
        // Old snapshot only knows about the sibling holder's keys (mirrors what keysSnapshot
        // would have captured pre-compile), not the facade itself.
        val oldKeys = mapOf(siblingKeyMeta to listOf(999))

        val resolved = resolvedKeysFor(changed, oldKeys)

        assertEquals(setOf(181946586, 999), resolved.toSet())
    }
}
