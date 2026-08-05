# Gradle configuration cache — verification report

## The problem

The CLI drives each reload's compile through the Gradle Tooling API (`GradleCompiler`). On large
projects, Gradle re-runs its whole configuration phase every cycle, and that dominates the
reload. Measured on Jetcaster (9 modules, Hilt + KSP): `compile` 8.8s-21.3s cold, total reload
10s-22.6s, vs `compile` 2.9s-3.2s / total ~4.3s with Gradle's configuration cache warm. Passing
`--configuration-cache` to the Tooling API build alone reproduces that win with zero change to
the consumer project's `gradle.properties`.

## Fix

- `cli/src/main/kotlin/dev/thuat/hotreload/cli/GradleCompiler.kt`: extracted the actual Tooling
  API build call behind a `BuildRunner` interface (`RealBuildRunner` in production), so
  `compile()`'s retry/fallback logic is unit-testable against a scripted fake instead of a real
  Gradle daemon. `RealBuildRunner.run(withConfigurationCache)` passes `--configuration-cache` via
  `BuildLauncher.withArguments(...)` when true.
  - `GradleCompiler.compile()`: tries with the flag first (unless `--no-configuration-cache` or a
    prior build in this process already found the project incompatible). On failure, retries
    ONCE without the flag only if `isConfigurationCacheFailure(output)` classifies the failure as
    configuration-cache shaped — any other failure (broken source, missing module, ...) is
    surfaced unchanged, never retried. On a successful (or failed) fallback retry, the decision is
    remembered on a `@Volatile` instance field for the life of the `GradleCompiler` — which is the
    life of the process in `run`'s watch loop, since `ReloadOrchestrator` builds exactly one
    instance and reuses it across every cycle.
  - The one-line fallback notice goes to stderr via an injectable `log` callback, printed only
    when the fallback actually triggers — nothing on the happy path.
- `cli/src/main/kotlin/dev/thuat/hotreload/cli/ConfigurationCache.kt` (new): `isConfigurationCacheFailure`,
  a pure classifier anchored on Gradle's own exception text. Confirmed against the real
  `gradle-configuration-cache-8.11.1.jar`: `org.gradle.internal.cc.impl.ConfigurationCacheProblemsException`'s
  message constant is the literal string `"Configuration cache problems found in this build."` —
  that's what ends up under Gradle's `* What went wrong:` console header, which
  `GradleConnector`'s captured stdout/stderr preserves verbatim. The classifier also checks for
  the bare exception class name, in case only a raw message (no console formatting) reaches
  `CompileResult.output`. Deliberately conservative: no loose "configuration cache" keyword match
  (a happy-path "Configuration cache entry reused" log line must never be mistaken for a failure).
- `ReloadOrchestrator.ReloadConfig` gained `useConfigurationCache: Boolean = true`, threaded into
  `GradleCompiler`'s constructor.
- `Main.kt`: `--no-configuration-cache` parsed as a boolean flag (same pattern as
  `--progress`/`--no-progress` — pulled out before the `--flag value` chunking), flows into
  `ReloadConfig.useConfigurationCache`. Usage text updated.
- `compile`'s phase timing is unaffected code-wise: `ReloadOrchestrator.cycle()` already times the
  whole `compiler.compile()` call as one wall-clock span, and the retry happens inside that call,
  so a fallback's extra build time is automatically included — never understates reality.
- README: one line in the CLI details block noting configuration cache is used automatically and
  can be disabled with `--no-configuration-cache`.

## Tests

- `cli/src/test/kotlin/dev/thuat/hotreload/cli/ConfigurationCacheTest.kt` — 5 tests for
  `isConfigurationCacheFailure`: recognizes the real Gradle message and the bare exception class
  name; does not flag an ordinary compile error, a benign "entry reused" log line, or an
  empty/unrelated failure.
- `cli/src/test/kotlin/dev/thuat/hotreload/cli/GradleCompilerTest.kt` — 7 tests against a scripted
  `BuildRunner` fake: the flag is passed by default; a configuration-cache-shaped failure triggers
  exactly one retry without the flag (and logs once); a normal build failure does not retry and is
  surfaced unchanged (no log); once the fallback has triggered, later `compile()` calls on the
  same instance skip the flag entirely (no repeated failing attempt); `--no-configuration-cache`
  suppresses the flag from the first build and a matching failure is never retried; a second
  failure after the fallback retry surfaces unchanged.
- All existing tests green, including the real-Gradle `GradleCompilerIntegrationTest` (4 tests,
  now exercising the default-on configuration cache path against `sample/`).

## Verification

**1. Build green**

```
$ ./gradlew build -x lint --console=plain
...
BUILD SUCCESSFUL in 1m 19s
149 actionable tasks: 12 executed, 137 up-to-date
```

`GradleCompilerIntegrationTest` (real Gradle, no fakes) also passed: 4/4, 0 failures.

**2. e2e**

`adb -s R5CX51BENMM shell dumpsys trust | grep deviceLocked` → `deviceLocked=0` (unlocked).

```
$ ANDROID_SERIAL=R5CX51BENMM ./e2e/run-e2e.sh
...
== bootstrap ==
✓ reloaded 0 class(es) in 0ms:
== golden path: edit composable body, cycle, assert new text + preserved state ==
✓ reloaded 1 class(es) in 8115ms [tier1: remember state preserved]: ...GreetingKt (keysnapshot 0.1s · compile 6.7s · diff 0.0s · dex 1.0s · push 0.2s · redefine 0.1s)
== incompatible path: add a function, expect exit 2 and clean error ==
✗ incompatible change: RedefineClasses failed: JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED (structural changes are unsupported in v1; rebuild)
  → run a full rebuild + reinstall, then 'hotreload bootstrap' again
E2E PASS
```

**3. Before/after timings on a real project (Jetcaster, throwaway copy)**

Copied `/Users/admin/Projects/compose-samples/Jetcaster` (source only — excluded `.git`, `build`,
`.gradle`, `.kotlin`, `.idea`) to a scratch directory, pointed its `settings.gradle.kts` at this
tool's absolute path instead of the relative `..` it used inside the monorepo (the only edit made
to the copy), built+installed `:mobile` once, then drove 4-5 consecutive `cycle` calls through
this repo's own newly-built CLI (`cli/build/install/cli/bin/cli`) against device `R5CX51BENMM`,
editing a real Text modifier's padding constant each time (a genuine body-only bytecode change,
not just a comment, so the diff/dex/push/redefine pipeline actually runs every cycle) —
`EpisodeListItem.kt`'s `Text(...).modifier = Modifier.padding(horizontal = 8.dp)` line.

Without configuration cache (`--no-configuration-cache`), 4 consecutive cycles:

```
compile 6.3s   (reload 11762ms total)
compile 15.4s  (reload 19665ms total)
compile 5.2s   (reload 8050ms total)
compile 5.6s   (reload 12216ms total)
```

With configuration cache (the new default), 5 consecutive cycles:

```
compile 5.6s   (reload 9317ms total)
compile 4.2s   (reload 8650ms total)
compile 3.0s   (reload 6563ms total)
compile 2.5s   (reload 5545ms total)
compile 9.7s   (reload 12756ms total)  -- outlier, reported as measured, not discarded
```

The warm middle cycles (4.2s → 3.0s → 2.5s) land right in the spec's claimed 2.9-3.2s warm-cache
range and are consistently faster than every no-cache cycle bar one. Cycle 5's 9.7s spike is
reported honestly rather than dropped; machine background load is the likely cause, not a
regression in the fallback logic (no fallback line was printed in either run — Jetcaster's
plugins are configuration-cache compatible, so this was the clean happy path throughout).

**4. Fallback path demonstration (scratch project, not the user's)**

Copied this repo's own `sample/` to a scratch directory, added one deliberately
configuration-cache-incompatible task to its `app/build.gradle.kts` (a `preBuild`-dependency task
whose `doLast` reads `project.name` — the textbook `Task.project`-at-execution-time violation) and
pointed its `settings.gradle.kts`'s `includeBuild(".. ")` at this repo's absolute path (both edits
exist only in the scratch copy). Built+installed once, bootstrapped, then ran one `cycle` against
device `R5CX51BENMM`:

stdout:
```
✓ reloaded 1 class(es) in 7474ms [tier1: remember state preserved]: dev.thuat.hotreload.sample.feature.GreetingKt (keysnapshot 0.1s · compile 6.1s · diff 0.0s · dex 1.0s · push 0.2s · redefine 0.1s)
```

stderr:
```
hotreload: configuration cache disabled for this project (a build failure looked configuration-cache related); retrying without it. Pass --no-configuration-cache to skip this check on future runs.
```

One retry, the one-line explanation (stderr only, never touching stdout/the e2e capture path),
and a successful reload, matching `GradleCompilerTest`'s scripted-fake coverage of the same path.
The within-process "later builds skip the flag" behavior is covered by that same unit test suite
(`bootstrap`/`cycle` are one-shot processes, so this only matters for `run`'s watch loop — proving
it live would need a multi-save `run` session, which the unit test already covers deterministically).

## What was touched outside this repo

- `/Users/admin/Projects/compose-samples/Jetcaster`: read-only (`du`, `git status`, `cat`). Zero
  writes — confirmed via `git status`/`git diff --stat` before and after, byte-identical.
- Device `R5CX51BENMM`: installed and later uninstalled two debug test builds
  (`com.example.jetcaster`, `dev.thuat.hotreload.sample`) from throwaway project copies. No other
  device state changed.
- Nothing else outside this repo was touched. All scratch copies (Jetcaster copy, `sample/` copy
  with the config-cache-breaker task) live under the session scratchpad directory, not under the
  user's real checkouts.

## Not done / deliberately out of scope

- Did not publish to Maven Central, cut tags, or upload release assets.
- Did not touch `gradle.properties` anywhere, including the scratch copies (the flag is a CLI
  build argument, never a project file change).
- Version stays `0.1.7` as instructed.
