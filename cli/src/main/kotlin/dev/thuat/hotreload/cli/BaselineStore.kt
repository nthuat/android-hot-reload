package dev.thuat.hotreload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// v2 format: "hash\tclassDir\trelPath" per line (v1 was "hash rel", collapsed across every
// module's class dir into one key — see ClassDiffer's ClassKey doc for why that was wrong).
// Not forward/backward compatible with v1 baseline.txt files; harmless in practice since this
// is a local, regenerable dev-cache file (next `bootstrap` overwrites it), not anything checked
// in or shared across machines.
class BaselineStore(val file: Path) {
    fun load(): Map<ClassKey, String> {
        if (!Files.exists(file)) return emptyMap()
        return Files.readAllLines(file).filter { it.isNotBlank() }.associate { line ->
            val (hash, dir, rel) = line.split('\t', limit = 3)
            ClassKey(Paths.get(dir), rel) to hash
        }
    }

    fun save(snapshot: Map<ClassKey, String>) {
        Files.createDirectories(file.parent)
        Files.write(file, snapshot.map { (key, hash) -> "$hash\t${key.classDir}\t${key.relPath}" }.sorted())
    }
}
