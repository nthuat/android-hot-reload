package dev.thuat.hotreload.gradle

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers applying [HotReloadPlugin] at the root of a multi-project build ("coordinator mode"),
 * using fake `com.android.application` / `com.android.library` / compose-compiler plugins
 * registered under their real IDs (see `testfixtures.FakeAndroidPlugins` and this module's
 * `src/test/resources/META-INF/gradle-plugins`) so the `plugins.withId(...)` reactions in
 * [HotReloadPlugin] fire exactly as they would for the real Android/Kotlin Gradle plugins, without
 * the cost of a full AGP+Kotlin TestKit build for behaviour that only depends on the plugin-id
 * event. `(project as ProjectInternal).evaluate()` forces `afterEvaluate` callbacks to run, since
 * ProjectBuilder projects otherwise never go through Gradle's normal evaluation lifecycle.
 */
class HotReloadPluginCoordinatorTest {
    private fun buildMultiProject(): Triple<Project, Project, Project> {
        val root = ProjectBuilder.builder().withName("root").build()
        val app = ProjectBuilder.builder().withName("app").withParent(root).build()
        val feature = ProjectBuilder.builder().withName("feature").withParent(root).build()
        return Triple(root, app, feature)
    }

    private fun evaluate(vararg projects: Project) {
        projects.forEach { (it as ProjectInternal).evaluate() }
    }

    private fun keyMetaFlag(project: Project): Boolean? =
        project.extensions.findByType(ComposeCompilerGradlePluginExtension::class.java)
            ?.generateFunctionKeyMetaClasses
            ?.orNull

    private fun runtimeDeps(project: Project) =
        project.configurations.getByName("debugImplementation").dependencies.toList()

    @Test
    fun `root-applied plugin gives the application module the dependency and the key-meta flag`() {
        val (root, app, _) = buildMultiProject()
        app.pluginManager.apply("com.android.application")
        app.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        root.plugins.apply(HotReloadPlugin::class.java)
        evaluate(root, app)

        val deps = runtimeDeps(app)
        assertEquals(1, deps.size)
        assertEquals(HotReloadPlugin.DEFAULT_RUNTIME_COORDINATE, "${deps.single().group}:${deps.single().name}:${deps.single().version}")
        assertEquals(true, keyMetaFlag(app))
    }

    @Test
    fun `root-applied plugin gives a library module the key-meta flag only, no dependency`() {
        val (root, _, feature) = buildMultiProject()
        feature.pluginManager.apply("com.android.library")
        feature.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        root.plugins.apply(HotReloadPlugin::class.java)
        evaluate(root, feature)

        assertEquals(true, keyMetaFlag(feature))
        assertFalse(feature.configurations.names.contains("debugImplementation"))
    }

    @Test
    fun `root-applied plugin leaves a plain non-android module untouched`() {
        val root = ProjectBuilder.builder().withName("root").build()
        val plain = ProjectBuilder.builder().withName("plain").withParent(root).build()
        root.plugins.apply(HotReloadPlugin::class.java)
        evaluate(root, plain)

        assertFalse(plain.configurations.names.contains("debugImplementation"))
        assertEquals(null, keyMetaFlag(plain))
    }

    @Test
    fun `module-applied directly (today's style) still configures dependency and flag`() {
        val (_, app, _) = buildMultiProject()
        app.pluginManager.apply("com.android.application")
        app.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        app.plugins.apply(HotReloadPlugin::class.java) // no root application at all
        evaluate(app)

        assertEquals(1, runtimeDeps(app).size)
        assertEquals(true, keyMetaFlag(app))
    }

    @Test
    fun `applying at both root and module does not duplicate the dependency`() {
        val (root, app, _) = buildMultiProject()
        app.pluginManager.apply("com.android.application")
        app.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        root.plugins.apply(HotReloadPlugin::class.java) // coordinator reaches `app`
        app.plugins.apply(HotReloadPlugin::class.java) // ...and `app` applies it directly too
        evaluate(root, app)

        assertEquals(1, runtimeDeps(app).size)
    }

    @Test
    fun `applying at both root and module registers the release-strip action exactly once`() {
        val (root, app, _) = buildMultiProject()
        app.pluginManager.apply("com.android.application")
        app.pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        val releaseCompile = app.tasks.register("compileReleaseKotlin").get()
        assertEquals(0, releaseCompile.actions.size) // baseline: nothing registered yet

        root.plugins.apply(HotReloadPlugin::class.java)
        app.plugins.apply(HotReloadPlugin::class.java)
        evaluate(root, app)

        assertEquals(1, releaseCompile.actions.size)
    }

    @Test
    fun `explicit runtimeCoordinate override at the root reaches the application module's injected dependency`() {
        val (root, app, _) = buildMultiProject()
        app.pluginManager.apply("com.android.application")
        root.plugins.apply(HotReloadPlugin::class.java)
        val rootExtension = root.extensions.getByType(HotReloadExtension::class.java)
        val override = "com.example.override:hotreload-runtime:9.9.9"
        rootExtension.runtimeCoordinate.set(override)
        evaluate(root, app)

        val deps = runtimeDeps(app)
        assertEquals(1, deps.size)
        assertEquals(override, "${deps.single().group}:${deps.single().name}:${deps.single().version}")
    }

    @Test
    fun `hotReloadInstallCli is registered exactly once on the root project regardless of apply style`() {
        val (root, app, feature) = buildMultiProject()
        app.pluginManager.apply("com.android.application")
        feature.pluginManager.apply("com.android.library")
        // Coordinator apply at root, PLUS per-module apply on both app and feature (sample/'s
        // actual style) — registerInstallCliTask must not throw "task already exists".
        root.plugins.apply(HotReloadPlugin::class.java)
        app.plugins.apply(HotReloadPlugin::class.java)
        feature.plugins.apply(HotReloadPlugin::class.java)
        evaluate(root, app, feature)

        assertTrue(root.tasks.names.contains(INSTALL_CLI_TASK_NAME))
        assertEquals(1, root.tasks.withType(InstallCliTask::class.java).size)
    }

    @Test
    fun `hotReloadInstallCli is registered on the root project even when the plugin is only applied per-module`() {
        val (root, app, _) = buildMultiProject()
        app.pluginManager.apply("com.android.application")
        app.plugins.apply(HotReloadPlugin::class.java) // no root application at all, mirrors sample/
        evaluate(root, app)

        assertTrue(root.tasks.names.contains(INSTALL_CLI_TASK_NAME))
        assertFalse(app.tasks.names.contains(INSTALL_CLI_TASK_NAME))
    }

    // Covers the app-module tie-break (see HotReloadPlugin.findApplicationModulePaths): a project
    // with more than one com.android.application module -- exactly the Jetcaster shape (:mobile
    // and :tv, no :app at all) that motivated baking --app-module into the wrapper in the first
    // place -- must resolve to the same module every time, regardless of which order the
    // subprojects happen to be declared/configured in.
    @Test
    fun `multiple application modules resolve deterministically to the alphabetically-first Gradle path`() {
        val root = ProjectBuilder.builder().withName("root").build()
        val tv = ProjectBuilder.builder().withName("tv").withParent(root).build()
        val mobile = ProjectBuilder.builder().withName("mobile").withParent(root).build()
        // Declared/built in "tv, then mobile" order -- if the resolution depended on
        // Project.allprojects's iteration order this would pick :tv; the alphabetical tie-break
        // must still pick :mobile regardless.
        tv.pluginManager.apply("com.android.application")
        mobile.pluginManager.apply("com.android.application")

        assertEquals(listOf(":mobile", ":tv"), findApplicationModulePaths(root))
    }

    @Test
    fun `a single application module resolves to just itself`() {
        val (root, app, _) = buildMultiProject()
        app.pluginManager.apply("com.android.application")

        assertEquals(listOf(":app"), findApplicationModulePaths(root))
    }

    @Test
    fun `no application module resolves to an empty list, not a failure`() {
        val root = ProjectBuilder.builder().withName("root").build()
        assertEquals(emptyList(), findApplicationModulePaths(root))
    }

    @Test
    fun `applying the plugin twice to the very same project is a no-op the second time`() {
        // Sanity check underpinning the dual-apply tests above: Gradle itself refuses to apply
        // the identical plugin class twice to one project, so `apply()`'s body never runs twice
        // for a single project — the guard in configureIfAndroid only has to handle the
        // root-and-module (two distinct projects, two distinct apply() calls) case.
        val project = ProjectBuilder.builder().build()
        project.plugins.apply(HotReloadPlugin::class.java)
        project.plugins.apply(HotReloadPlugin::class.java)
        assertTrue(project.plugins.hasPlugin(HotReloadPlugin::class.java))
    }
}
