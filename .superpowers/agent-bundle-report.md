# Agent-bundle report: bundle agent libs in the CLI distribution

## Defect

`Main.kt`'s default `--agent-so-dir` resolved relative to the process's CWD. That only worked
when the CLI happened to be launched from the tool checkout; the documented workflow (README
quickstart) runs it from the *consumer* project dir, so every consumer's first `bootstrap`/`run`
failed with `agent .so ... not found` and had to be worked around with a manual
`--agent-so-dir`.

## Fix

1. **Bundle**: `cli/build.gradle.kts` now copies
   `agent/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/<abi>/libhotreload_agent.so`
   into `<installDir>/agent/<abi>/...` via the `application` plugin's `distributions.main.contents`
   CopySpec, with `installDist`/`distZip`/`distTar` wired to `dependsOn(":agent:assembleDebug")`.

2. **Locate at runtime**: the CLI's generated start script (`startScripts` task) now has a
   `doLast` that inserts `export HOTRELOAD_HOME="$APP_HOME"` (Unix) / `set HOTRELOAD_HOME=%APP_HOME%`
   (Windows) ahead of `DEFAULT_JVM_OPTS`. Note this is a **plain env var**, not a
   `-Dhotreload.home=...` JVM system property as originally planned — that was tried first and
   confirmed broken: Gradle's Unix start-script template runs `DEFAULT_JVM_OPTS`/`JAVA_OPTS`
   through an `xargs | sed | eval` pipeline that backslash-escapes every `$` (anti shell-injection
   hardening), so a JVM arg referencing `$APP_HOME` comes out the other end as the four literal
   characters `$APP_HOME` instead of an expanded path. The env-var export sidesteps that pipeline
   entirely and was verified working (see evidence below).

   `Main.kt` extracts the resolution into a pure function:
   ```kotlin
   internal fun resolveAgentSoDir(explicit: String?, homeEnv: String?, cwd: Path): Path = when {
       explicit != null -> Paths.get(explicit)
       homeEnv != null -> Paths.get(homeEnv).resolve("agent")
       else -> cwd.resolve("agent/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib")
   }
   ```
   called with `System.getenv("HOTRELOAD_HOME")`. `--agent-so-dir` always wins; CWD-relative
   fallback keeps `gradle run` from a dev checkout working unchanged.

3. **Error message**: `ReloadOrchestrator`'s DeviceError now names both fixes in two lines:
   ```
   agent .so for abi '$abi' not found at $so
     → rebuild the tool with ./gradlew :cli:installDist, or pass --agent-so-dir <dir>
   ```

4. **Docs**: README quickstart step 1 now runs `./gradlew publishToMavenLocal :cli:installDist`
   and states installDist bundles the agent so no `--agent-so-dir` is needed. No other doc
   referenced running the CLI from the tool checkout.

## Tests

Added `ResolveAgentSoDirTest` (3 cases: `HOTRELOAD_HOME` set → `<home>/agent`; absent →
CWD-relative fallback; explicit `--agent-so-dir` wins over both) to
`cli/src/test/kotlin/dev/thuat/hotreload/cli/MainTest.kt`. All pre-existing tests left green.

## Verification

**1. Root build green**
```
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew build -x lint
```
→ `BUILD SUCCESSFUL in 2s` (135 actionable tasks, 10 executed / 125 up-to-date on the final run).

**2. `:cli:installDist` — agent libs present for both ABIs**
```
$ ls cli/build/install/cli/agent/arm64-v8a/ cli/build/install/cli/agent/x86_64/
cli/build/install/cli/agent/arm64-v8a/:
libhotreload_agent.so

cli/build/install/cli/agent/x86_64/:
libhotreload_agent.so
```
Also confirmed `./gradlew :cli:installDist --dry-run` shows `installDist` pulling in the full
`:agent:assembleDebug` task chain (mergeDebugNativeLibs etc.) — the dependency wiring is real, not
coincidental.

**3. e2e — ran against the physical device, not the emulator**

The AVD (`Pixel_3a_API_34_extension_level_7_x86_64`) was not running; a physical device
(`R5CX51BENMM`, Samsung SM-F731B, Android 15, arm64-v8a) was attached instead.
`e2e/run-e2e.sh` has no `--serial` flag, but every `adb`/CLI call in it goes through plain `adb`
(no explicit `-s`), so it honors `ANDROID_SERIAL`:
```
export ANDROID_HOME=~/Library/Android/sdk JAVA_HOME=$(/usr/libexec/java_home -v 21) ANDROID_SERIAL=R5CX51BENMM
./e2e/run-e2e.sh
```
→ golden path reloaded `dev.thuat.hotreload.sample.feature.GreetingKt` at tier1, incompatible-change
path correctly exited 2 → `E2E PASS`. (This run uses the sample app's own package, distinct from
the orderbook-demo consumer app also running on the same device — no interference.)

**4. The actual repro, fixed — from the CONSUMER project dir, no `--agent-so-dir`**

Relaunched the orderbook-demo app fresh first (`am force-stop` + `am start`) to get a clean
bootstrap, new pid `359` (previous pid was `32009`):
```
cd /Users/admin/Projects/Interview/Mobile/demos/orderbook-demo
export ANDROID_HOME=~/Library/Android/sdk JAVA_HOME=$(/usr/libexec/java_home -v 21)
/Users/admin/Projects/Ideas/android-hot-reload/cli/build/install/cli/bin/cli bootstrap \
  --project "$PWD" --package com.example.orderbook --serial R5CX51BENMM
# ✓ reloaded 0 class(es) in 0ms:

/Users/admin/Projects/Ideas/android-hot-reload/cli/build/install/cli/bin/cli cycle \
  --project "$PWD" --package com.example.orderbook --serial R5CX51BENMM \
  --file "$PWD/app/src/main/java/com/example/orderbook/catalog/ProductCard.kt"
# ✓ reloaded 1 class(es) in 9582ms [tier1 — remember state preserved]: com.example.orderbook.catalog.ProductCardKt
#   (compile 7.5s · diff 0.2s · dex 1.3s · push 0.5s · redefine 0.1s)
#   ⚠ skipped 2 not-yet-loaded class(es), ...: ComposableSingletons$ProductCardKt$lambda-1$1, ...$lambda-2$1
```
Both succeeded with `tier1`, zero manual agent path — confirms the fix. (Before the env-var
correction below, the first attempt at this repro reproduced the JVM-arg escaping bug: the
DeviceError printed the literal, unexpanded string `$APP_HOME` — that's what led to switching
from a `-Dhotreload.home=...` system property to the `HOTRELOAD_HOME` env var export.)

**5. On-device UI + pid stability**
```
$ adb -s R5CX51BENMM shell pidof com.example.orderbook
359    # before cycle
359    # after cycle — unchanged, confirms live in-process redefinition, not a restart

$ adb -s R5CX51BENMM shell uiautomator dump /sdcard/x.xml && adb -s R5CX51BENMM shell cat /sdcard/x.xml | grep -o 'text="[^"]*"' | sort -u
...
text="AGENTBUNDLE Assembly Label"
text="AGENTBUNDLE Bassike"
text="AGENTBUNDLE Country Road"
text="AGENTBUNDLE Nike"
...
```
UI reflects the edited `CardText`'s new `"AGENTBUNDLE "` prefix (previous on-disk text was
`"PHONE3 "`, from an earlier session's uncommitted edit; last-committed value in the consumer's
own git history was `"Run15 "`).

## Consumer project (orderbook-demo) — what's modified

Per instructions, left as found apart from the test edit. `git status --porcelain` (Mobile repo)
shows:
- `app/src/main/java/com/example/orderbook/catalog/ProductCard.kt` — **my edit**: changed the
  `CardText` label prefix from `"PHONE3 "` (already on disk, uncommitted, before this session) to
  `"AGENTBUNDLE "`, to have a distinguishable marker for the on-device UI check.
- `.hotreload/baseline.txt` and `.hotreload/dex/*.dex` — expected tool-state cache files, updated
  by the `bootstrap`/`cycle` runs above, not manually touched.
- `app/build.gradle.kts` and `settings.gradle.kts` — **already modified before this session
  started** (not touched by this task); left untouched.

Nothing was committed in the consumer project.

## Tool repo — commit

```
e79134e fix(cli): bundle agent libs in the distribution and resolve them from APP_HOME
```
Pushed to `origin/main` (`0a262dc..e79134e`), so CI runs.

Files changed: `README.md`, `cli/build.gradle.kts`,
`cli/src/main/kotlin/dev/thuat/hotreload/cli/Main.kt`,
`cli/src/main/kotlin/dev/thuat/hotreload/cli/ReloadOrchestrator.kt`,
`cli/src/test/kotlin/dev/thuat/hotreload/cli/MainTest.kt`.
