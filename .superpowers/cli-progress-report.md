# Live CLI progress output during a reload cycle

## Gap

A reload took 4-25s with zero output until the final line. `cycle`/`run` now print a phase-start
line ("compiling…", "dexing N class(es)…", "pushing…", …) as `ReloadOrchestrator.cycle()`
enters each phase, so the terminal isn't silent while the user waits.

## Design

- **`ReloadOrchestrator.kt`**: added `PhaseListener = (phase: String, classCount: Int?) -> Unit`,
  a no-op-by-default constructor parameter (`onPhase`). `cycle()` calls it right before each of
  its six timed phases (`keysnapshot`, `compile`, `diff`, `dex`, `push`, `redefine`) — the exact
  same names already used as `phaseMillis` keys. `classCount` is only populated for `dex` (size
  of `toRedefine`, known at that point). No behavior change: return value and `phaseMillis`
  contract untouched; the orchestrator still never prints anything itself. `bootstrap()` does no
  compile work and was left as-is (no progress lines) — a judgement call per the spec's own
  wording; the only thing it could show is a single "attaching…" and it usually completes in one
  ping round trip anyway.
- **`Progress.kt`** (new, thin presentation layer): pure `phaseMessage(phase, classCount)` builds
  the display text; `ProgressReporter` renders it two ways:
  - **Interactive** (`System.console() != null`, i.e. a real terminal on both stdin and stdout):
    each phase overwrites the previous one in place (`\r` + `[K`, no trailing newline).
    `clear()` erases the last line right before the final outcome line prints.
  - **Non-interactive** (piped, redirected, `$(...)` capture, CI, the e2e script): one plain line
    per phase, no cursor-control bytes at all.
  - `--progress` / `--no-progress` (new CLI flags, boolean — parsed out of `args` before the
    existing `--flag value` chunking so they don't misalign other options) override the
    auto-detection either direction. Chose `System.console()` over checking
    `System.getenv("CI")` or similar because it's the one JVM-native, dependency-free signal for
    "is a human watching this scroll by right now," and it already returns null for exactly the
    cases that matter here (piped, redirected, subshell capture).
- **`Main.kt`**: `report()`'s final-line construction for `Reloaded` was pulled out, byte-for-byte
  unchanged, into a top-level `reloadedLine()` function (was inline in `report()`) so it's
  directly unit-testable in isolation from progress rendering. `formatPhaseTimings` made
  `internal` for the same reason. `main()` builds one `ProgressReporter`, wires it into
  `ReloadOrchestrator(config, onPhase = progress::phase)`, and calls `progress.clear()` right
  before `exitWith`/`report()` on both the `cycle` and `run` (watch-loop) paths.

## Tests added

- `ProgressTest.kt` (new):
  - `PhaseMessageTest` — pins the display text for every phase name used in `phaseMillis`
    (`keysnapshot`/`compile`/`diff`/`dex`/`push`/`redefine`), the `dex` class-count suffix, and
    the fallback for an unrecognized name.
  - `ProgressReporterTest` — against a fake `PrintStream` sink (`ByteArrayOutputStream`):
    non-interactive mode emits one plain line per phase with **no** `\r` or ANSI bytes;
    interactive mode emits `\r`+text+clear-to-EOL per phase and `clear()` erases the last one.
  - `ReloadedLineTest` — pins `reloadedLine()`'s exact output (tier suffix, phase-timing suffix,
    empty-`phaseMillis` case for bootstrap, null-tier case) so a future change to progress
    rendering cannot silently alter the line `e2e/run-e2e.sh` and the README assert on verbatim.
- `ProgressWiringIntegrationTest.kt` (new): proves `onPhase` is actually invoked by the real
  `cycle()` code path (not just that `Progress.kt`'s rendering is correct in isolation) — primes
  a baseline against this repo's own `sample/` project via a real `bootstrap()`, then runs a real
  `cycle()` (real Gradle compile, no source edit) against a fake persistent ping-only agent
  socket, and asserts `onPhase` fired `["keysnapshot", "compile", "diff"]` in order before the
  real `NoChanges` outcome. Skipped via `assumeTrue` if `sample/` isn't present, same guard
  `GradleCompilerIntegrationTest` already uses.
- All pre-existing tests (`ReloadOrchestratorTest`, `MainTest`, etc.) pass unchanged — the
  `ReloadOrchestrator(config, runner)` two-arg constructor call sites are untouched since
  `onPhase` is a third, defaulted parameter.

## Verification

### 1. `./gradlew build -x lint`

```
BUILD SUCCESSFUL in 3s
149 actionable tasks: 8 executed, 141 up-to-date
```

### 2. Real device, interactive (TTY via `script -q`, R5CX51BENMM, Samsung SM-F731B / Android 15)

`adb -s R5CX51BENMM shell dumpsys trust | grep deviceLocked` → `deviceLocked=0` (unlocked)
before the run.

Raw bytes (`cat -v`), edited `sample/feature/.../Greeting.kt` "Hello" → "Reloaded":

```
^D^H^H^M[M-^_ snapshotting][K^M[M-^_ compiling][K^M[M-^_ diffing][K^M[M-^_ dexing 1 class(es)][K^M[M-^_ pushing][K^M[M-^_ redefining][K^M[K[M-\M-S reloaded 1 class(es) in 7000ms [tier1: remember state preserved]: dev.thuat.hotreload.sample.feature.GreetingKt (keysnapshot 0.0s · compile 5.6s · diff 0.0s · dex 1.0s · push 0.2s · redefine 0.1s)^M
```

Rendered as it appeared on screen (each phase overwrote the previous one in place, then the
final line landed on a clean line):

```
⟳ snapshotting…
⟳ compiling…
⟳ diffing…
⟳ dexing 1 class(es)…
⟳ pushing…
⟳ redefining…
✓ reloaded 1 class(es) in 7000ms [tier1: remember state preserved]: dev.thuat.hotreload.sample.feature.GreetingKt (keysnapshot 0.0s · compile 5.6s · diff 0.0s · dex 1.0s · push 0.2s · redefine 0.1s)
```

### 3. Same command piped (`| cat`), plain lines, final line still greppable

```
⟳ snapshotting…
⟳ compiling…
⟳ diffing…
⟳ dexing 1 class(es)…
⟳ pushing…
⟳ redefining…
✓ reloaded 1 class(es) in 8062ms [tier1: remember state preserved]: dev.thuat.hotreload.sample.feature.GreetingKt (keysnapshot 0.1s · compile 6.3s · diff 0.1s · dex 1.3s · push 0.2s · redefine 0.1s)
```

No `\r`/ANSI bytes present (confirmed with `cat -v`, no `^M` or escape sequences in this run).

### 4. `e2e/run-e2e.sh` (`ANDROID_SERIAL=R5CX51BENMM`)

```
== golden path: edit composable body, cycle, assert new text + preserved state ==
⟳ snapshotting…
⟳ compiling…
⟳ diffing…
⟳ dexing 1 class(es)…
⟳ pushing…
⟳ redefining…
✓ reloaded 1 class(es) in 5062ms [tier1: remember state preserved]: dev.thuat.hotreload.sample.feature.GreetingKt (keysnapshot 0.0s · compile 3.9s · diff 0.0s · dex 0.8s · push 0.2s · redefine 0.1s)
== incompatible path: add a function, expect exit 2 and clean error ==
⟳ snapshotting…
⟳ compiling…
⟳ diffing…
⟳ dexing 1 class(es)…
⟳ pushing…
⟳ redefining…
✗ incompatible change: RedefineClasses failed: JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED (structural changes are unsupported in v1; rebuild)
  → run a full rebuild + reinstall, then 'hotreload bootstrap' again
E2E PASS
```

The script's own `grep -q "tier1"` and UI-text assertions ran against this exact output and
passed — the new progress lines are additive and don't interfere with either assertion.
`git status --short` after the run: clean (the script's own `trap cleanup` reverted its edit, as
before).

### 5. Longer reload (Jetcaster) — attempted, inconclusive, not included as evidence

Bootstrapped against the already-running `com.example.jetcaster` (pid confirmed via `adb
shell pidof`) on the same device, consuming the published 0.1.7 plugin+runtime. Editing
`mobile/src/main/java/.../ui/home/Home.kt` and running `cycle` against it repeatedly hit a
`Daemon compilation failed` / `Incremental compilation failed: Storage for [...
source-to-classes.tab] is already registered` from Kotlin's incremental-compiler daemon — caused
by my own rapid manual `./gradlew` invocations (including a `--stop`) racing the CLI's own
Tooling API build against the same incremental caches while iterating on this. Not a defect in
the progress feature (the `sample/` evidence above shows the identical code path working
correctly end to end); not worth chasing further given it's explicitly optional. Cleaned up
afterward: `Home.kt` restored byte-for-byte to its pre-existing (already-dirty, not mine)
state, the `.hotreload/` cache dir this created was removed, and `:mobile:compileDebugKotlin`
was re-run once (after clearing the corrupted incremental cache) to confirm the checkout builds
cleanly again. `git diff --stat` on that checkout now shows only the pre-existing, not-mine
`Home.kt` modifications — nothing added or left behind by this work.

### 6. README

Added one short paragraph under "CLI details" (`README.md`, after the `--java-home` note)
describing the live progress lines, the TTY/non-TTY behavior, and the `--progress`/
`--no-progress` override. Quickstart section untouched.

## Files touched

- `cli/src/main/kotlin/dev/thuat/hotreload/cli/ReloadOrchestrator.kt` — `PhaseListener`, `onPhase`
  param, six `onPhase(...)` calls in `cycle()`.
- `cli/src/main/kotlin/dev/thuat/hotreload/cli/Main.kt` — `--progress`/`--no-progress` parsing,
  `ProgressReporter` wiring, `progress.clear()` before `cycle`/`run`'s final line,
  `reloadedLine()` extraction, `formatPhaseTimings` made `internal`, usage text.
- `cli/src/main/kotlin/dev/thuat/hotreload/cli/Progress.kt` — new, the presentation layer.
- `cli/src/test/kotlin/dev/thuat/hotreload/cli/ProgressTest.kt` — new.
- `cli/src/test/kotlin/dev/thuat/hotreload/cli/ProgressWiringIntegrationTest.kt` — new.
- `README.md` — one paragraph.
