package dev.thuat.hotreload.cli

import java.nio.file.Files
import java.nio.file.Path

/**
 * Recognizes and rewrites Gradle's "no such project" failure into an actionable error, for the
 * specific case that motivates this file: `--app-module` defaults to `:app` (see `Main.kt`), but
 * plenty of real projects (compose-samples/Jetcaster: `:mobile`/`:tv`) don't have one. Without
 * this, [GradleCompiler]'s raw Tooling API exception surfaces as `CompileError` ("compile error"
 * in the CLI's own category scheme) with Gradle's bare "project 'app' not found" text -- true but
 * useless, since there's nothing wrong with the user's source. This maps that specific shape to
 * `EnvironmentError` (exit 3, same category as JdkPreflight.kt's "wrong JDK" case) naming the
 * application modules the project actually has.
 */

// Confirmed against a real Tooling API failure (this repo's own sample/, pointed at a bogus
// `:nope` module -- see GradleCompilerIntegrationTest): GradleConnector's exception message ends
// up carrying Gradle's own console text verbatim, "* What went wrong:" included, e.g.:
//   Cannot locate tasks that match ':nope:mergeProjectDexDebug' as project 'nope' not found in
//   root project 'hotreload-sample'.
// Anchored on the configured module's own short name (the part after the last ':'), not a bare
// "project not found" substring match, so an unrelated Gradle failure that happens to mention
// some other missing project is never mistaken for this one.
internal fun isAppModuleNotFoundFailure(output: String, appModule: String): Boolean {
    val shortName = appModule.substringAfterLast(':')
    if (shortName.isEmpty()) return false
    val pattern = Regex(
        """Cannot locate tasks that match '[^']*' as project '${Regex.escape(shortName)}' not found in root project""",
    )
    return pattern.containsMatchIn(output)
}

/**
 * The replacement message for [isAppModuleNotFoundFailure]'s shape: names the module that was
 * configured (`--app-module`, or the `:app` default) and, when cheaply discoverable, every
 * application module the project actually has -- e.g. "no ':app' module in this project.
 * Application modules found: :mobile, :tv. Pass --app-module :mobile". Falls back to pointing at
 * `./gradlew projects` when [findApplicationModules] can't find any candidates (its scan is
 * best-effort text parsing, not a real Gradle evaluation -- see that function's doc).
 */
internal fun appModuleNotFoundHint(appModule: String, projectDir: Path): String {
    val candidates = findApplicationModules(projectDir)
    val base = "no '$appModule' module in this project."
    return if (candidates.isNotEmpty()) {
        "$base Application modules found: ${candidates.joinToString(", ")}. Pass --app-module ${candidates.first()}"
    } else {
        "$base Could not determine this project's application modules; run `./gradlew projects` " +
            "to see what's available, then pass --app-module <path>."
    }
}

// Cheap and best-effort by design: parses the CONSUMER project's own settings.gradle(.kts) for
// declared module paths, then greps each module's own build.gradle(.kts) text for the
// com.android.application plugin id. Deliberately NOT a second Tooling API build/model query --
// the failure this is reacting to already came from one Gradle invocation this cycle; spending a
// second one just to enumerate modules would double the cost of an already-failed cycle for a
// hint message. A plain text scan can't see plugins applied indirectly (a convention plugin, a
// version-catalog bundle) -- results sort alphabetically, same tie-break as
// HotReloadPlugin.findApplicationModulePaths on the Gradle-plugin side, for a consistent
// suggestion between "hotReloadInstallCli baked in X" and "cycle suggests X" if they ever
// disagree.
internal fun findApplicationModules(projectDir: Path): List<String> {
    val settingsText = listOf("settings.gradle.kts", "settings.gradle")
        .map { projectDir.resolve(it) }
        .firstOrNull { Files.exists(it) }
        ?.let { Files.readString(it) }
        ?: return emptyList()
    val modulePaths = Regex("""["'](:[\w.-]+(?::[\w.-]+)*)["']""").findAll(settingsText)
        .map { it.groupValues[1] }
        .distinct()
        .toList()
    return modulePaths.filter { hasApplicationPlugin(projectDir, it) }.sorted()
}

private fun hasApplicationPlugin(projectDir: Path, modulePath: String): Boolean {
    val moduleDir = projectDir.resolve(modulePath.removePrefix(":").replace(':', '/'))
    val buildText = listOf("build.gradle.kts", "build.gradle")
        .map { moduleDir.resolve(it) }
        .firstOrNull { Files.exists(it) }
        ?.let { Files.readString(it) }
        ?: return false
    return buildText.contains("com.android.application")
}
