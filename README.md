# android-hot-reload

A JVMTI-based hot reload tool for Android: edit a Jetpack Compose composable's body, and see
the change on a running debug build in seconds — no full install, no activity restart. Full
design rationale and the class-redefinition constraints it works within are in the
[design spec](docs/superpowers/specs/2026-08-01-android-hot-reload-design.md).

## Status

v1 (`v0.1.1`): composable **body** reloads only — see the compatibility table below for exactly
what is and isn't supported.

Verified end to end on both an API 34 x86_64 emulator and a physical arm64 device (Samsung
SM-F731B, Android 15), against this repo's `sample/` project and a real third-party multi-module
Compose app. Typical reload on that real app is ~4s (`compile 2.1s · dex 0.9s · push 0.3s ·
redefine 0.1s`), with `remember` state preserved outside the edited file. The automated
end-to-end test — golden path plus the incompatible-change rejection path — is `e2e/run-e2e.sh`
and runs on every push (see `.github/workflows/ci.yml`).

## Quickstart (consuming the published tool)

The Gradle plugin and the runtime library are published via [JitPack](https://jitpack.io/#nthuat/android-hot-reload)
— no need to clone or build this repo just to *use* the tool. Only the CLI (which bundles the
JVMTI agent's `.so`) needs a separate download, since it's not a Maven artifact.

1. In your app project's `settings.gradle.kts`, add JitPack to both repository blocks, plus the
   `resolutionStrategy` block that redirects the `dev.thuat.hotreload` plugin ID to its JitPack
   module — plugin markers don't survive JitPack's group remapping, so plain `id(...) version
   "..."` resolution won't find it without this:
   ```kotlin
   pluginManagement {
       repositories {
           google(); mavenCentral(); gradlePluginPortal()
           maven("https://jitpack.io")
       }
       resolutionStrategy {
           eachPlugin {
               if (requested.id.id == "dev.thuat.hotreload") {
                   useModule("com.github.nthuat.android-hot-reload:gradle-plugin:${requested.version}")
               }
           }
       }
   }
   dependencyResolutionManagement {
       repositories {
           google(); mavenCentral()
           maven("https://jitpack.io")
       }
   }
   ```
2. Apply the plugin **once, at the root project**, pinned to a released tag (see
   [Releases](../../releases) for the latest) — that's the only change a multi-module project
   needs:
   ```kotlin
   // root build.gradle.kts
   plugins {
       id("dev.thuat.hotreload") version "v0.1.1"
   }
   ```
   Applying it at the root puts the plugin in "coordinator" mode: it reacts to every subproject's
   own `com.android.application` / `com.android.library` plugin (in whichever order Gradle
   configures them) and wires each one up automatically — the application module gets the runtime
   dependency injected into `debugImplementation`, and *every* Android module (app and libraries
   alike) gets the Compose compiler's function-key metadata enabled. That last part is what makes
   tier-1 group-key reloads work for library-module composables too, not just the app module's —
   previously a module you forgot to apply the plugin to would silently fall back to tier 2
   (whole-composition rebuild, losing `remember` state).

   No further configuration is needed — the plugin derives the runtime library's coordinate from
   wherever it resolved *itself* from (same group, same version, artifact `hotreload-runtime`), so
   it finds the right JitPack coordinate automatically. (`hotreload.runtimeCoordinate.set(...)` is
   still available as a root-level override, for repository layouts the plugin can't auto-detect —
   set it once at the root and it reaches every module's injected dependency.)

   **Applying it per module still works**, if you'd rather be explicit about which modules opt in.
   Declare the version once in the root build (`apply false`) and apply it without a version in
   each module that has composables, otherwise Gradle fails with `gradle-plugin:null`:
   ```kotlin
   // root build.gradle.kts
   plugins { id("dev.thuat.hotreload") version "v0.1.1" apply false }

   // app/build.gradle.kts, feature/build.gradle.kts, … (each module with composables)
   plugins { id("dev.thuat.hotreload") }
   ```
   Forgetting a module in this style is a silent correctness trap again (see above) — applying
   once at the root avoids it entirely. Mixing both styles (root *and* some modules) is safe too;
   the plugin is idempotent, so it won't double-add the dependency or double-configure a module
   applied both ways.
3. Build, install, and launch your debug build as usual.
4. Download the CLI from the [release matching your tag](../../releases), unzip it, and point it
   at your project and package:
   ```bash
   ./bin/cli run --project /path/to/your/project --package your.app.package
   ```
   Edit a composable, save — the running app updates in place. `hotreload bootstrap` (single
   attach) and `hotreload cycle --file path/to/File.kt` (single reload) are also available for
   scripting. Requires JDK 17+ on your `PATH`/`JAVA_HOME` to run.

### Alternative: building from source (hacking on the tool itself)

If you're editing `android-hot-reload` itself, skip JitPack and build locally instead.

**mavenLocal** — publish once, consume like any other Maven dependency:
```bash
cd /path/to/android-hot-reload
export JAVA_HOME=$(/usr/libexec/java_home -v 21)  # see Requirements
./gradlew publishToMavenLocal :cli:installDist
```
`publishToMavenLocal` publishes both consumer-facing modules: the `dev.thuat.hotreload` Gradle
plugin (`gradle-plugin`) and the runtime library (`dev.thuat:hotreload-runtime`).
`:cli:installDist` builds the CLI with the JVMTI agent's `.so` files bundled inside its own
install tree, so it finds them automatically — no `--agent-so-dir` needed. Re-run both after
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
Apply the plugin the same way as the JitPack quickstart (`id("dev.thuat.hotreload") version
"0.1.0-SNAPSHOT"`), but skip the `hotreload { runtimeCoordinate.set(...) }` override — the
plugin's built-in default already points at the `dev.thuat` coordinate mavenLocal just published.

**Composite build** — if you want changes picked up without a `publishToMavenLocal` round-trip
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
every reload cycle — `./gradlew help -q` in the tool repo alone costs ~1.7–3.8s. On a real
consumer project this accounted for roughly half the `compile` phase of each reload; switching to
mavenLocal dropped median total cycle time from ~6.8s to ~4.0s (median compile 3.5s → 2.1s,
5-run samples, contended dev machine). Use the composite route only when you need source changes
to the tool itself reflected immediately; otherwise mavenLocal is faster.

## Supported / unsupported changes

| Change | Supported in v1 |
| --- | --- |
| Edit a composable function's body (text, logic, layout) | Yes |
| Edit a non-composable function's body | Yes |
| Edit a file that contains one or more `@Preview` functions | Yes — the `ComposableSingletons$<File>Kt$lambda-N$1` holder classes the Compose compiler emits for preview-only lambdas are skipped rather than failing the reload (see "Not-yet-loaded classes" below). |
| Add/remove a top-level function or method (changes class shape) | No — rejected by ART's `RedefineClasses`, exit code 2 |
| Add/remove/reorder fields | No — same restriction |
| Add/remove a class | No |
| Change a function signature | No |
| `remember`/`rememberSaveable` state in a composable **other than** the one whose file was edited | Yes, on the primary (tier-1) path — Compose group-key invalidation (`invalidateGroupsWithKey`) recomposes only the changed scope in place, the same mechanism Android Studio Live Edit uses. |
| `remember`/`rememberSaveable` state **inside** the edited composable's own file | Not preserved — that scope re-executes fresh against the new bytecode, same as Live Edit. Hoist state that must survive an edit to its *own* file to the `Activity`/`ViewModel`. |

Anything in the "No" column returns exit code 2 with a message telling you to rebuild; the CLI
never leaves the app in a corrupted state.

### Not-yet-loaded classes (e.g. `@Preview` lambda holders)

A changed class that was already in the baseline snapshot (so it's known to exist in the
installed APK — never a brand-new class; that case is still rejected above) but isn't currently
loaded in the running process is **skipped**, not treated as a failure. The most common case:
the Compose compiler emits a `ComposableSingletons$<File>Kt$lambda-N$1` holder class per
composable lambda in a file, including ones used only by `@Preview` functions — those never run
outside Android Studio/Paparazzi, so the app process never loads them, and any edit that shifts
lambda numbering in the file used to fail the *entire* reload with a bogus "rebuild" demand.

Skipping is safe: nothing running is using that class, so nothing can desync. If it's ever
loaded later (e.g. you start using that lambda for real), it loads the APK's original bytes —
stale until the next full rebuild. Exit code stays 0; the CLI prints a short extra line naming
how many classes were skipped. If *every* changed class in a cycle was skipped, the CLI prints a
distinct "nothing applied" line instead of a reload line (still exit 0 — nothing broke, nothing
changed either).

### Reload tiers

Each reload picks the strongest tier that succeeds, logged at tag `HotReload`:

1. **`tier1: group-key invalidation`** — recomposes only the scopes belonging to the redefined
   class's Compose group keys. Preserves `remember` state everywhere else in the composition.
2. **`tier2: whole-composition rebuild via HotReloader`** — falls back to disposing and
   rebuilding the entire composition when group-key invalidation isn't reachable. Loses all
   `remember` state, not just the edited scope's.
3. **`tier3: recreating <Activity>`** — last resort, a full `Activity.recreate()`.

Run `adb logcat -s HotReload` during a reload to see which tier actually fired.

### Phase timings

Every successful reload line ends with a compact per-phase breakdown — `compile`, `diff`
(baseline snapshot/diff), `dex` (splitting a changed class back out of AGP's merged dex
output), `push` (adb push + run-as copy), `redefine` (the agent round trip) — e.g.:

```
✓ reloaded 1 class(es) in 1980ms [tier1 — remember state preserved]: com.example.FooKt
  (compile 0.8s · diff 0.0s · dex 0.7s · push 0.4s · redefine 0.1s)
```

Always on, no flag needed — a slow cycle is diagnosable straight from its normal output instead
of needing to be re-measured after the fact.

## How it works

1. The Gradle plugin injects a small runtime lib (a `ContentProvider` + reflection hook into
   Compose's `HotReloader`) into your debug build.
2. The CLI watches your source, recompiles the changed file via the Gradle Tooling API, and
   diffs the resulting `.class` output against a baseline to find changed classes.
3. Changed classes are extracted from AGP's already-merged dex output (D8, `--file-per-class`)
   and pushed to the device.
4. A JVMTI agent (attached to the running app's JVM/ART via `adb shell am attach-agent`) calls
   `RedefineClasses` with the new dex bytes.
5. On success, the agent notifies the runtime lib over JNI, which asks Compose to recompose;
   incompatible changes are rejected by ART before anything touches the running app.

## Requirements

- `ANDROID_HOME`/`ANDROID_SDK_ROOT` set, with `platform-tools` on it.
- A debuggable build (JVMTI attach and `RedefineClasses` both require it).
- Device or emulator API 26+ (JVMTI `RedefineClasses` support).
- A conventional single top-level application module, default `:app` — override with
  `--app-module` if your app module has a different Gradle path.
- Building this repo locally requires JDK 21 (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`
  before any `./gradlew` call) — Gradle 8.11.1 does not support newer JDKs. CI supplies its own
  JDK 17, which works fine; this is a local-machine-only constraint.

## License

Apache-2.0 — see [`LICENSE`](LICENSE).

`agent/src/main/cpp/include/jvmti.h` is vendored, unmodified, from the AOSP ART runtime
(originally OpenJDK's `jvmti.h`) and is licensed under the GNU General Public License v2.0
with the Classpath exception — see `agent/LICENSE-jvmti-header.md` for provenance and why
that doesn't extend to `libhotreload_agent.so` itself (interface declarations only, nothing
GPL-licensed is linked in).

## Contributing

Start with the [design spec](docs/superpowers/specs/2026-08-01-android-hot-reload-design.md)
and the [implementation plan](docs/superpowers/plans/2026-08-01-android-hot-reload-v1.md) for
the module breakdown, task sequence, and deliberately deferred v1 scope.
