# Root-apply report: apply the hotreload plugin once at the root project

## Goal

Make `plugins { id("dev.thuat.hotreload") version "v0.1.1" }` in the **root** `build.gradle.kts`
the only required consumer change for a multi-module project — no per-module `plugins {}` block
needed, so a forgotten library module can no longer silently fall back to tier-2 reloads.

## What changed

`gradle-plugin/src/main/kotlin/dev/thuat/hotreload/gradle/HotReloadPlugin.kt`:

- `HotReloadPlugin.apply()` now checks `project == project.rootProject`. When true ("coordinator
  mode"), it calls `project.subprojects { subproject -> configureIfAndroid(subproject, extension) }`
  in addition to configuring the project itself — so every subproject reacts to its own
  `com.android.application`/`com.android.library` plugin via `plugins.withId(...)`, regardless of
  configuration order.
- The per-project configuration logic (dependency injection + key-meta flag + release-strip
  `doLast`) was extracted into `configureIfAndroid(target, extension)`, guarded by a new
  `claimConfiguration(target)` helper that stores a marker in `target.extensions.extraProperties`.
  This makes applying the plugin at **both** the root and a module idempotent: whichever
  `plugins.withId` callback fires first claims the marker and does the work; the other is a no-op.
  No duplicate dependency, no duplicate `doLast` registration, no "already applied" failure.
- `HotReloadExtension`'s doc comment now states where the extension lives: whichever project the
  plugin is *directly* applied to gets its own instance. In coordinator mode, every subproject is
  configured using the **root's** extension instance (passed straight into `configureIfAndroid`),
  so a `hotreload { runtimeCoordinate.set(...) }` override set once at the root reaches every
  subproject's injected dependency without each module needing its own `hotreload {}` block.
- Applying the plugin directly to a single module (today's style, and how `sample/` still applies
  it via composite build — left unchanged per the constraints) continues to work exactly as
  before, since `configureIfAndroid(project, extension)` is still called unconditionally on
  whatever project the plugin is applied to.

`gradle-plugin/build.gradle.kts`: added `testImplementation(libs.compose.compiler.gradle.plugin)`
so unit tests can register a fake `org.jetbrains.kotlin.plugin.compose` plugin that creates a real
`ComposeCompilerGradlePluginExtension`, letting the key-meta flag assertion exercise the real
extension type without a full AGP+Kotlin TestKit build.

`README.md`: the JitPack quickstart's step 2 now leads with "apply once at the root", explains
coordinator mode in plain terms, and keeps per-module application documented as a supported
alternative (noting the silent tier-2 fallback trap it reintroduces, and that mixing both styles
is safe). The mavenLocal/composite-build sections needed no changes — they already say "apply the
plugin the same way as the JitPack quickstart", which now means root-apply.

## Tests added (`gradle-plugin/src/test/kotlin/dev/thuat/hotreload/gradle/`)

**`HotReloadPluginCoordinatorTest.kt`** — fast `ProjectBuilder`-based unit tests. Fake
`com.android.application`/`com.android.library`/`org.jetbrains.kotlin.plugin.compose` plugins
(`testfixtures/FakeAndroidPlugins.kt`) are registered under their *real* plugin IDs via
`src/test/resources/META-INF/gradle-plugins/*.properties`, so `HotReloadPlugin`'s
`plugins.withId(...)` reactions fire exactly as they would against the real Android/Kotlin Gradle
plugins, without the cost of a full AGP+Kotlin+Compose build. `(project as
ProjectInternal).evaluate()` forces `afterEvaluate` to run (ProjectBuilder projects don't go
through Gradle's normal evaluation lifecycle otherwise). 8 tests:

- root-applied: application module gets the dependency + key-meta flag
- root-applied: library module gets the key-meta flag only, no dependency
- root-applied: a plain non-Android module gets neither
- module-applied directly (today's style) still configures dependency + flag
- root + module dual-apply: dependency added exactly once
- root + module dual-apply: release-strip `doLast` registered exactly once (asserted via
  `tasks.actions.size` on a fake `compileReleaseKotlin` task, baseline 0 → 1, not 2)
- explicit `runtimeCoordinate` override at the root reaches the application module's dependency
- sanity check that Gradle itself refuses to apply the identical plugin class twice to one project
  (documents why the guard only needs to handle the *two-distinct-projects* case)

**`HotReloadPluginRootApplyRealBuildTest.kt`** — one genuine TestKit multi-project build (real
`com.android.application`/`com.android.library`, real Android SDK) proving the `subprojects { }`
wiring and dependency injection against real AGP-created `debugImplementation` configurations, with
`dev.thuat.hotreload` applied only at the root. Deliberately skips Kotlin/Compose here: TestKit's
`withPluginClasspath()` isolates the plugin-under-test into its own classloader, separate from the
one used for externally-resolved plugins like `org.jetbrains.kotlin.plugin.compose` — so our
plugin's `compileOnly`-referenced `ComposeCompilerGradlePluginExtension` type isn't visible from
that isolated classloader in a synthetic TestKit build (a real consumer doesn't have this split;
both plugins resolve into the same project's normal plugin classpath there). The key-meta flag is
proven two other ways instead: the fast fake-plugin-id unit test above, and the real mavenLocal
consumer build below.

All pre-existing tests (`HotReloadPluginTest`, `RuntimeCoordinateDerivationTest`) still pass
unchanged. Full `gradle-plugin` suite: **22 tests, 0 failures**.

## Verification

### 1. Root build + CLI install

```
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew build -x lint       → BUILD SUCCESSFUL (136 actionable tasks)
./gradlew :cli:installDist    → BUILD SUCCESSFUL
```

### 2. `e2e/run-e2e.sh` — BLOCKED, device locked

`adb devices` confirms `R5CX51BENMM  device` (adb connection itself is fine), but the phone's
screen is behind a secure lock-screen bouncer (PIN/pattern/biometric — `dumpsys trust` reports
`deviceLocked=1`, `trustState=UNTRUSTED`; `dumpsys window` shows the `Bouncer` window). I did not
attempt to guess or bypass the lock (only tried the sanctioned `adb shell wm dismiss-keyguard` and
a wake/home keyevent, both of which are no-ops against a secure keyguard, as expected). The app
installs and launches fine (`Performing Streamed Install: Success`, `Starting: Intent {
cmp=dev.thuat.hotreload.sample/.MainActivity }`), but the UI-dump assertions after that fail
because the screen is showing the lock screen, not the app. **Please unlock the device and re-run
`e2e/run-e2e.sh` (with `ANDROID_HOME`/`ANDROID_SERIAL=R5CX51BENMM` set); I did not fabricate a
pass.**

### 3. Real root-only consumer proof (mavenLocal, throwaway copy)

```
./gradlew publishToMavenLocal   → BUILD SUCCESSFUL
```

Throwaway copy at `/tmp/rootapply-test` (copy of `sample/`, outside the repo): removed
`id("dev.thuat.hotreload")` from `app/build.gradle.kts` and `feature/build.gradle.kts`; changed
`settings.gradle.kts` to drop the `includeBuild("..")` composite-build wiring in favor of
`mavenLocal()` in both repository blocks; added `id("dev.thuat.hotreload") version
"0.1.0-SNAPSHOT"` **only** to the root `build.gradle.kts`.

- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (54 tasks, no `apply false`/`gradle-plugin:null` errors)
- `./gradlew :app:dependencies --configuration debugRuntimeClasspath` →
  `+--- dev.thuat:hotreload-runtime:0.1.0-SNAPSHOT` (resolves correctly)
- `find feature/build/tmp/kotlin-classes/debug -iname "*KeyMeta*"` →
  `GreetingKt$KeyMeta.class`, `ScreenHostKt$KeyMeta.class` — **the FEATURE module (a
  `com.android.library`) got the key-meta compiler flag purely from the root-only application.**
  This is the concrete proof that coordinator mode reaches library modules without any per-module
  plugin block.
- Install + launch + `bootstrap` + `cycle` on the physical device, editing the FEATURE module's
  `Greeting.kt`, confirming `tier1` in `adb logcat -s HotReload`: **not completed**, blocked by the
  same locked-device issue as step 2. `/tmp/rootapply-test` was left in place (not deleted) so this
  can be finished once the device is unlocked, per the task's own ordering (delete only after the
  on-device portion completes).

### 4. README

Updated — see "What changed" above.

## What's left modified anywhere

- This repo (`android-hot-reload`, `main` branch): all changes committed and pushed
  (`5dec90b`, `feat(gradle-plugin): apply hotreload once at the root, configure every subproject`).
  `sample/` was **not** touched (still applies the plugin per-module via composite build, per the
  constraints).
- `/tmp/rootapply-test`: throwaway consumer copy, **left in place** pending the on-device portion
  of step 3 above (not yet deleted). The test app is not installed on the device yet (install
  attempts failed only because the lock screen blocked UI verification after `assembleDebug`
  succeeded — no app was actually left running/installed from this session beyond the
  already-existing `sample/` install from the blocked e2e run).
- No new tag/release cut (not warranted — this is a backward-compatible plugin-behavior change,
  no API break).

## Outstanding action needed from you

Unlock device `R5CX51BENMM` (Samsung SM-F731B), then:
1. Re-run `e2e/run-e2e.sh` (`ANDROID_HOME=/Users/admin/Library/Android/sdk
   ANDROID_SERIAL=R5CX51BENMM ./e2e/run-e2e.sh`) to get a clean PASS.
2. From `/tmp/rootapply-test`, install the app, launch it, run `cli bootstrap`/`cli cycle` against
   the FEATURE module's `Greeting.kt`, and confirm `tier1` in `adb logcat -s HotReload`, pid
   unchanged, and the on-device text change via `uiautomator dump`.
3. Delete `/tmp/rootapply-test` and uninstall the test app once that's done.

I can complete both immediately once the device is unlocked — just say so.
