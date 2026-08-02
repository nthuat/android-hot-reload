package dev.thuat.hotreload.cli

import java.nio.file.Files
import java.nio.file.Path

class BaselineStore(val file: Path) {
    fun load(): Map<String, String> {
        if (!Files.exists(file)) return emptyMap()
        return Files.readAllLines(file).filter { it.isNotBlank() }.associate { line ->
            val (hash, rel) = line.split(' ', limit = 2)
            rel to hash
        }
    }

    fun save(snapshot: Map<String, String>) {
        Files.createDirectories(file.parent)
        Files.write(file, snapshot.map { (rel, hash) -> "$hash $rel" }.sorted())
    }
}
