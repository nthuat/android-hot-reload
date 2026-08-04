package dev.thuat.hotreload.gradle

import java.io.File
import java.util.Properties

/**
 * Resolves the Android SDK directory to bake into the generated `hotreload` wrapper script (see
 * [HotReloadWrapperScript]) so `./hotreload run` works even when the caller's shell has no
 * `ANDROID_HOME` exported — the reported quickstart failure this whole file exists to fix.
 *
 * Priority, highest first:
 * 1. [agpSdkDir] — the Android Gradle Plugin's own resolved SDK location, when available (see
 *    [HotReloadPlugin]'s reflection lookup on the `android` extension's `sdkDirectory`, a stable
 *    public AGP API on `BaseExtension` unchanged across AGP 8.x/9.x).
 * 2. `sdk.dir` in [localPropertiesFile], read the same way AGP itself does.
 * 3. `ANDROID_HOME` / `ANDROID_SDK_ROOT` from [env] at configuration time.
 *
 * Every candidate is checked with [File.isDirectory] before being accepted, so a stale/wrong
 * value (typo'd `sdk.dir`, a leftover env var pointing nowhere) falls through to the next rung
 * instead of baking in a location that doesn't exist. Returns null (never throws) when nothing
 * resolves — callers then generate the wrapper without the SDK baked in, same as before this
 * feature existed, and the CLI's own "set ANDROID_HOME or pass --adb" error is still what a user
 * sees.
 *
 * Pure function over already-extracted inputs (no [org.gradle.api.Project] here) so it's
 * unit-testable without any Gradle/AGP scaffolding.
 */
object AndroidSdkResolution {
    fun resolve(agpSdkDir: File?, localPropertiesFile: File, env: Map<String, String>): File? =
        agpSdkDir?.takeIf { it.isDirectory }
            ?: sdkDirFromLocalProperties(localPropertiesFile)
            ?: envSdkDir(env)

    private fun sdkDirFromLocalProperties(file: File): File? {
        if (!file.isFile) return null
        val props = Properties()
        val loaded = runCatching { file.inputStream().use { props.load(it) } }.isSuccess
        if (!loaded) return null
        val path = props.getProperty("sdk.dir")?.takeIf { it.isNotBlank() } ?: return null
        return File(path).takeIf { it.isDirectory }
    }

    private fun envSdkDir(env: Map<String, String>): File? =
        (env["ANDROID_HOME"] ?: env["ANDROID_SDK_ROOT"])
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
}
