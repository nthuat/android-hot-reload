# Contributing

Start with the [design spec](superpowers/specs/2026-08-01-android-hot-reload-design.md)
and the [implementation plan](superpowers/plans/2026-08-01-android-hot-reload-v1.md) for
the module breakdown, task sequence, and deliberately deferred v1 scope.

Issues and PRs welcome. `./gradlew build` runs the unit suites; `e2e/run-e2e.sh` runs the
on-device end-to-end test against a connected device or emulator (`ANDROID_SERIAL` to pick one).
Releasing is documented in [`releasing.md`](releasing.md).

<details>
<summary><b>Building from source / consuming a local build</b></summary>

If you're editing `android-hot-reload` itself, consume it locally instead of from Maven Central.

**mavenLocal**: publish once, consume like any other Maven dependency:
```bash
cd /path/to/android-hot-reload
export JAVA_HOME=$(/usr/libexec/java_home -v 21)  # see Requirements
./gradlew publishToMavenLocal :cli:installDist
```
`publishToMavenLocal` publishes both consumer-facing modules: the `dev.thuat.hotreload` Gradle
plugin (`gradle-plugin`) and the runtime library (`dev.thuat:hotreload-runtime`).
`:cli:installDist` builds the CLI with the JVMTI agent's `.so` files bundled inside its own
install tree, so it finds them automatically; no `--agent-so-dir` needed. Re-run both after
pulling changes to the tool. In your app project's `settings.gradle.kts`, add `mavenLocal()` to
both repository blocks (ahead of the others, so it's checked first):
```kotlin
pluginManagement {
    repositories { mavenLocal(); google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { mavenLocal(); google(); mavenCentral() }
}
```
Apply the plugin the same way as the quickstart (`id("dev.thuat.hotreload") version
"0.1.3"`), but skip the `hotreload { runtimeCoordinate.set(...) }` override: the plugin's
built-in default already points at the `dev.thuat` coordinate mavenLocal just published.

**Composite build**: if you want changes picked up without a `publishToMavenLocal` round-trip
each time:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    includeBuild("/path/to/android-hot-reload")
}
includeBuild("/path/to/android-hot-reload")
```
(mirrors `sample/settings.gradle.kts` in this repo, which intentionally consumes the tool this
way so the sample always builds against source.) Apply the plugin the same way but *without* a
version: `id("dev.thuat.hotreload")`.

**Measured cost of the composite route**: Gradle re-configures the entire 4-module tool build
every reload cycle: `./gradlew help -q` in the tool repo alone costs ~1.7-3.8s. On a real
consumer project this accounted for roughly half the `compile` phase of each reload; switching to
mavenLocal dropped median total cycle time from ~6.8s to ~4.0s (median compile 3.5s → 2.1s,
5-run samples, contended dev machine). Use the composite route only when you need source changes
to the tool itself reflected immediately; otherwise mavenLocal is faster.
</details>
