package dev.thuat.hotreload.gradle

/**
 * Pure tag/URL/version mapping used by [InstallCliTask]. Kept separate from the task class (which
 * needs Gradle types and does real I/O) so this part stays unit-testable with no network and no
 * Gradle test fixtures.
 */
object CliInstallSupport {
    private const val REPO = "nthuat/android-hot-reload"

    /**
     * Maven version ("0.1.2") -> git release tag ("v0.1.2"). The GitHub release tag carries a `v`
     * prefix while the Maven Central version string does not — this is the one-character mapping
     * that silently 404s a download if it's ever inlined ad hoc instead of routed through here.
     */
    fun releaseTag(version: String): String = "v$version"

    /** The `cli.zip` asset URL for a given plugin version, matching this repo's release layout. */
    fun downloadUrl(version: String): String =
        "https://github.com/$REPO/releases/download/${releaseTag(version)}/cli.zip"

    /**
     * Extracts the version segment from a "group:artifact:version" coordinate — the shape
     * [HotReloadPlugin.defaultRuntimeCoordinate] returns. The CLI always tracks the plugin's own
     * resolved *version*; which group/artifact it came from is irrelevant for picking a release.
     */
    fun versionFromCoordinate(coordinate: String): String = coordinate.substringAfterLast(':')
}
