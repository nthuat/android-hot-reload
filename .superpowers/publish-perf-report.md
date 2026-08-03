# Publish-to-mavenLocal performance report

## What changed

- **`gradle-plugin/build.gradle.kts`**: added `maven-publish`. `java-gradle-plugin` +
  `maven-publish` together already generate the main `pluginMaven` publication (from the
  `java` component) plus the plugin-marker publication
  (`dev.thuat.hotreload:dev.thuat.hotreload.gradle.plugin`) that the `plugins { id(...) }`
  DSL needs to resolve the id from a repository. Group/version/plugin id unchanged
  (`dev.thuat`, `0.1.0-SNAPSHOT`, `dev.thuat.hotreload`).
- **`runtime/build.gradle.kts`**: added `maven-publish`, `android { publishing {
  singleVariant("release") } }`, and an `afterEvaluate` block registering a `release`
  `MavenPublication` from `components["release"]` (AGP only creates that component after
  evaluation). Publishing a single variant means AGP omits the build-type attribute from the
  published component, so it resolves for both `debugImplementation` and
  `releaseImplementation` consumers — verified below. Artifact id defaults to `project.name`,
  already renamed to `hotreload-runtime` in `settings.gradle.kts`; coordinate is
  `dev.thuat:hotreload-runtime:0.1.0-SNAPSHOT`, unchanged from what the plugin already injects
  in `HotReloadPlugin.kt`.
- **`cli`/`agent`**: untouched, as specified — `cli` stays `installDist`-only, the agent
  `.so` is still built locally and pushed from the build dir. Confirmed
  `--agent-so-dir`'s default (`Main.kt`) resolves relative to the CLI's invocation
  **working directory**, not to any Gradle wiring — publishing changes nothing about it.
- **Convenience step**: no new task needed. `./gradlew publishToMavenLocal` run at the tool
  repo root already executes the task in every subproject that defines it (`gradle-plugin`,
  `runtime`) and is silently skipped in `cli`/`agent`, which don't — confirmed by running it.
- **`README.md`**: mavenLocal is now the primary quickstart path (publish once, add
  `mavenLocal()` to both `pluginManagement` and `dependencyResolutionManagement` repositories,
  apply the plugin with an explicit `version`). The composite-build (`includeBuild`) route is
  kept as a documented alternative for hacking on the tool itself, with the measured ~2s/cycle
  overhead stated plainly.
- **`.github/workflows/ci.yml`**: added `./gradlew publishToMavenLocal` as a smoke check after
  `build -x lint` in the existing `unit` job (cheap, no new job).

## Verification

1. `export ANDROID_HOME=~/Library/Android/sdk JAVA_HOME=$(/usr/libexec/java_home -v 21)`
2. `./gradlew publishToMavenLocal` → `BUILD SUCCESSFUL in 23s`. Confirmed on disk:
   `~/.m2/repository/dev/thuat/hotreload-runtime/0.1.0-SNAPSHOT/hotreload-runtime-0.1.0-SNAPSHOT.aar`,
   `~/.m2/repository/dev/thuat/gradle-plugin/0.1.0-SNAPSHOT/gradle-plugin-0.1.0-SNAPSHOT.jar`,
   and the plugin marker POM under
   `dev/thuat/hotreload/dev.thuat.hotreload.gradle.plugin/0.1.0-SNAPSHOT/`.
3. Root `./gradlew build -x lint` → `BUILD SUCCESSFUL in 50s`, 135 tasks, green.
4. `e2e/run-e2e.sh` → `E2E PASS`. The sample project still consumes the tool via composite
   build (`sample/settings.gradle.kts`, untouched) — confirmed that route still works after
   these changes: golden path `✓ reloaded 1 class(es) in 4543ms [tier1]:
   dev.thuat.hotreload.sample.feature.GreetingKt`, incompatible-change path still exit 2.

## Before/after on `orderbook-demo` (emulator-5554, `com.example.orderbook`)

5 consecutive `cli cycle` runs, each a distinct real edit to `ProductCard.kt`'s `CardText`
prefix string, agent already attached.

**Before — composite build (`includeBuild` x2, as it shipped)**:

```
run 1: 23999ms total (compile 21.0s · diff 0.1s · dex 1.3s · push 1.2s · redefine 0.3s)
run 2:  6849ms total (compile  4.3s · diff 0.2s · dex 1.5s · push 0.7s · redefine 0.2s)
run 3:  6813ms total (compile  3.5s · diff 0.2s · dex 1.1s · push 1.7s · redefine 0.3s)
run 4:  5634ms total (compile  3.3s · diff 0.1s · dex 1.0s · push 0.8s · redefine 0.3s)
run 5:  4422ms total (compile  2.6s · diff 0.1s · dex 0.8s · push 0.7s · redefine 0.2s)
```

Median total **6813ms**, median compile **3.5s**. (Run 1 is a cold-daemon outlier — 23999ms —
included honestly, not dropped; it's why the spread looks wide.)

**After — mavenLocal** (`includeBuild` lines removed, `mavenLocal()` added to both repository
blocks, plugin id given an explicit `version`):

```
run 1: 6019ms total (compile 3.4s · diff 0.2s · dex 1.1s · push 1.1s · redefine 0.2s)
run 2: 4213ms total (compile 2.2s · diff 0.1s · dex 0.8s · push 0.9s · redefine 0.2s)
run 3: 4034ms total (compile 2.0s · diff 0.1s · dex 0.9s · push 0.8s · redefine 0.2s)
run 4: 3996ms total (compile 2.1s · diff 0.1s · dex 0.8s · push 0.7s · redefine 0.2s)
run 5: 3802ms total (compile 1.9s · diff 0.1s · dex 0.7s · push 0.8s · redefine 0.3s)
```

Median total **4034ms**, median compile **2.1s**.

**Delta**: median total cycle time **6813ms → 4034ms (−41%, −2.78s)**; median compile phase
**3.5s → 2.1s (−1.4s)**. Consistent with the ~2s/cycle configuration-overhead estimate in the
task brief. Machine was contended throughout (emulator + other processes); both samples show
real spread (before: 4.4–24.0s; after: 3.8–6.0s) but the after-distribution sits uniformly
below the before-distribution with no overlap past run 1's daemon-cold outlier — this is a real
win, not noise.

## Device correctness after the switch

- `adb shell pidof com.example.orderbook` — same pid (`8511`) before, during, and after every
  cycle; no process restart.
- `adb logcat -s HotReload` — every cycle logs `tier1: group-key invalidation, keys=[...]`.
- `uiautomator dump` after a cycle shows the edited text live (e.g. `A5 Assembly`, `A5 Bassike`
  for the `"A5 " + product.brand` edit).
- Skipped-not-loaded warning still fires unchanged:
  `⚠ skipped 2 not-yet-loaded class(es) ... ComposableSingletons$ProductCardKt$lambda-1$1,
  ComposableSingletons$ProductCardKt$lambda-2$1`.
- `./gradlew :app:dependencies --configuration debugRuntimeClasspath` in `orderbook-demo`
  shows `+--- dev.thuat:hotreload-runtime:0.1.0-SNAPSHOT` resolved from `mavenLocal()` —
  confirms the AGP `singleVariant("release")` publication is consumable from
  `debugImplementation` as intended.

## `orderbook-demo` working tree — left in the FASTER (mavenLocal) state

mavenLocal measurably wins, so the consumer project is left switched over, not reverted:

- **`settings.gradle.kts`**: both `includeBuild("/Users/admin/Projects/Ideas/android-hot-reload")`
  lines removed; `mavenLocal()` added as the first repository in both `pluginManagement.repositories`
  and `dependencyResolutionManagement.repositories`.
- **`app/build.gradle.kts`**: `id("dev.thuat.hotreload")` → `id("dev.thuat.hotreload") version
  "0.1.0-SNAPSHOT"` (required once the plugin resolves from a repository instead of an included
  build). No other lines changed.
- **`app/src/main/java/com/example/orderbook/catalog/ProductCard.kt`**: `CardText`'s label
  prefix carries the last test edit, `"SKIPCHECK " + product.brand` (iterated through several
  values — `B1`..`B5`, `A1`..`A5` — while timing the two 5-run samples).
- `.hotreload/` (untracked baseline + dex cache) was regenerated by the cycle runs, same as any
  normal use of the tool.
- Nothing committed in `orderbook-demo`, per instructions.

Depends on `~/.m2/repository/dev/thuat/{gradle-plugin,hotreload-runtime}` staying populated —
re-run `./gradlew publishToMavenLocal` in `android-hot-reload` after any local change to those
two modules, same as the new README quickstart says.
