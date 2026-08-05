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
 *
 * Where it lives: whichever project the plugin is *directly* applied to gets its own instance
 * (root, a single module, or both — each `apply()` call creates one on its own project). When the
 * plugin runs in coordinator mode (applied at the root), every subproject is configured using the
 * *root's* extension, so an override set once at the root reaches every module's injected
 * dependency without each module needing its own `hotreload {}` block.
 */
abstract class HotReloadExtension {
    abstract val runtimeCoordinate: Property<String>
}

class HotReloadPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("hotreload", HotReloadExtension::class.java)
        extension.runtimeCoordinate.convention(defaultRuntimeCoordinate(project))

        registerInstallCliTask(project)

        // Coordinator mode: applied at the root, configure every subproject reactively so
        // ordering/configuration-time issues don't bite (a subproject's own `com.android.*`
        // plugin may apply before or after this one runs). This is what makes "apply once at the
        // root" sufficient — no per-module `plugins {}` block needed anymore.
        if (project == project.rootProject) {
            project.subprojects { subproject -> configureIfAndroid(subproject, extension) }
        }
        // Applying directly to a single module (today's style, and still how sample/ does it via
        // composite build) must keep working unchanged — configure the project itself too.
        configureIfAndroid(project, extension)
    }

    companion object {
        const val DEFAULT_RUNTIME_COORDINATE = "dev.thuat:hotreload-runtime:0.1.8"

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
                        "classpath location (${jarFile?.absolutePath ?: "not loaded from a jar"}), " +
                        "falling back to the built-in default '$DEFAULT_RUNTIME_COORDINATE'. " +
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

// Extra-property key marking a project as already configured (dependency added / key-meta
// enabled), so applying the plugin at both the root (coordinator mode) and directly on a module
// doesn't do the work twice — see [configureIfAndroid].
private const val CONFIGURED_MARKER = "dev.thuat.hotreload.configured"

internal const val INSTALL_CLI_TASK_NAME = "hotReloadInstallCli"

/**
 * Registers [InstallCliTask] on the *root* project of whatever build [project] belongs to —
 * always the root, regardless of whether the hotreload plugin itself was applied at the root
 * (coordinator mode) or per-module (today's style, and how `sample/` consumes it): a consumer
 * should be able to run `./gradlew hotReloadInstallCli` the same way no matter which style their
 * build uses. Guarded so it only registers once even though [HotReloadPlugin.apply] can run
 * against several projects that all share the same root (coordinator + per-module both applying,
 * or several modules each applying it directly).
 */
private fun registerInstallCliTask(project: Project) {
    val root = project.rootProject
    if (root.tasks.names.contains(INSTALL_CLI_TASK_NAME)) return

    val version = CliInstallSupport.versionFromCoordinate(HotReloadPlugin.defaultRuntimeCoordinate(project))
    root.tasks.register(INSTALL_CLI_TASK_NAME, InstallCliTask::class.java) { task ->
        task.version.set(version)
        task.outputDir.set(root.layout.buildDirectory.dir("hotreload/cli"))
        task.projectDir.set(root.layout.projectDirectory)
        // Best-effort only — read at task-execution time (after every project is configured), via
        // reflection so gradle-plugin doesn't need a compileOnly dependency on AGP just for this
        // one optional nicety. Any failure (no "android" extension, method shape differs, no
        // application module at all) falls back to a placeholder in the printed command.
        task.applicationId.set(project.provider { findApplicationId(root) ?: "" })
        task.androidSdkDir.set(project.provider { resolveSdkDir(root)?.absolutePath ?: "" })
        task.applicationModules.set(project.provider { findApplicationModulePaths(root) })
    }
}

/** See [registerInstallCliTask]'s comment on why this is reflection instead of a typed AGP API. */
private fun findApplicationId(root: Project): String? = runCatching {
    root.allprojects.firstNotNullOfOrNull { p ->
        val android = p.extensions.findByName("android") ?: return@firstNotNullOfOrNull null
        val defaultConfig = android.javaClass.getMethod("getDefaultConfig").invoke(android)
        defaultConfig?.javaClass?.getMethod("getApplicationId")?.invoke(defaultConfig) as? String
    }
}.getOrNull()

/**
 * Gradle paths (e.g. `:app`, `:mobile`) of every module with `com.android.application` applied,
 * sorted alphabetically -- the module `hotReloadInstallCli` bakes into the wrapper as
 * `--app-module` is `firstOrNull()` of this list (see [InstallCliTask.writeWrapper]).
 *
 * TIE-BREAK RULE for multiple application modules (e.g. compose-samples/Jetcaster has both
 * `:mobile` and `:tv`): alphabetical order of the Gradle project path, first one wins.
 * Deliberately NOT `root.allprojects`' own iteration order (declaration order in
 * `settings.gradle(.kts)`, or subproject discovery order) -- that's an accident of how a
 * consumer happened to list their modules, not a meaningful signal, and would make the baked-in
 * default silently change if they ever reordered an `include(...)` call. Alphabetical is at
 * least reproducible from the settings file alone and doesn't require guessing at "the important
 * one". No compileOnly AGP dependency needed here (unlike [findApplicationId]/[findAgpSdkDir]):
 * `PluginContainer.hasPlugin(String)` is a stable Gradle-core API that matches by plugin id, no
 * AGP type resolution required.
 */
internal fun findApplicationModulePaths(root: Project): List<String> = runCatching {
    root.allprojects
        .filter { it.plugins.hasPlugin("com.android.application") }
        .map { it.path }
        .sorted()
}.getOrElse { emptyList() }

/** See [AndroidSdkResolution] for the priority order and why each rung falls back to the next. */
private fun resolveSdkDir(root: Project): File? = AndroidSdkResolution.resolve(
    agpSdkDir = findAgpSdkDir(root),
    localPropertiesFile = File(root.projectDir, "local.properties"),
    env = System.getenv(),
)

/**
 * `BaseExtension.getSdkDirectory(): File` is a stable public AGP API (unchanged across AGP
 * 8.x/9.x), but calling it directly would need a `compileOnly` AGP dependency this module
 * deliberately avoids -- same reflection trick as [findApplicationId], and for the same reason.
 */
private fun findAgpSdkDir(root: Project): File? = runCatching {
    root.allprojects.firstNotNullOfOrNull { p ->
        val android = p.extensions.findByName("android") ?: return@firstNotNullOfOrNull null
        android.javaClass.getMethod("getSdkDirectory").invoke(android) as? File
    }
}.getOrNull()

/**
 * Reacts to whichever of `com.android.application` / `com.android.library` applies to [target],
 * in whichever order that happens relative to this call (`plugins.withId` fires immediately if
 * the plugin is already applied, or later when it is). Guarded by [claimConfiguration] so that
 * applying the hotreload plugin at *both* the root — which reaches every subproject through
 * [HotReloadPlugin.apply]'s coordinator loop — *and* directly on the module itself still only
 * adds the runtime dependency / registers the release-strip action once.
 */
private fun configureIfAndroid(target: Project, extension: HotReloadExtension) {
    target.plugins.withId("com.android.application") {
        if (claimConfiguration(target)) {
            // Deferred to afterEvaluate: a consumer's `hotreload { runtimeCoordinate.set(...) }`
            // block runs later — either later in the same module script, or (coordinator mode)
            // in the root script, which finishes evaluating before this module's afterEvaluate.
            target.afterEvaluate {
                target.dependencies.add("debugImplementation", extension.runtimeCoordinate.get())
            }
            enableKeyMeta(target)
        }
    }
    // Key-meta generation must run on every composable-bearing module (app AND libraries) —
    // the JVMTI agent redefines classes in whichever module the edited source lives in.
    target.plugins.withId("com.android.library") {
        if (claimConfiguration(target)) enableKeyMeta(target)
    }
}

/** True the first time this is called for [target]; false on every subsequent call. */
private fun claimConfiguration(target: Project): Boolean {
    val extra = target.extensions.extraProperties
    if (extra.has(CONFIGURED_MARKER)) return false
    extra.set(CONFIGURED_MARKER, true)
    return true
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
