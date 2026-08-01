# Android Hot Reload — v1 Design

**Date:** 2026-08-01
**Status:** Approved
**Goal:** Open-source, editor-agnostic hot reload for Jetpack Compose on real Android devices. A working daily dev tool — reliability over feature breadth.

## Problem

Android has no good hot reload. Live Edit is limited (literals + composable bodies, Studio-only, conservative). JetBrains Compose Hot Reload is desktop-only (requires JetBrains Runtime, impossible on ART). Compose HotSwan proves the full pipeline works on stock Android but is closed-source and paid.

ART on stock devices provides everything needed: debuggable apps accept JVMTI agents (`am attach-agent`, API 26+), and JVMTI `RedefineClasses` supports method-body swaps. Compose's recomposition provides the re-render hook. This project builds an open pipeline on those primitives.

## V1 Scope

**Reloads reliably:** method-body changes to composable functions (and any other class's method bodies, which the same primitive gives for free). UI updates on device with state preserved — `remember`, ViewModel, navigation, scroll all survive because only code is swapped, never the object graph.

**Target projects:** Android-only Compose apps, including large multi-module builds and mixed Views+Compose codebases. Only Compose UI re-renders automatically; changed non-composable classes still get body-swapped but Views don't refresh themselves.

**Non-goals for v1:** structural changes (new/removed methods, fields, classes), resource reloading, literal fast-path, IDE plugin, KMP targets, multi-device broadcast, non-debuggable builds.

## Approach Decision

Three candidates considered for delivering swapped code to the running app:

- **A. JVMTI agent over ADB (chosen)** — proven path (Live Edit and HotSwan use the same primitive), zero overhead in app code, works on stock devices. Costs: native agent (.so), body-only redefinition rules, debuggable builds only.
- **B. Compiler-plugin indirection** — rejected: writing and maintaining a Kotlin compiler plugin against the moving Compose compiler is the hardest possible subsystem, adds runtime overhead to every build, fragile across Kotlin versions.
- **C. JDWP piggyback** — rejected: fights the real debugger session, undocumented, flaky.

Compilation uses Gradle's own incremental compile via the tooling API rather than an embedded compiler daemon. ~1–4s reload instead of sub-second, but correct classpaths and compiler-plugin config come free in multi-module projects. A fast path can be added later if daily use demands it.

## Architecture

Four components in one repo (Gradle multi-project):

```
┌─────────────┐  watches .kt saves
│ hotreload-  │  runs Gradle compileDebugKotlin (tooling API)
│ cli (JVM)   │  diffs .class output → dex changed classes (d8)
└──────┬──────┘
       │ adb push dex + adb forward socket
       ▼
┌─────────────┐        ┌──────────────────┐
│ device      │        │ app (debuggable) │
│             │ attach │ ┌──────────────┐ │
│ agent.so ───┼──agent─▶ │ JVMTI: Redefine│ │
│             │        │ │ Classes       │ │
│             │        │ ├──────────────┤ │
│             │        │ │ runtime lib: │ │
│             │        │ │ recompose    │ │
│             │        │ └──────────────┘ │
└─────────────┘        └──────────────────┘
```

### gradle-plugin
Applied to the app's build. Debug variant only: adds the `runtime` library dependency. Zero effect on release builds.

### cli (Kotlin JVM)
The daemon loop: file watcher → Gradle tooling API incremental compile of the changed module → content-hash diff of class output dirs against a baseline captured after each cycle → `d8` changed classes into `patch.dex` → `adb push` → socket message to agent → print result.

### agent (C++ JVMTI, arm64 + x86_64)
Attached once via `adb shell am attach-agent`. Listens on a LocalServerSocket (reached via `adb forward`). Receives dex, calls `RedefineClasses` for body-compatible changes, reports per-class success/failure back to the CLI.

### runtime (Android library, debug only)
After successful redefinition, triggers recomposition via Compose runtime's `invalidateGroupsWithKey` internal API. Opt-in fallback: full `Activity.recreate()`.

## Data Flow — One Reload Cycle

1. Save `.kt` → watcher debounce 100 ms
2. CLI: Gradle tooling API `:module:compileDebugKotlin` (incremental — only the changed module compiles)
3. Diff class output dirs by content hash → changed `.class` set
4. `d8` → `patch.dex` → `adb push` → socket message to agent
5. Agent: `RedefineClasses`, per-class result returned to CLI
6. Runtime lib: invalidate groups for redefined composables → recompose; state untouched → preserved
7. CLI prints `✓ reloaded 2 classes in 1.8s` or the failure reason

## Error Handling

Reliability is the core goal: the tool must never leave the app in silently-wrong state.

- **Incompatible change** (new/removed method, field, changed signature, new class): JVMTI returns an error → CLI reports exactly what changed and why it's unsupported, offers one-key full rebuild + reinstall.
- **Compile error:** Gradle output surfaced as-is; app untouched; watcher keeps running.
- **Compose group mismatch** (an edit that shifts group keys risks a recompose crash): conservative rule — if a redefined class's composable structure changed (detected via synthetic-member / group-key diff), fall back to `Activity.recreate()` instead of group invalidation.
- **Device disconnect / dead agent:** CLI health-check ping before each push; one automatic reattach attempt, then surface to user.
- **Multi-device:** v1 targets one device — first found, or `--serial`.

## Testing

- **Unit (JVM, JUnit):** class-diff logic, dex packaging, socket protocol framing.
- **Integration (emulator on CI, GitHub Actions AVD):** sample Compose app lives in the repo. Scripted: boot emulator, edit a source fixture, run one cycle, assert new text visible via `uiautomator dump`. Two E2E paths: golden path (successful reload) and incompatible change (asserts clean error, no corruption).
- **Agent:** standalone JVMTI test host on device via `adb shell app_process`.

## Risks

- `invalidateGroupsWithKey` is a Compose runtime internal API — may shift across Compose versions. Mitigation: version-check in runtime lib, `Activity.recreate()` fallback always available.
- JVMTI behavior varies slightly across OEM ART builds. Mitigation: agent reports capability errors explicitly; CI covers stock emulator images for a range of API levels.
- Gradle tooling API compile latency on very large projects may exceed comfortable reload time. Mitigation: measure first; embedded compiler fast-path is a known later upgrade, not a v1 requirement.

## Future (explicitly deferred)

Structural changes via dex injection (new classes are legal — load fresh dex, redefine old bodies to call into it), literal fast-path, resource reload, IDE plugin on top of the CLI, KMP, multi-device broadcast.
