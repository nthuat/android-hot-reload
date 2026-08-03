package dev.thuat.hotreload.gradle

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * Lets consumers override the runtime dependency coordinate. Rarely needed now that the plugin
 * derives the coordinate from wherever it was itself resolved from (see
 * [HotReloadPlugin.defaultRuntimeCoordinate]) — kept as an escape hatch for setups where
 * derivation can't work, e.g. a repository layout this plugin doesn't recognise.
 */
abstract class HotReloadExtension {
    abstract val runtimeCoordinate: Property<String>
}

class HotReloadPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("hotreload", HotReloadExtension::class.java)
        extension.runtimeCoordinate.convention(defaultRuntimeCoordinate(project))
        project.plugins.withId("com.android.application") {
            // Deferred to afterEvaluate: a consumer's `hotreload { runtimeCoordinate.set(...) }`
            // block runs later in the same script, after this `plugins {}`-block apply().
            project.afterEvaluate {
                project.dependencies.add("debugImplementation", extension.runtimeCoordinate.get())
            }
            enableKeyMeta(project)
        }
        // Key-meta generation must run on every composable-bearing module (app AND libraries) —
        // the JVMTI agent redefines classes in whichever module the edited source lives in.
        project.plugins.withId("com.android.library") { enableKeyMeta(project) }
    }

    companion object {
        const val DEFAULT_RUNTIME_COORDINATE = "dev.thuat:hotreload-runtime:0.1.0-SNAPSHOT"

        // The module name this plugin's own jar is published under (see gradle-plugin's project
        // name in settings.gradle.kts) — needed to anchor the Maven-layout parse on the plugin's
        // own artifact segment, since the runtime artifact sits under a *different* one
        // (RuntimeCoordinateDerivation.RUNTIME_ARTIFACT_ID) beside it in the same repo.
        internal const val OWN_ARTIFACT_ID = "gradle-plugin"

        /**
         * The runtime coordinate to use when a consumer sets no explicit override: derived from
         * the jar this plugin class was loaded from (same group/version the runtime artifact
         * publishes under, whatever repository that turns out to be), falling back to the
         * hardcoded `dev.thuat` default when derivation isn't possible — notably the
         * composite-build/`includeBuild` case, where the plugin comes from a project rather than
         * a jar and Gradle substitutes the included build's project regardless of what
         * coordinate string is configured here.
         */
        internal fun defaultRuntimeCoordinate(project: Project): String {
            val jarFile = ownJarFile()
            val derived = jarFile?.let { RuntimeCoordinateDerivation.deriveFromJar(it, OWN_ARTIFACT_ID) }
            if (derived == null) {
                project.logger.info(
                    "hotreload: could not derive the runtime coordinate from the plugin's own " +
                        "classpath location (${jarFile?.absolutePath ?: "not loaded from a jar"}) " +
                        "— falling back to the built-in default '$DEFAULT_RUNTIME_COORDINATE'. " +
                        "This is expected for composite builds/includeBuild, where the plugin is " +
                        "resolved from a project rather than a jar; set " +
                        "hotreload.runtimeCoordinate explicitly if this default is wrong for " +
                        "your setup."
                )
                return DEFAULT_RUNTIME_COORDINATE
            }
            val (group, version) = derived
            return "$group:${RuntimeCoordinateDerivation.RUNTIME_ARTIFACT_ID}:$version"
        }

        private fun ownJarFile(): File? {
            val location = HotReloadPlugin::class.java.protectionDomain?.codeSource?.location ?: return null
            val file = runCatching { File(location.toURI()) }.getOrElse { File(location.path) }
            return file.takeIf { it.isFile }
        }
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
// Android-build-type granularity.
//
// Release must never actually ship these classes, though: `generateFunctionKeyMetaClasses`
// output includes `@FunctionKeyMetaClass(file = "<absolute local source path>")`, which release
// APKs must never carry, hard constraint aside from just "why ship dead debug-tooling classes".
// So every `compile*Release*Kotlin` task gets a `doLast` deleting `**/*$KeyMeta*.class` from its
// own declared outputs before any downstream task (dexing, packaging, R8) reads them. `doLast`
// runs as part of the task's own execution, so Gradle snapshots the *stripped* output for
// up-to-date checks — nothing downstream ever sees a KeyMeta class in a release build. This
// walks `Task.outputs.files` (plain Gradle-core API) rather than the KGP-specific
// `destinationDirectory` property, so it needs no extra compileOnly dependency and isn't tied to
// a specific Kotlin Gradle plugin task type.
private fun enableKeyMeta(project: Project) {
    project.plugins.withId("org.jetbrains.kotlin.plugin.compose") {
        project.extensions.configure(ComposeCompilerGradlePluginExtension::class.java) { ext ->
            ext.generateFunctionKeyMetaClasses.set(true)
        }
        project.tasks.matching { it.name.startsWith("compile") && it.name.contains("Release") && it.name.endsWith("Kotlin") }
            .configureEach { task ->
                task.doLast {
                    task.outputs.files.forEach { outputDir ->
                        project.fileTree(outputDir) { it.include("**/*\$KeyMeta*.class") }
                            .forEach { it.delete() }
                    }
                }
            }
    }
}
