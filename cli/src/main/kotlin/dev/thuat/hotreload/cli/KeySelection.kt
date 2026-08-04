package dev.thuat.hotreload.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

// The Jetcaster no-op bug: KeyMetaExtractor.keysFor(changed) reads the class file AFTER this
// cycle's compile has already overwritten it, so the keys it returns are the NEW build's group
// keys. But the app's already-running composition built its slot table from the OLD build's
// bytecode, still installed on-device — its recompose scopes are keyed with the OLD group keys
// until an actual recomposition rewrites them. A structural edit (anything that isn't a pure
// text/literal change) commonly renumbers a composable's group key, so the new keys sent to
// invalidateGroupsWithKey don't match anything in the live slot table: Compose's runtime API
// returns Unit either way (see ComposeInvalidator.kt's doc), so the on-device reflection call
// "succeeds" and tier1 gets reported even though nothing actually got invalidated. Reproduced
// live against Google's Jetcaster sample (see jetcaster-noop-report.md) — the small samples
// this tool was validated against never shifted a key because their edits never restructured a
// file, so this never showed up before.
//
// Fix: capture what's actually running on-device — the OLD keys, extracted from each class's
// bytecode as it sits on disk right BEFORE this cycle's compile overwrites it (that's the
// invariant ClassDiffer's own baseline hash already relies on: the class files on disk when a
// cycle starts are exactly what's currently redefined on the device, from either the last
// successful cycle or the original install) — and invalidate the UNION of old and new keys, not
// just the new ones. A trivial edit has old == new, so this changes nothing for the common case
// the small samples already covered; a structural edit now invalidates the key the running slot
// table is actually holding.

// Extracts FunctionKeyMeta keys for every .class file under `classDirs`, keyed by its absolute
// path, BEFORE a compile has a chance to overwrite any of them. Must be called before
// GradleCompiler.compile() in ReloadOrchestrator.cycle() to observe the pre-compile ("currently
// on-device") bytecode rather than the freshly-built one. Entries with no keys are omitted
// (nothing to union later, and it keeps the map small for a project with many non-composable
// classes) — a missing entry and an empty list are equivalent to every caller here.
internal fun keysSnapshot(classDirs: List<Path>): Map<Path, List<Int>> =
    classDirs.filter(Files::isDirectory).flatMap { dir ->
        Files.walk(dir).use { stream ->
            stream.asSequence()
                .filter { it.toString().endsWith(".class") }
                .mapNotNull { file ->
                    KeyMetaExtractor.extractKeys(file).takeIf { it.isNotEmpty() }?.let { file to it }
                }
                .toList()
        }
    }.toMap()

// The actual keys to send for `changed`: the union of its NEW keys (read from the just-compiled
// class file, via the existing KeyMetaExtractor.keysFor) and its OLD keys (looked up in
// `oldKeys`, a keysSnapshot taken before this cycle's compile ran — see this file's doc for why
// both are needed). Falls back to new-only when `changed` has no pre-compile entry (e.g. a class
// that didn't exist before this cycle) so a first-ever build still works exactly as before this
// fix. Top-level and pure for direct unit testing, mirroring KeyMetaExtractor.keysFor's own
// candidate derivation so old and new keys are looked up the same way.
internal fun resolvedKeysFor(changed: ChangedClass, oldKeys: Map<Path, List<Int>>): List<Int> {
    val new = KeyMetaExtractor.keysFor(changed)
    val old = oldKeys[changed.classFile].orEmpty().asSequence() +
        KeyMetaExtractor.legacyKeyMetaCandidates(changed.classFile, changed.binaryName)
            .asSequence()
            .flatMap { oldKeys[it].orEmpty().asSequence() }
    return (old + new.asSequence()).distinct().toList()
}
