package dev.hotreload.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

class HotReloadPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            project.dependencies.add("debugImplementation", "dev.hotreload:runtime:0.1.0-SNAPSHOT")
        }
        // Key-meta generation must run on every composable-bearing module (app AND libraries) —
        // the JVMTI agent redefines classes in whichever module the edited source lives in.
        project.plugins.withId("com.android.application") { enableKeyMeta(project) }
        project.plugins.withId("com.android.library") { enableKeyMeta(project) }
    }
}

// Top-level function (own synthetic class, not a member of HotReloadPlugin) so that Gradle's
// decoration of the Plugin<Project> implementation never needs to resolve Kotlin/Compose gradle
// plugin types on classpaths that don't have them (e.g. plain TestKit runs that apply this
// plugin without AGP/KGP present).
//
// NOTE ON SCOPE: the brief asked for a `-P plugin:...:generateFunctionKeyMetaClasses=true`
// freeCompilerArgs entry added only to *Debug Kotlin compile tasks. That was tried first and
// rejected by the compiler: "Multiple values are not allowed for plugin option
// androidx.compose.compiler.plugins.kotlin:generateFunctionKeyMetaClasses" — the
// org.jetbrains.kotlin.plugin.compose subplugin (applied by every Compose module already)
// unconditionally emits this same single-value option itself (default false) for every Kotlin
// compile task it attaches to, so an extra -P for the same option collides. The only supported
// way to set it is this compose-compiler-gradle-plugin project extension, which is project-wide
// (applies to release compiles too, not just debug) — Kotlin 2.1.0's extension has no per-
// Android-build-type granularity. Cost is limited to a few extra small annotated classes in
// release output; the runtime dependency that reads them is debugImplementation-only, so release
// never invokes the reflection path. See task-12 report for the full probe transcript.
private fun enableKeyMeta(project: Project) {
    project.plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        project.extensions.configure(ComposeCompilerGradlePluginExtension::class.java) { ext ->
            ext.generateFunctionKeyMetaClasses.set(true)
        }
    }
}
