# Coordinate rename: dev.hotreload → dev.thuat

## Status
Complete. All product-code coordinates moved. Root build, sample build, agent build, and
full E2E all verified green after the change.

## Decisions

- **Runtime artifact coordinate**: chose option (a) from the brief — kept the Gradle module
  directory at `runtime/` but renamed the *project name* (not the path) to `hotreload-runtime`
  via `project(":runtime").name = "hotreload-runtime"` in root `settings.gradle.kts`. Combined
  with `group = "dev.thuat"` in `runtime/build.gradle.kts`, Gradle's composite-build automatic
  substitution now matches `dev.thuat:hotreload-runtime` with zero extra machinery — verified
  directly: `:app:dependencies --configuration debugRuntimeClasspath` shows
  `dev.thuat:hotreload-runtime:0.1.0-SNAPSHOT -> project :android-hot-reload:hotreload-runtime`.
  `HotReloadPlugin.kt`'s injected dependency string was updated to match:
  `"dev.thuat:hotreload-runtime:0.1.0-SNAPSHOT"`.
- **Gradle plugin id**: `dev.hotreload` → `dev.thuat.hotreload` (not `dev.thuat` — the id needs
  the `hotreload` segment to stay a distinct, recognizable plugin id under the new group
  namespace). Implementation class path updated to
  `dev.thuat.hotreload.gradle.HotReloadPlugin`.
- **Maven `group`** on `runtime` and `gradle-plugin` modules: bare `dev.thuat` (per spec).
- **Non-coordinate strings left untouched** per the brief: JVMTI socket name
  `hotreload-agent`, on-device path segments `hotreload/...`, `.hotreload` baseline dir,
  manifest provider authority `${applicationId}.hotreload-init`, native library/CMake target
  name `hotreload_agent` — none of these are Java/Maven coordinates.

## Files changed (product code)

- `settings.gradle.kts` — runtime project rename
- `runtime/build.gradle.kts`, `runtime/src/main/AndroidManifest.xml`
- `runtime/src/main/kotlin/dev/hotreload/runtime/*.kt` → moved to
  `runtime/src/main/kotlin/dev/thuat/hotreload/runtime/*.kt` (package + doc comment)
- `gradle-plugin/build.gradle.kts`
- `gradle-plugin/src/{main,test}/kotlin/dev/hotreload/gradle/*.kt` → moved to
  `.../dev/thuat/hotreload/gradle/*.kt`
- `agent/build.gradle.kts` (namespace)
- `agent/src/main/cpp/agent.cpp` — JNI descriptor
  `Ldev/hotreload/runtime/ComposeInvalidator;` → `Ldev/thuat/hotreload/runtime/ComposeInvalidator;`
  (+ comment example)
- `cli/build.gradle.kts` (mainClass)
- `cli/src/{main,test}/kotlin/dev/hotreload/cli/*.kt` (19 files) → moved to
  `.../dev/thuat/hotreload/cli/*.kt`; test fixture strings referencing the sample package
  (`ReloadOrchestratorTest.kt`, `AdbTest.kt`, `GradleCompilerIntegrationTest.kt`) updated too
- `sample/app/build.gradle.kts`, `sample/feature/build.gradle.kts` (plugin id, namespace,
  applicationId)
- `sample/app/src/main/kotlin/dev/hotreload/sample/MainActivity.kt` → moved to
  `.../dev/thuat/hotreload/sample/MainActivity.kt` (package + import)
- `sample/feature/src/main/kotlin/dev/hotreload/sample/feature/Greeting.kt` → moved to
  `.../dev/thuat/hotreload/sample/feature/Greeting.kt`
- `e2e/run-e2e.sh` — `PKG`, `GREETING` path
- `README.md` — plugin id example

`sample/.hotreload/` (gitignored build state — baseline hashes, dex cache) was left as-is; it
regenerates from a fresh baseline on the next `hotreload bootstrap` and was not part of any
git-tracked rename.

## Verification (evidence)

1. Repo-wide grep for `dev\.hotreload|dev/hotreload` across `*.kt *.kts *.cpp *.xml *.sh *.yml
   *.md`, excluding `.git/.gradle/.cxx/build`: **zero hits** in product code. One leftover file
   (see below).
2. `export JAVA_HOME=$(/usr/libexec/java_home -v 21); ./gradlew build -x lint` → **BUILD
   SUCCESSFUL** (135 actionable tasks).
3. `cd sample && ../gradlew :app:assembleDebug -x lint` → **BUILD SUCCESSFUL**.
   `../gradlew :app:dependencies --configuration debugRuntimeClasspath | grep thuat` →
   `+--- dev.thuat:hotreload-runtime:0.1.0-SNAPSHOT -> project :android-hot-reload:hotreload-runtime`
4. `./gradlew :agent:assembleDebug` → **BUILD SUCCESSFUL**; `nm -D
   .../libhotreload_agent.so | grep Agent_OnAttach` → `T Agent_OnAttach` present;
   `strings .../libhotreload_agent.so | grep thuat` →
   `Ldev/thuat/hotreload/runtime/ComposeInvalidator;`.
5. `adb uninstall dev.thuat.hotreload.sample` (clean slate) then `e2e/run-e2e.sh` on
   emulator-5554 (API 34 x86_64) → **E2E PASS**, including the tier-1 golden path
   (`reloaded 1 class(es) ... [tier1 — remember state preserved]:
   dev.thuat.hotreload.sample.feature.GreetingKt`) and the incompatible-change rejection path
   (exit 2, `JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED`). Run twice to confirm stability
   after fixing an initial fixture-file staging issue (see below).

### Gotcha encountered and fixed

The first `sed` pass on `sample/feature/.../Greeting.kt` silently no-op'd (still unclear why —
the equivalent pattern worked on `MainActivity.kt` in the same batch), leaving the package
declaration as `dev.hotreload.sample.feature` after the `git mv`. The first E2E run still
reported `dev.thuat.hotreload.sample.feature.GreetingKt` in its output because Kotlin doesn't
require package-directory agreement, so it silently compiled the wrong package — until the
e2e script's own `cleanup()` trap ran `git checkout -- "$GREETING"` on exit, which restored the
file from the *git index* (still holding the stale, un-staged package) and reverted it back to
`dev.hotreload.sample.feature`. Fixed by editing the file again and running `git add -A` to
stage the correction *before* the final E2E run, so the cleanup trap now restores the correct
content. Re-ran the full build + sample build + E2E suite after the fix; all green, and
`git diff` after the final run shows zero drift from the staged content.

## Leftover old-string locations (intentional, not fixed)

- `docs/superpowers/specs/2026-08-01-android-hot-reload-design.md`: no `dev.hotreload`
  coordinate references exist (only unrelated `hotreload-` socket-name text) — nothing to
  change.
- `docs/superpowers/plans/2026-08-01-android-hot-reload-v1.md`: **left entirely as-is**. This
  2209-line document is a completed, historical, task-by-task implementation plan (already
  executed — every task in it describes code that now exists) written entirely in
  past/prescriptive narrative form, not a living current-state doc. Per the brief's guidance
  ("historical docs/superpowers/plans+specs may keep old strings ONLY in historical-narrative
  context"), the whole file qualifies as historical narrative, so it was left untouched rather
  than edited piecemeal. It still refers to `dev.hotreload.*` throughout (package names, plugin
  id, Maven coordinates, JNI descriptor, E2E package var) — flagging per instructions in case a
  different judgment call is wanted here.

## Commit

`refactor: move coordinates under dev.thuat`
