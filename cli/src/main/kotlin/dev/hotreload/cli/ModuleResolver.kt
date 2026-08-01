package dev.hotreload.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo

class ModuleResolver(private val projectDir: Path) {
    // ponytail: module = any dir with build.gradle(.kts) one or more levels below root; no settings.gradle parsing
    fun allModules(): List<String> =
        Files.walk(projectDir, 3).use { stream ->
            stream.filter { it.fileName.toString() in BUILD_FILES && it.parent != projectDir }
                .map { ":" + it.parent.relativeTo(projectDir).toString().replace(java.io.File.separatorChar, ':') }
                .toList()
        }.distinct()

    fun moduleOf(sourceFile: Path): String? {
        var dir = sourceFile.parent
        while (dir != null && dir != projectDir) {
            if (BUILD_FILES.any { Files.exists(dir.resolve(it)) }) {
                return ":" + dir.relativeTo(projectDir).toString().replace(java.io.File.separatorChar, ':')
            }
            dir = dir.parent
        }
        return null
    }

    fun classDirsOf(module: String): List<Path> {
        val moduleDir = projectDir.resolve(module.removePrefix(":").replace(':', java.io.File.separatorChar))
        return listOf(moduleDir.resolve("build/tmp/kotlin-classes/debug"))
    }

    private companion object {
        val BUILD_FILES = setOf("build.gradle.kts", "build.gradle")
    }
}
