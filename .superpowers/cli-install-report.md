# CLI install usability — report

## What shipped

**A. `hotReloadInstallCli` Gradle task** (`gradle-plugin/src/main/kotlin/dev/thuat/hotreload/gradle/`)
- `CliInstallSupport.kt` — pure functions: `releaseTag` (adds the `v` prefix), `downloadUrl`,
  `versionFromCoordinate` (extracts the version from `defaultRuntimeCoordinate`'s
  `group:artifact:version` string).
- `InstallCliTask.kt` — downloads `cli.zip` for the plugin's own resolved version (via
  `HotReloadPlugin.defaultRuntimeCoordinate`, so it reuses `RuntimeCoordinateDerivation` and
  inherits its composite-build fallback + `info` log), unpacks to `build/hotreload/cli/`
  (stdlib `java.net.http.HttpClient` + `java.util.zip`, no new dependency), sets `bin/cli`
  executable, and prints the ready-to-paste `run` command with the resolved `--project` and
  (best-effort, via reflection so no AGP compile dependency is needed) `--package`.
  Idempotent two ways: normal Gradle `@Input`/`@OutputDirectory` up-to-date checking, plus an
  in-action version-marker check that also skips re-downloading on a forced `--rerun-tasks`.
  Comment on the class explicitly says why it must never be wired up to *run* the reload
  (Tooling API build would deadlock against the daemon holding this task's own build lock).
- `HotReloadPlugin.kt` — registers the task on `project.rootProject`, guarded so it only
  registers once no matter how many projects apply the plugin (coordinator, per-module, or both
  — `sample/` uses per-module and still gets the task on its root).

**B. `install.sh`** (repo root) — POSIX `sh`, `set -eu`, no bashisms, passed `shellcheck -s sh`
clean. Resolves latest release (or `HOTRELOAD_VERSION` pin, with/without `v` prefix), downloads
`cli.zip`, verifies it (non-empty, `unzip -t` integrity, `bin/cli` entry present — no checksum
file is published today, noted in a comment), installs to
`~/.local/share/hotreload/<version>/`, symlinks `~/.local/bin/hotreload`, warns if that dir isn't
on `PATH` (with the exact line to add) and if `java` is missing or older than 17 (handles both
`"1.8.0_x"` and `"17.0.x"`/bare `"21"` version-string shapes).

**README** — quickstart step 3 now leads with the Gradle task, then the install script, manual
download kept as a one-line fallback.

## Tests / verification

1. **`./gradlew build -x lint`** — green (`JAVA_HOME=$(/usr/libexec/java_home -v 21)`).
2. **Unit tests** — `CliInstallSupportTest` (tag/URL/version mapping, pure, no network) and two
   new cases in `HotReloadPluginCoordinatorTest` (task registers exactly once across
   coordinator+per-module apply combinations; registers on root even when the plugin is never
   applied at root at all). All pass.
3. **Task against the real release** — copied `sample/` to a scratch dir outside the repo,
   converted it to root-only coordinator-mode apply, Maven Central-only repos, no `includeBuild`.
   **Deviation, disclosed**: the *published* `dev.thuat:gradle-plugin:0.1.2` on Central predates
   this feature (can't publish a new one — out of scope per the brief), so the scratch project
   resolved the plugin via `mavenLocal()` after a local `./gradlew publishToMavenLocal` of this
   session's build (same version number, not pushed anywhere). The **CLI zip download itself hit
   the real, live `v0.1.2` GitHub release asset**, unaffected by that. Ran
   `./gradlew hotReloadInstallCli`: downloaded `https://github.com/nthuat/android-hot-reload/releases/download/v0.1.2/cli.zip`,
   unpacked to `build/hotreload/cli/`, printed
   `./build/hotreload/cli/bin/cli run --project <abs path> --package dev.thuat.hotreload.sample`
   (applicationId correctly resolved from the app module). `build/hotreload/cli/bin/cli` executed
   (printed usage, exit 64 — expected for no args). Re-run showed `UP-TO-DATE`; `--rerun-tasks`
   re-run hit the in-action marker and skipped the re-download. Scratch dir deleted afterward.
   Left behind: `~/.m2/repository/dev/thuat/{gradle-plugin,hotreload-runtime}/0.1.2/` from the
   local publish — harmless, but delete it (`rm -rf ~/.m2/repository/dev/thuat`) if you don't want
   a locally-published 0.1.2 sitting in mavenLocal.
4. **`install.sh` for real** — ran it against my own `$HOME`. Created:
   `~/.local/share/hotreload/0.1.2/` (unpacked CLI dist) and symlink `~/.local/bin/hotreload` →
   `~/.local/share/hotreload/0.1.2/bin/cli`. `hotreload` resolved via `which` and ran (usage
   message, exit 64). Re-ran: printed "already installed... skipping download", symlink
   re-pointed identically, no duplicate version dirs. Also exercised in isolation: `PATH` without
   `~/.local/bin` → correct warning with the exact export line; a faked old `java` (`1.8.0_392`) →
   correct too-old warning; faked `17.0.9` and bare `21` → correctly silent. Both
   `HOTRELOAD_VERSION=v0.1.2` and `HOTRELOAD_VERSION=0.1.2` pin correctly. Remove
   `~/.local/share/hotreload` and the `~/.local/bin/hotreload` symlink if you don't want them kept.
5. **`e2e/run-e2e.sh`** — physical device `R5CX51BENMM` checked first
   (`dumpsys trust | grep deviceLocked` → `deviceLocked=0`, unlocked), so it ran on that device
   rather than falling back to the emulator. **E2E PASS**: golden path reloaded tier-1
   (`GreetingKt`, 4366ms, `remember` state preserved, both CLI output and logcat agree on
   `tier1`), incompatible-change path correctly rejected with exit 2 and left the app un-corrupted.
6. README updated as described above.

## Files touched
- `gradle-plugin/src/main/kotlin/dev/thuat/hotreload/gradle/CliInstallSupport.kt` (new)
- `gradle-plugin/src/main/kotlin/dev/thuat/hotreload/gradle/InstallCliTask.kt` (new)
- `gradle-plugin/src/main/kotlin/dev/thuat/hotreload/gradle/HotReloadPlugin.kt`
- `gradle-plugin/src/test/kotlin/dev/thuat/hotreload/gradle/CliInstallSupportTest.kt` (new)
- `gradle-plugin/src/test/kotlin/dev/thuat/hotreload/gradle/HotReloadPluginCoordinatorTest.kt`
- `install.sh` (new)
- `README.md`

## Commits (pushed to `main`)
- `eabfc56` feat(gradle-plugin): add hotReloadInstallCli task
- `ae8e59c` feat: add install.sh for a project-independent CLI install
- `8e50963` docs: quickstart leads with the Gradle task and install script
