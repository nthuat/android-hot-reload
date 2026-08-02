# android-hot-reload

A JVMTI-based hot reload tool for Android: edit a Jetpack Compose composable's body, and see
the change on a running debug build in seconds — no full install, no activity restart. Full
design rationale and the class-redefinition constraints it works within are in the
[design spec](docs/superpowers/specs/2026-08-01-android-hot-reload-design.md).

## Status

v1: composable **body** reloads only (see the compatibility table below). Verified end-to-end
on an API 34 x86_64 emulator; see `e2e/run-e2e.sh`.

## Quickstart

1. Until artifacts are published to a Maven repo, consume this repo as a Gradle composite
   build from a checkout — clone `android-hot-reload` somewhere, then in your app project's
   `settings.gradle.kts`:
   ```kotlin
   pluginManagement {
       repositories { google(); mavenCentral(); gradlePluginPortal() }
       includeBuild("/path/to/android-hot-reload")
   }
   includeBuild("/path/to/android-hot-reload")
   ```
   (mirrors `sample/settings.gradle.kts` in this repo, which consumes the tool the same way.)
2. Apply the plugin to your app module (injects the runtime lib into debug builds):
   ```kotlin
   // app/build.gradle.kts
   plugins {
       id("dev.thuat.hotreload")
   }
   ```
3. Build, install, and launch your debug build as usual.
4. Point the CLI at your project and package, and let it watch for changes:
   ```bash
   cli/build/install/cli/bin/cli run --project /path/to/your/project --package your.app.package
   ```
   Edit a composable, save — the running app updates in place. `hotreload bootstrap` (single
   attach) and `hotreload cycle --file path/to/File.kt` (single reload) are also available for
   scripting.

## Supported / unsupported changes

| Change | Supported in v1 |
| --- | --- |
| Edit a composable function's body (text, logic, layout) | Yes |
| Edit a non-composable function's body | Yes |
| Add/remove a top-level function or method (changes class shape) | No — rejected by ART's `RedefineClasses`, exit code 2 |
| Add/remove/reorder fields | No — same restriction |
| Add/remove a class | No |
| Change a function signature | No |
| `remember`/`rememberSaveable` state in a composable **other than** the one whose file was edited | Yes, on the primary (tier-1) path — Compose group-key invalidation (`invalidateGroupsWithKey`) recomposes only the changed scope in place, the same mechanism Android Studio Live Edit uses. |
| `remember`/`rememberSaveable` state **inside** the edited composable's own file | Not preserved — that scope re-executes fresh against the new bytecode, same as Live Edit. Hoist state that must survive an edit to its *own* file to the `Activity`/`ViewModel`. |

Anything in the "No" column returns exit code 2 with a message telling you to rebuild; the CLI
never leaves the app in a corrupted state.

### Reload tiers

Each reload picks the strongest tier that succeeds, logged at tag `HotReload`:

1. **`tier1: group-key invalidation`** — recomposes only the scopes belonging to the redefined
   class's Compose group keys. Preserves `remember` state everywhere else in the composition.
2. **`tier2: whole-composition rebuild via HotReloader`** — falls back to disposing and
   rebuilding the entire composition when group-key invalidation isn't reachable. Loses all
   `remember` state, not just the edited scope's.
3. **`tier3: recreating <Activity>`** — last resort, a full `Activity.recreate()`.

Run `adb logcat -s HotReload` during a reload to see which tier actually fired.

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

`agent/src/main/cpp/include/jvmti.h` is vendored, unmodified, from the AOSP ART runtime
(originally OpenJDK's `jvmti.h`) and is licensed under the GNU General Public License v2.0
with the Classpath exception — see `agent/LICENSE-jvmti-header.md` for provenance and why
that doesn't extend to `libhotreload_agent.so` itself (interface declarations only, nothing
GPL-licensed is linked in). No overall project license file exists yet; add one before
distributing.

## Contributing

Start with the [design spec](docs/superpowers/specs/2026-08-01-android-hot-reload-design.md)
and the [implementation plan](docs/superpowers/plans/2026-08-01-android-hot-reload-v1.md) for
the module breakdown, task sequence, and deliberately deferred v1 scope.
