package dev.thuat.hotreload.cli

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

    // AGP 9 moved Kotlin compilation output from the Kotlin-Gradle-Plugin's own task (still what
    // AGP 8 uses) to AGP's built-in Kotlin compiler, with a different intermediates path — verified
    // live against Google's JetNews sample (AGP 9.3.1): the old path doesn't exist at all under
    // AGP 9, so a hardcoded single path silently snapshotted an empty set and every cycle reported
    // "no bytecode changes" forever (the bug this fixes). Rather than pick one, probe every known
    // layout and return whichever actually exist — cheap (a handful of Files.isDirectory stat
    // calls, no directory walk) and works whichever toolchain built the project. javac output is
    // included on the same reasoning even though neither sample project used here has Java sources
    // to verify it against live: its path has been stable across AGP versions and Kotlin's move to
    // a built-in compiler doesn't touch it.
    fun classDirsOf(module: String): List<Path> {
        val moduleDir = projectDir.resolve(module.removePrefix(":").replace(':', java.io.File.separatorChar))
        val variantCap = VARIANT.replaceFirstChar(Char::uppercase)
        val candidates = listOf(
            moduleDir.resolve("build/tmp/kotlin-classes/$VARIANT"),
            moduleDir.resolve("build/intermediates/built_in_kotlinc/$VARIANT/compile${variantCap}Kotlin/classes"),
            moduleDir.resolve("build/intermediates/javac/$VARIANT/compile${variantCap}JavaWithJavac/classes"),
        )
        val existing = candidates.filter(Files::isDirectory)
        if (existing.isEmpty()) {
            error(
                "no compiled-class output found for module $module — looked for:\n" +
                    candidates.joinToString("\n") { "  - $it" } +
                    "\n(checked AGP 8 + Kotlin-Gradle-Plugin layout, AGP 9 built-in-Kotlin layout, " +
                    "and javac output). Run a build first (./gradlew $module:assembleDebug), or this " +
                    "module's AGP/Kotlin toolchain uses a layout this tool doesn't know about yet."
            )
        }
        return existing
    }

    private companion object {
        val BUILD_FILES = setOf("build.gradle.kts", "build.gradle")
        const val VARIANT = "debug"
    }
}
