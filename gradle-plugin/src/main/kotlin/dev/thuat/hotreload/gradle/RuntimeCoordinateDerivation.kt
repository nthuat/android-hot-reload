package dev.thuat.hotreload.gradle

import java.io.File
import java.util.Properties
import java.util.jar.JarFile

/**
 * Derives the `hotreload-runtime` Maven coordinate from wherever the Gradle plugin itself was
 * resolved from. The runtime artifact is always published alongside the plugin, under the same
 * group and version, in whatever repository resolved the plugin — so re-deriving group+version
 * from the plugin's own jar (rather than hardcoding `dev.thuat`) makes the same code correct
 * against mavenLocal/Central (`dev.thuat`) and JitPack (`com.github.<user>.<repo>`) alike.
 *
 * Every parsing entry point here is a pure function over a path string or file content, so it's
 * unit-testable without a real jar on disk.
 */
object RuntimeCoordinateDerivation {
    const val RUNTIME_ARTIFACT_ID = "hotreload-runtime"

    /**
     * Gradle module cache layout:
     * `.../caches/modules-2/files-2.1/<group>/<module>/<version>/<sha1>/<module>-<version>.jar`
     * Anchored on the `files-2.1` marker segment rather than counting from either end, since
     * the absolute prefix and the sha1 directory name both vary.
     */
    fun parseGradleCachePath(path: String): Pair<String, String>? {
        val parts = path.split('/', '\\')
        val markerIndex = parts.indexOf("files-2.1")
        if (markerIndex == -1 || markerIndex + 3 >= parts.size) return null
        val group = parts[markerIndex + 1]
        val version = parts[markerIndex + 3]
        if (group.isBlank() || version.isBlank()) return null
        return group to version
    }

    /**
     * mavenLocal's repository layout, `~/.m2/repository/<group-as-nested-dirs>/<artifactId>/
     * <version>/<artifactId>-<version>.jar`. Anchored on the `.m2/repository` marker segments —
     * joining every directory that precedes the artifact id (as the gradle-cache parser can,
     * since it anchors on the `files-2.1` marker instead) would also swallow the absolute prefix
     * (`/Users/dev/...`) into the "group", which is wrong.
     */
    fun parseMavenLayoutPath(path: String, artifactId: String): Pair<String, String>? {
        val parts = path.split('/', '\\').filter { it.isNotEmpty() }
        val repoIndex = (0 until parts.size - 1).firstOrNull { parts[it] == ".m2" && parts[it + 1] == "repository" }
            ?: return null
        val afterRepo = parts.subList(repoIndex + 2, parts.size)
        val artifactIndex = afterRepo.lastIndexOf(artifactId)
        if (artifactIndex <= 0 || artifactIndex + 1 >= afterRepo.size) return null
        val version = afterRepo[artifactIndex + 1]
        val group = afterRepo.subList(0, artifactIndex).joinToString(".")
        if (version.isBlank() || group.isBlank()) return null
        return group to version
    }

    /** `META-INF/maven/<groupId>/<artifactId>/pom.properties` content, when a jar embeds one. */
    fun parsePomProperties(content: String): Pair<String, String>? {
        val props = Properties()
        props.load(content.reader())
        val group = props.getProperty("groupId")
        val version = props.getProperty("version")
        if (group.isNullOrBlank() || version.isNullOrBlank()) return null
        return group to version
    }

    /**
     * Best-effort derivation from the jar the plugin class was actually loaded from. Returns
     * null (never throws) when the jar isn't in a recognisable layout, or the plugin wasn't
     * loaded from a jar at all (e.g. an `includeBuild` composite, where it's loaded from a
     * project's compiled classes directory) — callers fall back to a hardcoded default
     * coordinate in that case.
     */
    fun deriveFromJar(jarFile: File, ownArtifactId: String): Pair<String, String>? {
        if (!jarFile.isFile) return null
        val fromPomProperties = runCatching {
            JarFile(jarFile).use { jar ->
                jar.entries().asSequence()
                    .firstOrNull { it.name.endsWith("pom.properties") }
                    ?.let { entry -> jar.getInputStream(entry).use { stream -> stream.readBytes().decodeToString() } }
            }
        }.getOrNull()?.let { parsePomProperties(it) }
        if (fromPomProperties != null) return fromPomProperties

        val path = jarFile.absolutePath
        return parseGradleCachePath(path) ?: parseMavenLayoutPath(path, ownArtifactId)
    }
}
