# android-hot-reload

[![Maven Central](https://img.shields.io/maven-central/v/dev.thuat/hotreload-runtime?label=maven%20central)](https://central.sonatype.com/artifact/dev.thuat/hotreload-runtime)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**Hot reload for Jetpack Compose on real Android devices.** Edit a composable, save, and the
running app updates in place — no reinstall, no activity restart, `remember` state preserved.

```
✓ reloaded 1 class(es) in 1980ms [tier1 — remember state preserved]: com.example.FooKt
  (compile 0.8s · diff 0.0s · dex 0.7s · push 0.4s · redefine 0.1s)
```

It works by redefining classes in the running process with a JVMTI agent, then asking Compose to
recompose only the affected scopes — the same group-key mechanism Android Studio's Live Edit
uses. Unlike Live Edit it runs from any editor, and unlike JetBrains' Compose Hot Reload it works
on Android rather than desktop JVM.

## Quickstart

**1. Apply the plugin at your root project** — that's the only Gradle change, even for
multi-module builds:

```kotlin
// root build.gradle.kts
plugins { id("dev.thuat.hotreload") version "0.1.2" }
```

**2. Install the CLI:**

```bash
curl -fsSL https://raw.githubusercontent.com/nthuat/android-hot-reload/main/install.sh | sh
```

**3. Build, install and launch your debug build as usual**, then:

```bash
hotreload run --project . --package your.app.package
```

Edit a composable, hit save. That's it.

<details>
<summary><b>What applying at the root actually does</b></summary>

The plugin reacts to each subproject's own `com.android.application` / `com.android.library`
plugin and wires it up: the application module gets the runtime library injected into
`debugImplementation`, and *every* Android module — app and libraries alike — gets the Compose
compiler's function-key metadata enabled.

That last part matters. Function-key metadata is what makes tier-1 (state-preserving) reloads
work; a module missing it silently degrades to tier 2, rebuilding the whole composition and
losing `remember` state. Applying once at the root means you can't forget a module.

The runtime library's coordinate is derived from wherever the plugin resolved itself from, so
there's nothing to configure. `hotreload.runtimeCoordinate.set(...)` is available at the root as
an override for repository layouts it can't auto-detect.

**Per-module application still works** if you'd rather opt modules in explicitly — declare the
version once in the root with `apply false`, then apply it without a version in each module that
has composables (omitting the version fails with `gradle-plugin:null`):

```kotlin
// root build.gradle.kts
plugins { id("dev.thuat.hotreload") version "0.1.2" apply false }

// app/build.gradle.kts, feature/build.gradle.kts, … (each module with composables)
plugins { id("dev.thuat.hotreload") }
```

Mixing both styles is safe — the plugin is idempotent and won't double-configure a module.
</details>

<details>
<summary><b>CLI details and other install methods</b></summary>

`install.sh` puts the latest release in `~/.local/share/hotreload/<version>/` and symlinks
`~/.local/bin/hotreload`. Re-run it to upgrade. Pin a version with
`HOTRELOAD_VERSION=v0.1.2 curl ... | sh`.

Besides `run` (watch mode), the CLI has `bootstrap` (attach once) and `cycle --file path/to/File.kt`
(reload once) for scripting. Requires JDK 17+ on `PATH`/`JAVA_HOME`. The 17.7 MB download bundles
the JVMTI agent for `arm64-v8a` and `x86_64`, so there's no `--agent-so-dir` to set.

**Gradle task** — `./gradlew hotReloadInstallCli` downloads the release matching this project's
plugin version, so the CLI can't drift from the plugin, and unpacks it to `build/hotreload/cli/`.
Requires plugin **0.1.3+**; not in the published `0.1.2`.

**Manual** — grab `cli.zip` from the [latest release](../../releases) and unzip it.
</details>

## Status

`0.1.2` — composable **body** reloads. See [Supported / unsupported changes](#supported--unsupported-changes)
for the exact boundary.

Verified end to end on an API 34 x86_64 emulator and a physical arm64 device (Samsung SM-F731B,
Android 15), against this repo's `sample/` project and a real third-party multi-module Compose
app, where a typical reload is ~4s. `e2e/run-e2e.sh` covers the golden path and the
incompatible-change rejection path, and runs on every push.

## Supported / unsupported changes

| Change | Reloads? |
| --- | :---: |
| Edit a composable function's body | ✅ |
| Edit a non-composable function's body | ✅ |
| Edit a file containing `@Preview` functions | ✅ |
| Add or remove a function, method, or field | ❌ |
| Change a function signature | ❌ |
| Add or remove a class | ❌ |

The ❌ rows are ART's `RedefineClasses` restrictions, not ours — class *shape* can't change. Those
exit with code 2 and a message telling you to rebuild; the app keeps running its old code rather
than ending up half-swapped.

**`remember` state** survives a reload everywhere except inside the edited file itself, whose
scopes re-execute fresh against the new bytecode. This matches Live Edit. If some state must
survive edits to its own file, hoist it to a `ViewModel` or the `Activity`.

<details>
<summary><b>Not-yet-loaded classes (e.g. `@Preview` lambda holders)</b></summary>

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
</details>

<details>
<summary><b>Reload tiers</b></summary>

Each reload picks the strongest tier that succeeds, logged at tag `HotReload`:

1. **`tier1: group-key invalidation`** — recomposes only the scopes belonging to the redefined
   class's Compose group keys. Preserves `remember` state everywhere else in the composition.
2. **`tier2: whole-composition rebuild via HotReloader`** — falls back to disposing and
   rebuilding the entire composition when group-key invalidation isn't reachable. Loses all
   `remember` state, not just the edited scope's.
3. **`tier3: recreating <Activity>`** — last resort, a full `Activity.recreate()`.

Run `adb logcat -s HotReload` during a reload to see which tier actually fired.
</details>

<details>
<summary><b>Phase timings</b></summary>

Every successful reload line ends with a compact per-phase breakdown — `compile`, `diff`
(baseline snapshot/diff), `dex` (splitting a changed class back out of AGP's merged dex
output), `push` (adb push + run-as copy), `redefine` (the agent round trip) — e.g.:

```
✓ reloaded 1 class(es) in 1980ms [tier1 — remember state preserved]: com.example.FooKt
  (compile 0.8s · diff 0.0s · dex 0.7s · push 0.4s · redefine 0.1s)
```

Always on, no flag needed — a slow cycle is diagnosable straight from its normal output instead
of needing to be re-measured after the fact.
</details>

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

<details>
<summary><b>Building from source / consuming a local build</b></summary>

If you're editing `android-hot-reload` itself, consume it locally instead of from Maven Central.

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
Apply the plugin the same way as the quickstart (`id("dev.thuat.hotreload") version
"0.1.2"`), but skip the `hotreload { runtimeCoordinate.set(...) }` override — the plugin's
built-in default already points at the `dev.thuat` coordinate mavenLocal just published.

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
</details>


Start with the [design spec](docs/superpowers/specs/2026-08-01-android-hot-reload-design.md)
and the [implementation plan](docs/superpowers/plans/2026-08-01-android-hot-reload-v1.md) for
the module breakdown, task sequence, and deliberately deferred v1 scope.
