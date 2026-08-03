package dev.thuat.hotreload.gradle.testfixtures

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * Stand-ins for `com.android.application` / `com.android.library` / the Compose compiler Kotlin
 * subplugin, registered under the real plugin IDs via properties files under
 * `META-INF/gradle-plugins` in this module's `src/test/resources`, so [HotReloadPlugin]'s
 * `plugins.withId(...)` reactions fire
 * exactly as they would against the real Android/Kotlin Gradle plugins — without needing a real
 * AGP+Kotlin+Compose TestKit build (slow, network/SDK-dependent) for behaviour that only depends
 * on the plugin-id event, not on what AGP/KGP actually do.
 */
class FakeAndroidApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Real AGP creates this configuration; HotReloadPlugin adds the runtime dependency to it.
        project.configurations.create("debugImplementation")
    }
}

class FakeAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Library modules never get the runtime dependency, so no configuration to fake here.
    }
}

class FakeComposeCompilerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Mirrors the real org.jetbrains.kotlin.plugin.compose plugin registering its extension,
        // which is what HotReloadPlugin.enableKeyMeta configures.
        project.extensions.create(
            "composeCompiler",
            ComposeCompilerGradlePluginExtension::class.java,
        )
    }
}
