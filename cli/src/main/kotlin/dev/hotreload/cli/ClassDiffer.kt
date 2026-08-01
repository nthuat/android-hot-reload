package dev.hotreload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.relativeTo
import kotlin.streams.asSequence

data class ChangedClass(val classFile: Path, val binaryName: String, val descriptor: String)
data class DiffResult(val changed: List<ChangedClass>, val added: List<String>, val removed: List<String>)

class ClassDiffer {
    fun snapshot(classDirs: List<Path>): Map<String, String> =
        classDirs.filter(Files::isDirectory).flatMap { dir ->
            Files.walk(dir).use { stream ->
                stream.asSequence()
                    .filter { it.toString().endsWith(".class") }
                    .map { it.relativeTo(dir).toString().replace('\\', '/') to sha256(it) }
                    .toList()
            }
        }.toMap()

    fun diff(baseline: Map<String, String>, current: Map<String, String>, classDirs: List<Path>): DiffResult {
        val changed = current.filter { (rel, hash) -> baseline[rel] != null && baseline[rel] != hash }
            .keys.map { rel ->
                val binaryName = rel.removeSuffix(".class").replace('/', '.')
                ChangedClass(
                    classFile = classDirs.map { it.resolve(rel) }.first(Files::exists),
                    binaryName = binaryName,
                    descriptor = "L${rel.removeSuffix(".class")};",
                )
            }
        return DiffResult(
            changed = changed,
            added = (current.keys - baseline.keys).sorted(),
            removed = (baseline.keys - current.keys).sorted(),
        )
    }

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }
}
