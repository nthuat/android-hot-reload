package dev.thuat.hotreload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.relativeTo
import kotlin.streams.asSequence

data class ChangedClass(val classFile: Path, val binaryName: String, val descriptor: String)
data class DiffResult(val changed: List<ChangedClass>, val added: List<String>, val removed: List<String>)

// Keys a class by its owning module's class dir *and* its dir-relative path. Two modules can
// legitimately contain a class at the same relative path (e.g. two modules both compiling
// `com/x/Util.class`) — collapsing across dirs down to a bare relPath key either masked one
// module's edit under the other's hash (snapshot, old: last-wins map merge) or, on diff,
// resolved to whichever module's copy happened to exist first in classDirs order (old:
// `classDirs.map{...}.first(Files::exists)`) — silently redefining the wrong module's file.
data class ClassKey(val classDir: Path, val relPath: String)

class ClassDiffer {
    fun snapshot(classDirs: List<Path>): Map<ClassKey, String> =
        classDirs.filter(Files::isDirectory).flatMap { dir ->
            Files.walk(dir).use { stream ->
                stream.asSequence()
                    .filter { it.toString().endsWith(".class") }
                    .map { ClassKey(dir, it.relativeTo(dir).toString().replace('\\', '/')) to sha256(it) }
                    .toList()
            }
        }.toMap()

    // classDirs no longer needed here: each key already carries its owning dir, so there is no
    // "guess which module" resolution step for ChangedClass.classFile.
    fun diff(baseline: Map<ClassKey, String>, current: Map<ClassKey, String>): DiffResult {
        val changed = current.filter { (key, hash) -> baseline[key] != null && baseline[key] != hash }
            .keys.map { key ->
                val binaryName = key.relPath.removeSuffix(".class").replace('/', '.')
                ChangedClass(
                    classFile = key.classDir.resolve(key.relPath),
                    binaryName = binaryName,
                    descriptor = "L${key.relPath.removeSuffix(".class")};",
                )
            }
        return DiffResult(
            changed = changed,
            added = (current.keys - baseline.keys).map { it.classDir.resolve(it.relPath).toString() }.sorted(),
            removed = (baseline.keys - current.keys).map { it.classDir.resolve(it.relPath).toString() }.sorted(),
        )
    }

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }
}
