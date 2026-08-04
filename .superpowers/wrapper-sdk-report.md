# Bake ANDROID_HOME into the generated `hotreload` wrapper -- report

## The bug

Reported from real use against `nowinandroid`: `./gradlew hotReloadInstallCli` then `./hotreload
run` fails with `error: set ANDROID_HOME or pass --adb` unless the user's shell already exports
`ANDROID_HOME`. The generated wrapper baked in `--project`/`--package` but not the SDK location,
so the README's "three commands, nothing to export" quickstart claim was false for anyone whose
shell doesn't already have it set (common for Android Studio users, who never need to).

## The fix

**`gradle-plugin/src/main/kotlin/dev/thuat/hotreload/gradle/AndroidSdkResolution.kt`** (new) --
pure `resolve(agpSdkDir, localPropertiesFile, env)` function, priority order:
1. the `android` extension's own `sdkDirectory` (AGP's `BaseExtension.getSdkDirectory()`, a stable
   public API unchanged across AGP 8.x/9.x -- read via reflection in `HotReloadPlugin.kt`'s new
   `findAgpSdkDir`, same trick `findApplicationId` already used, so no `compileOnly` AGP
   dependency is needed just for this),
2. `sdk.dir` from the project's `local.properties`, parsed with `java.util.Properties` the same
   way AGP itself does,
3. `ANDROID_HOME` / `ANDROID_SDK_ROOT` from the environment at configuration time.

Every candidate is checked with `File.isDirectory` before being accepted, so a stale value falls
through to the next rung. Returns `null` (never throws) when nothing resolves -- the wrapper is
then generated exactly as before this fix, and the CLI's own error is what a user sees.

**`HotReloadPlugin.kt`** -- `registerInstallCliTask` now also sets `task.androidSdkDir` from a
lazy `project.provider { resolveSdkDir(root)?.absolutePath ?: "" }`, evaluated at task-execution
time (after every project is configured), same treatment as the existing `applicationId` lookup.

**`InstallCliTask.kt`** -- new `@Internal androidSdkDir: Property<String>` (best-effort, not
`@Input`: it doesn't affect what's downloaded, only what gets baked into the wrapper script,
mirroring `applicationId`). `writeWrapper` passes it through to `HotReloadWrapperScript.writeTo`.

**`HotReloadWrapperScript.kt`** -- `content`/`writeTo` gained a `sdkDir: File? = null` parameter
(default keeps every existing call site, and existing test, working unchanged). When non-null, the
generated script gains two lines right after `script_dir=...` is resolved:
```sh
: "${ANDROID_HOME:=/Users/admin/Library/Android/sdk}"
export ANDROID_HOME
```
`: "${VAR:=default}"` is the POSIX no-op-with-side-effect idiom: it sets `ANDROID_HOME` only if
unset/empty, then the separate `export` makes it visible to the CLI subprocess and everything it
spawns -- so a user's own deliberate override always wins. **Chose `export ANDROID_HOME` over a
baked-in `--adb` flag**: `Main.kt`'s `defaultAdb()` only reads `ANDROID_HOME` for the adb path when
no `--adb` is passed, but the CLI's `GradleCompiler` also spawns a Gradle Tooling API build (to
compile/dex the edited file) that inherits the CLI process's environment -- that nested build's own
AGP needs the same SDK location too, particularly in a shell where `local.properties` wasn't there
either. Exporting the env var reaches both; `--adb` would only cover the first. Header comment
updated to mention the SDK is baked in too, unconditionally (whether or not this particular run
resolved one) so the header text is identical in both cases -- only the two SDK lines differ.

**README** -- Requirements' `ANDROID_HOME` bullet now says it's only needed if the plugin couldn't
resolve the SDK itself; the "Day-to-day commands" details section mentions the SDK location is
baked in alongside project path/`applicationId`.

## Tests

`gradle-plugin/src/test/kotlin/dev/thuat/hotreload/gradle/AndroidSdkResolutionTest.kt` (new) --
priority chain: AGP wins over local.properties over env; `ANDROID_HOME` then `ANDROID_SDK_ROOT`;
null when nothing resolves to a real directory. Pure, no Gradle/AGP scaffolding needed.

`HotReloadWrapperScriptTest.kt` -- three new cases: SDK present sets `ANDROID_HOME` via the
conditional-assign form and points at the resolved path; SDK absent adds no SDK line and the two
scripts (with/without) are byte-for-byte identical apart from those two lines; the conditional-assign
form specifically (not a plain `ANDROID_HOME=...` assignment) proves a user's export is never
clobbered. All prior tests (marker/clobber guard, override precedence, blank-package fallback)
pass unchanged with the new `sdkDir` parameter defaulting to `null`.

## Verification

**1. `./gradlew build -x lint`** -- `BUILD SUCCESSFUL in 45s`, 143 tasks (10 executed after the
source changes, 133 up-to-date). `gradle-plugin:test` green, including all new tests.

**2. Real bug reproduction, outside the repo, mavenLocal.** `./gradlew publishToMavenLocal` at the
repo root (signed with the maintainer's existing key; green). Copied `sample/` into a scratch dir,
converted from the repo's `includeBuild("..")` composite-build style to a real consumer: `settings.
gradle.kts`/root `build.gradle.kts` use `mavenLocal()` first, plugin applied **once, at the root**
(coordinator mode) with `version "0.1.6"`, removed the per-module `id("dev.thuat.hotreload")` lines,
copied the repo's own `gradlew`/wrapper jar in (the `sample/` fixture has none of its own).

```
$ unset ANDROID_HOME ANDROID_SDK_ROOT && ./gradlew hotReloadInstallCli
hotReloadInstallCli: downloading https://github.com/nthuat/android-hot-reload/releases/download/v0.1.6/cli.zip
hotReloadInstallCli: ready. Run ./hotreload run (bootstrap / cycle --file ... also work).
  It has machine-specific absolute paths baked in -- add 'hotreload' to .gitignore.
```

Generated `./hotreload`:
```sh
#!/bin/sh
# Generated by hotReloadInstallCli -- DO NOT EDIT, this file is regenerated on every run.
# Plugin version: 0.1.6
#
# Thin wrapper around the CLI hotReloadInstallCli downloaded, with --project/--package
# (and, when this plugin could resolve one, ANDROID_HOME) baked in so day-to-day use
# needs no flags and no environment to export:
#   ./hotreload run                       # watch mode
#   ./hotreload bootstrap                  # re-attach after the app restarts
#   ./hotreload cycle --file path/to/File.kt
# Flags you pass win over the baked-in --project/--package (they come after on the
# command line, and the CLI keeps the last occurrence of a repeated flag), e.g.:
#   ./hotreload run --serial emulator-5554
# A baked-in ANDROID_HOME below is only set if not already exported, so a deliberate
# override in your shell always wins.
#
# This file has machine-specific absolute paths -- add it to .gitignore.
set -e
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
: "${ANDROID_HOME:=/Users/admin/Library/Android/sdk}"
export ANDROID_HOME
cmd="$1"
if [ "$#" -gt 0 ]; then shift; fi
exec "$script_dir/build/hotreload/cli/bin/cli" "$cmd" --project "<scratch consumer dir>" --package "dev.thuat.hotreload.sample" "$@"
```

Device: `R5CX51BENMM` (Samsung, Android 15). `adb -s R5CX51BENMM shell dumpsys trust` reported
`deviceLocked=0` -- unlocked, usable. Built + installed the consumer's `app-debug.apk`, launched it,
then with **`ANDROID_HOME` and `ANDROID_SDK_ROOT` explicitly unset**:

```
$ ./hotreload bootstrap --serial R5CX51BENMM
✓ reloaded 0 class(es) in 0ms:
$ ./hotreload cycle --serial R5CX51BENMM --file feature/.../Greeting.kt   # edited to "SDK Baked, $name!"
✓ reloaded 1 class(es) in 24827ms [tier1 -- remember state preserved]: dev.thuat.hotreload.sample.feature.GreetingKt
```
`uiautomator dump` on-device confirmed `text="SDK Baked, World!"` -- the reload actually took
effect, with no `ANDROID_HOME` ever exported in that shell. (The pre-existing published v0.1.6
`cli.zip` release binary still prints an em dash in its tier suffix -- fixed in source by an
earlier commit on this branch, `8cad966`, but that fix postdates the already-tagged v0.1.6 GitHub
release; unrelated to this change and will land in the binary at the next release cut.)

**3. No-SDK fallback**, same scratch consumer copied again: hid `local.properties`
(`mv local.properties local.properties.hidden`), env vars unset, regenerated:

```
$ ./gradlew hotReloadInstallCli   # succeeds -- AGP's own SDK resolution is lazy, doesn't block configuration
```
Generated wrapper has **no SDK lines at all** (confirmed by diffing against the with-SDK version --
only those two lines differ). Then:
```
$ ./hotreload bootstrap
error: set ANDROID_HOME or pass --adb
```
Exactly the CLI's original, unmodified error message.

Both scratch consumer copies deleted afterward; test app uninstalled from the device
(`adb uninstall dev.thuat.hotreload.sample`) after each phase.

**4. `e2e/run-e2e.sh`** with `ANDROID_SERIAL=R5CX51BENMM`: **E2E PASS** (golden reload path +
incompatible-change rejection, both against the repo's own `sample/`, exercising the CLI/runtime/
agent directly -- untouched by this change). Two earlier attempts on this run showed a transient
"reloaded text not visible" / "app corrupted" failure that turned out to be a **different app
(`com.example.jetcaster`) stealing foreground focus on the shared physical device mid-test** --
confirmed by `dumpsys activity activities` showing `topResumedActivity=...jetcaster...` and, after
re-launching our app, the expected `Reloaded, World!`/`Count: 2` text was there all along. Not a
regression from this change (the e2e script never touches the Gradle plugin or wrapper code at
all). Uninstalled the sample app from the device after the passing run.

**5. README** -- Requirements bullet now reads: "`ANDROID_HOME` (with `platform-tools` on it) only
if `hotReloadInstallCli` couldn't resolve your SDK location itself; the generated `./hotreload`
bakes it in when it could." Quickstart's three commands make no environment-setup claim beyond
that, which is now true for anyone AGP/`local.properties`/env can resolve an SDK for (i.e. anyone
who can already build the project at all).

## Constraints honored

Version stayed `0.1.6` everywhere (no `build.gradle.kts` touched). No publish to Central, no tags,
no releases. `~/Projects/Android/nowinandroid`, the interview demo, and `compose-samples` were
never touched -- all verification ran against throwaway copies under the scratchpad. No em dashes
introduced in any user-facing string, generated script content, or doc text (KDoc comments follow
the codebase's existing convention of using them internally, same as `HotReloadPlugin.kt`,
`InstallCliTask.kt`, etc. already did before this change).
