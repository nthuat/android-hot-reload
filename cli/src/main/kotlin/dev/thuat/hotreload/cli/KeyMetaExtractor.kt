package dev.thuat.hotreload.cli

import java.nio.file.Files
import java.nio.file.Path
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode

// Host-side replacement for ComposeInvalidator.keysForClass's on-device reflection, which only
// works on Compose ~1.7: that compiler emits keys as RUNTIME-retention annotations on a separate
// `<Facade>$KeyMeta` holder class. Compose 1.11's compiler stopped emitting holder classes
// entirely and instead annotates each composable's own compiled method directly with
// `@FunctionKeyMeta(key=..., startOffset=..., endOffset=...)` — but that annotation is declared
// `@Retention(AnnotationRetention.BINARY)` (verified via javap on both androidx.compose.runtime
// 1.7.6's and 1.11.4's own FunctionKeyMeta.class — see the fix report), so it lands in
// RuntimeInvisibleAnnotations and reflection on-device can never see it, regardless of Compose
// version. The CLI already has the compiled .class file on disk, where invisible annotations are
// exactly as readable as visible ones — so extraction moves here.
//
// Two shapes to handle, both via the same repeatable-annotation reader (see keysFromAnnotations):
//  - Compose 1.11+: one `@FunctionKeyMeta` per composable, applied to that composable's own
//    compiled method (METHOD target). A file with N composables has N method-level annotations.
//  - Compose ~1.7: one `@FunctionKeyMeta` per composable, all applied to the CLASS
//    `<Facade>$KeyMeta` (CLASS target) — see keysFor's fallback for locating that sibling file.
// When more than one `@FunctionKeyMeta` lands on the *same* element, Kotlin's compiler wraps them
// in the compiler-generated `FunctionKeyMeta$Container(value = [...])` annotation (verified via
// javap on this repo's own MainActivityKt$KeyMeta, which has two composables) rather than listing
// two direct entries — so the container form must be unwrapped too, not just the direct form.
object KeyMetaExtractor {
    private const val KEY_META_DESC = "Landroidx/compose/runtime/internal/FunctionKeyMeta;"
    private const val KEY_META_CONTAINER_DESC =
        "Landroidx/compose/runtime/internal/FunctionKeyMeta\$Container;"

    fun extractKeys(classFile: Path): List<Int> =
        runCatching { extractKeys(Files.readAllBytes(classFile)) }.getOrDefault(emptyList())

    // Never throws: a class file too malformed/foreign for ASM to parse (or not a class file at
    // all) just yields no keys — extraction is best-effort plumbing feeding a redefine that must
    // still proceed either way (the runtime's on-device fallback / tier2 covers the rest), never
    // a reason to abort the whole reload cycle.
    fun extractKeys(bytes: ByteArray): List<Int> = runCatching {
        val node = ClassNode()
        ClassReader(bytes).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        val keys = mutableListOf<Int>()
        keys += keysFromAnnotations(node.visibleAnnotations)
        keys += keysFromAnnotations(node.invisibleAnnotations)
        node.methods.orEmpty().forEach { method ->
            keys += keysFromAnnotations(method.visibleAnnotations)
            keys += keysFromAnnotations(method.invisibleAnnotations)
        }
        keys.distinct()
    }.getOrDefault(emptyList())

    // A changed class's own keys (Compose 1.11+ shape: annotations sit directly on it) unioned
    // with whatever its legacy `$KeyMeta` sibling holds, if that sibling still exists on disk
    // (Compose ~1.7 shape). Mirrors ComposeInvalidator.keysForClass's candidate derivation
    // exactly (same "outer"/"outerKt" pair) so host-side extraction and the on-device fallback
    // agree on where to look — see that function's doc for why both candidates are needed (a
    // member composable's file facade isn't the class that got redefined).
    fun keysFor(changed: ChangedClass): List<Int> {
        val own = extractKeys(changed.classFile)
        val legacy = legacyKeyMetaCandidates(changed.classFile, changed.binaryName).asSequence()
            .filter(Files::exists)
            .flatMap { extractKeys(it).asSequence() }
        return (own.asSequence() + legacy).distinct().toList()
    }

    // The `<Facade>$KeyMeta` / `<Facade>Kt$KeyMeta` sibling paths that might hold this class's
    // Compose ~1.7 legacy keys (see the class doc). Factored out of [keysFor] so
    // [KeySelection.keysSnapshot]'s pre-compile pass can look the same candidate paths up in a
    // pre-built map instead of re-deriving them ad hoc.
    fun legacyKeyMetaCandidates(classFile: Path, binaryName: String): List<Path> {
        val outer = binaryName.substringBefore('$')
        return linkedSetOf("$outer\$KeyMeta", "${outer}Kt\$KeyMeta")
            .map { classFile.resolveSibling("${it.substringAfterLast('.')}.class") }
    }

    private fun keysFromAnnotations(annotations: List<AnnotationNode>?): List<Int> =
        annotations.orEmpty().flatMap { ann ->
            when (ann.desc) {
                KEY_META_DESC -> listOfNotNull(keyValue(ann))
                KEY_META_CONTAINER_DESC -> containerEntries(ann).mapNotNull(::keyValue)
                else -> emptyList()
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun containerEntries(container: AnnotationNode): List<AnnotationNode> {
        val values = container.values ?: return emptyList()
        val valueIdx = values.indexOf("value")
        if (valueIdx < 0 || valueIdx + 1 >= values.size) return emptyList()
        return (values[valueIdx + 1] as? List<AnnotationNode>).orEmpty()
    }

    // AnnotationNode.values is ASM's flat [name, value, name, value, ...] representation — there
    // is no typed accessor, so `key` must be found by name like this.
    private fun keyValue(annotation: AnnotationNode): Int? {
        val values = annotation.values ?: return null
        val keyIdx = values.indexOf("key")
        if (keyIdx < 0 || keyIdx + 1 >= values.size) return null
        return values[keyIdx + 1] as? Int
    }
}
