# JitPack distribution — report

Goal: make `android-hot-reload` consumable by other projects via JitPack, verified end to end
on a real device, with a GitHub release for the CLI.

## What changed

1. **`gradle-plugin/.../HotReloadPlugin.kt`** — added a `hotreload { runtimeCoordinate }`
   extension (`Property<String>`, default `"dev.thuat:hotreload-runtime:0.1.0-SNAPSHOT"` —
   unchanged behavior for mavenLocal/composite-build consumers). The `debugImplementation`
   dependency add moved into `project.afterEvaluate` so a consumer's own `hotreload { ... }`
   block (evaluated after the `plugins {}` block that triggers `apply()`) can override it before
   the dependency is actually added. This is obstacle 1: JitPack serves artifacts under
   `com.github.<user>.<repo>`, not `dev.thuat`, so the old hardcoded coordinate never resolved
   for JitPack consumers.
   Unit tests added in `HotReloadPluginTest.kt` (via `ProjectBuilder`) cover both the default
   and the override.
2. **`jitpack.yml`** (repo root) — `jdk: openjdk17` (matches what CI already verifies the whole
   build against) and a custom `install:` command:
   ```
   ./gradlew --configure-on-demand :gradle-plugin:publishToMavenLocal :hotreload-runtime:publishToMavenLocal
   ```
   `--configure-on-demand` was verified locally (via `-i` log grep) to skip *evaluating* `:agent`
   and `:cli` entirely for this task set — so JitPack's build image never needs NDK/CMake for
   `:agent`, and never touches `:cli`.
3. **`README.md`** — quickstart reordered to lead with JitPack (the `pluginManagement`
   `resolutionStrategy { eachPlugin { useModule(...) } }` block, the versioned plugin
   application, the `hotreload.runtimeCoordinate` override, and where to get the CLI zip). The
   old mavenLocal/composite-build routes are kept, demoted under "Alternative: building from
   source." License section updated: Apache-2.0 + the `jvmti.h` provenance note.

## Why the resolutionStrategy block is required (obstacle 2, confirmed empirically)

JitPack republished the plugin marker artifact too, but under the *remapped* group:
`com.github.nthuat.android-hot-reload:dev.thuat.hotreload.gradle.plugin:v0.1.0` — not under
group `dev.thuat.hotreload` where Gradle's normal `id("dev.thuat.hotreload") version "..."`
plugin-marker resolution looks. So marker-based resolution cannot work through JitPack
regardless of repository order; `useModule(...)` pointing straight at the `gradle-plugin` module
artifact is the only way. Verified by resolving it in a real throwaway consumer build (below).

## Git

- `d227c4b` — feat(gradle-plugin): make runtime dependency coordinate configurable
- (jitpack.yml commit) — build: add jitpack.yml scoped to gradle-plugin and hotreload-runtime
- (README commit) — docs: JitPack-first quickstart, demote mavenLocal/composite to source-build section
- Tag: `v0.1.0` (annotated, pushed to origin)
- All pushed to `main` on `origin` (github.com:nthuat/android-hot-reload).

## JitPack build

- Triggered via `GET https://jitpack.io/com/github/nthuat/android-hot-reload/v0.1.0/build.log`
  (first request builds synchronously).
- **First attempt was green** — no retag needed.
- Final status: `GET https://jitpack.io/api/builds/com.github.nthuat/android-hot-reload/v0.1.0`
  → `{"version":"v0.1.0","status":"ok", ..., "modules":["dev.thuat.hotreload.gradle.plugin","gradle-plugin","hotreload-runtime"],"isTag":true,"private":false}`
- Build log: `https://jitpack.io/com/github/nthuat/android-hot-reload/v0.1.0/build.log`
  Confirms: JDK 17 (`openjdk17`) picked up, `--configure-on-demand` install command ran, build
  completed in ~1m12s, all 3 artifacts (`gradle-plugin`, `hotreload-runtime`, and the plugin
  marker) found and published — `:agent` and `:cli` never appear in the log (never configured).

## Consumer resolution — proof (throwaway copy, JitPack only)

Copied `sample/` to a scratchpad dir outside the repo, stripped the `includeBuild("..")` wiring,
added a real Gradle wrapper, and rewrote `settings.gradle.kts`/`app/build.gradle.kts` to resolve
purely from `https://jitpack.io` + `google()`/`mavenCentral()` — no `mavenLocal()`, no
`includeBuild`.

- `./gradlew :app:assembleDebug -x lint` → **BUILD SUCCESSFUL**.
- `./gradlew :app:dependencies --configuration debugRuntimeClasspath` →
  ```
  +--- com.github.nthuat.android-hot-reload:hotreload-runtime:v0.1.0
  ```
  confirming the runtime resolves from JitPack, not `dev.thuat`.

## Device reload — proof (physical device, R5CX51BENMM)

Using the throwaway consumer app (built entirely from JitPack-resolved plugin/runtime) and the
locally-built CLI (`:cli:installDist`, JDK 21):

1. Installed + launched the app — baseline UI showed `"Hello, World!"`.
2. `cli bootstrap --project <throwaway> --package dev.thuat.hotreload.sample` →
   `✓ reloaded 0 class(es) in 0ms`.
3. Edited `Greeting.kt`'s composable body (`"Hello, $name!"` → `"Reloaded via JitPack, $name!"`).
4. `cli cycle --project <throwaway> --package dev.thuat.hotreload.sample --file Greeting.kt` →
   `✓ reloaded 1 class(es) in 2502ms [tier1 — remember state preserved]: dev.thuat.hotreload.sample.feature.GreetingKt`
5. `adb shell uiautomator dump` confirmed the on-device UI text changed to
   `"Reloaded via JitPack, World!"` — the published artifacts work, not just resolve.
6. Throwaway app uninstalled, scratch copy deleted afterward.

(One false alarm along the way: the very first `cli cycle` attempt failed with a cryptic
`26.0.2` error — that was just a missing `JAVA_HOME=21` export in that particular shell
invocation, defaulting to the system's JDK 26; re-running with `JAVA_HOME` set fixed it. Not a
JitPack or plugin issue.)

## Constraints verified green

- `./gradlew build -x lint` — BUILD SUCCESSFUL (135 tasks).
- `e2e/run-e2e.sh` (`ANDROID_SERIAL=R5CX51BENMM`, no emulator) — **E2E PASS** (golden path +
  incompatible-change path both correct).

## GitHub release

- `gh release create v0.1.0 cli/build/distributions/cli.zip` with notes covering what it is,
  the JDK 17+ (bundled CLI)/JDK 21 (building from source) requirements, and how to run it.
- URL: https://github.com/nthuat/android-hot-reload/releases/tag/v0.1.0
- Asset verified: `curl -sI -L` on the asset download URL → `302` to a signed
  `release-assets.githubusercontent.com` blob URL (i.e. it resolves and would download).
- Zip contents verified to include the agent `.so` for both ABIs:
  `cli/agent/arm64-v8a/libhotreload_agent.so`, `cli/agent/x86_64/libhotreload_agent.so`.

## Bottom line

JitPack works for this Gradle plugin + Android library combination, with two non-obvious fixes
required (coordinate override extension, `useModule` resolutionStrategy) — both now documented
in the README and covered by the throwaway end-to-end run. No fallback needed; the JitPack-first
quickstart is safe to ship as the default path for external consumers.
