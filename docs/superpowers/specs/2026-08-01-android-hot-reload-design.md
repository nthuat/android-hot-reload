# Android Hot Reload — v1 Design

**Date:** 2026-08-01
**Status:** Approved
**Goal:** Open-source, editor-agnostic hot reload for Jetpack Compose on real Android devices. A working daily dev tool — reliability over feature breadth.

## Problem

Android has no good hot reload. Live Edit is limited (literals + composable bodies, Studio-only, conservative). JetBrains Compose Hot Reload is desktop-only (requires JetBrains Runtime, impossible on ART). Compose HotSwan proves the full pipeline works on stock Android but is closed-source and paid.

ART on stock devices provides everything needed: debuggable apps accept JVMTI agents (`am attach-agent`, API 26+), and JVMTI `RedefineClasses` supports method-body swaps. Compose's recomposition provides the re-render hook. This project builds an open pipeline on those primitives.

## V1 Scope

**Reloads reliably:** method-body changes to composable functions (and any other class's method bodies, which the same primitive gives for free). UI updates on device with state preserved — `remember`, ViewModel, navigation, scroll all survive on the primary reload path (group-key invalidation; see runtime component), because only code is swapped and only affected recompose scopes re-execute. Fallback paths carry progressively weaker state guarantees, and the CLI reports which path ran.

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
The daemon loop: file watcher → Gradle tooling API build of the app module's merge-dex tasks
(`mergeProjectDexDebug`/`mergeLibDexDebug`; there's no per-module target — Gradle's task graph
pulls in `compileDebugKotlin` for whichever module actually changed as an upstream dependency,
so compile errors surface regardless of which module the edit lives in) → content-hash diff of
class output dirs against a baseline captured after each cycle → extract each changed class
from AGP's already-merged dex output via D8's `--file-per-class` split → `adb push` → socket
message to agent → print result, including which reload tier fired.

Extracting from the merged dex rather than dexing each changed class in isolation is a
correctness requirement, not an optimization: a fresh, standalone `d8` invocation on just one
`.class` file mints a *different* synthetic-lambda hash (`$r8$lambda$<hash>`, emitted for e.g.
the bridge method backing every Compose composable's restart lambda) than AGP's own merge-dex
output for the exact same class — the hash depends on toolchain/build context, not just the
class bytes. `RedefineClasses` then reports the old-hash method deleted and the new-hash one
added (`JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED`), a spurious incompatible-change
rejection for an edit ART could otherwise redefine cleanly. AGP's merge-dex tasks reproduce the
exact same hash across edits (verified empirically: stable per call site, not per edit), so the
CLI instead locates the changed class inside that already-merged, already-consistent dex output
and splits just that one class back out with `--file-per-class` — a dex-to-dex repackage that
mints no new hashes, only re-emits the original bytes. v1 assumes a conventional single
top-level app module, default `:app` (override with `--app-module`), which both the compile
step and the dex-extraction step target.

### agent (C++ JVMTI, arm64 + x86_64)
Attached once via `adb shell am attach-agent`. Load path matters: SELinux blocks debuggable apps loading agents from `/data/local/tmp` on many Android versions, so the CLI pushes the .so to `/data/local/tmp` then copies it into the app's `code_cache` dir via `run-as` (same trick Android Studio uses); patch dex files travel the same route, each cycle's device filename prefixed with a content hash so a failed push/copy can never leave a stale file that gets silently redefined instead. Bootstrap pings first and skips the whole push/copy/attach sequence if the agent already responds — re-pushing `agent.so` onto an already-attached, already-`mmap`'d agent corrupts its own live code pages (reproduced on-device as a SIGSEGV in unrelated code shortly after a second bootstrap call).

The agent listens on an abstract-namespace local socket **named per package** (`hotreload-agent-<package>`, derived from `/proc/self/cmdline`) — a fixed global name would let two instrumented apps collide on one socket. On `accept()` it authenticates the peer via `SO_PEERCRED`, accepting only the app's own uid or `adb`'s daemon uid (root or shell — `adb forward`'s bridged connections arrive as adbd's own uid, not the app's, verified empirically) and rejecting everything else (some other on-device app trying to reach the socket directly), logging rejections.

A `LOAD_DEX` message carries every changed class from one edit as one batch — records separated by an ASCII Record Separator (0x1E) — not one message per class. The agent reads and resolves every class's target first; a record whose class isn't currently loaded (e.g. a `@Preview`-only Compose lambda holder — see "Not-yet-loaded class" below) is skipped rather than aborting the batch, and `RedefineClasses(n, defs)` is then called **once** for whatever did resolve, which JVMTI applies atomically: a mid-batch rejection can never leave some classes already swapped and others not (the earlier one-message-per-class design could). The reply status distinguishes real incompatibility (`RedefineClasses` rejected the bytecode — unsupported in v1) from environmental/agent-side errors (malformed payload, unreadable dex file) via a distinct status byte, so the CLI doesn't tell the user to "rebuild" for e.g. a disk hiccup; a not-yet-loaded class is neither of those — it's reported back as "skipped" in the OK reply's detail. After a successful redefine (at least one class actually redefined) it signals the runtime lib directly — an in-process JNI call to `ComposeInvalidator.reload()`, passed every redefined class's binary name in one call — no extra IPC.

### runtime (Android library, debug only)
`ComposeInvalidator.reload(binaryNames)`, called by the agent via JNI after redefinition, re-renders the UI through a three-tier fallback chain:

1. **Group-key invalidation (primary — preserves `remember` state).** The gradle-plugin enables the Compose compiler's function-key-metadata output for debug builds; at reload time the runtime maps each redefined class to its compiler-emitted key-meta annotations and calls the Compose runtime's internal `invalidateGroupsWithKey` (reflectively, probing both `HotReloader` and `Recomposer.Companion` homes). Only the affected recompose scopes re-execute; the slot table survives, so `remember` state in untouched groups is preserved. This is the same mechanism Android Studio's Live Edit uses.
2. **Whole-composition rebuild (`HotReloader.saveStateAndDispose`/`loadStateAndCompose`).** Empirically verified on device: this path disposes the entire composition and discards ALL `remember`/`rememberSaveable` slots (`rememberSaveable` restore only replays a real Activity save/restore round trip). Activity-instance and ViewModel state survive. Used only when tier 1's reflection targets are unavailable.
3. **`Activity.recreate()`** on the tracked foreground activity — full restart of the visible screen, state loss over wrong state.

The CLI reports which tier executed so the user knows what state guarantee they got.

## Data Flow

### Cycle 0 — Bootstrap

Before the first reload: gradle-plugin applied to the app, `assembleDebug` built and installed, app launched by the user. The CLI sets up `adb forward` to the agent's (per-package-named) socket and pings first; if that already succeeds (an earlier bootstrap's agent is still attached and responsive), it captures the class-output baseline and returns immediately without touching the device further. Otherwise it pushes the agent .so and copies it into `code_cache` via `run-as`, attaches it with `am attach-agent`, and pings again (retrying briefly — `attach-agent` can return before the agent has actually started listening), then captures the baseline. Only then does watching begin.

### One Reload Cycle

1. Save `.kt` → watcher debounce 100 ms
2. CLI: Gradle tooling API build of `:app:mergeProjectDexDebug` + `:app:mergeLibDexDebug`
   (pulls in `compileDebugKotlin` for whichever module actually changed, and AGP's own
   dexBuilder tasks, as upstream dependencies of those two)
3. Diff class output dirs by content hash, keyed per-module so two modules sharing a relative
   class path never collapse into one entry → changed `.class` set (excluding
   compiler-generated `$KeyMeta` metadata classes, which never need redefinition — see the
   `cli` section above)
4. Extract each changed class from AGP's merged dex output, matched by its package-qualified
   path (D8 `--file-per-class` split — not a fresh isolated dex encode; see the `cli` section
   above for why that would be wrong) → `adb push` every dex file (unique, content-hashed
   device filename per cycle) → **one** socket message carrying the whole batch to the agent
5. Agent: reads and resolves every class, then one `RedefineClasses(n, defs)` call for the
   whole batch (atomic — see the `agent` section above), result (plus the tier that fired)
   returned to CLI
6. Runtime lib: invalidate groups for redefined composables → recompose; state untouched → preserved
7. CLI prints `✓ reloaded 2 classes in 1.8s [tier1 — remember state preserved]` or the failure reason

## Error Handling

Reliability is the core goal: the tool must never leave the app in silently-wrong state.

- **Incompatible change** (new/removed method, field, changed signature, new class): JVMTI returns an error → CLI reports exactly what changed and why it's unsupported, offers one-key full rebuild + reinstall.
- **Not-yet-loaded class** (present in the baseline snapshot — i.e. it exists in the installed APK, so it already cleared the `diff.added`/`diff.removed` structural check above — but not currently loaded in the running process, e.g. a `ComposableSingletons$<File>Kt$lambda-N$1` holder the Compose compiler emits for a `@Preview`-only lambda): **skipped**, not failed. The agent resolves every record in a `LOAD_DEX` batch independently and calls `RedefineClasses(n, defs)` only for the ones that resolved; skipped descriptors are reported back in the reply detail alongside the tier. This is safe specifically because no running code can be using a class that isn't loaded — skipping it cannot desync running state, and if it's loaded later it just gets the APK's original bytes until the next full rebuild. If every record in the batch was skipped, the agent replies OK without redefining anything and without notifying the runtime (nothing changed, nothing to recompose); the CLI reports a distinct "nothing applied" outcome. Exit code is 0 either way — this is explicitly not the "Incompatible change" case above.
- **Compile error:** Gradle output surfaced as-is; app untouched; watcher keeps running.
- **Recompose failure** (internal `HotReloader` reflection fails or invalidation throws): fall back to `Activity.recreate()` on the tracked foreground activity — state loss over wrong state.
- **Device disconnect / dead agent:** CLI pings once, at `bootstrap` (attach time) — not
  before every push. A dead agent surfaces as a `DeviceError` when a later cycle's socket
  connection fails (after the compile step has already run), reported loudly to the user with
  no attempt to hide or retry through it. There is no automatic reattach in v1; recovery is a
  manual re-run of `hotreload bootstrap`. Automatic reattach is future work, not implemented.
- **Multi-device:** v1 targets one device — first found, or `--serial`.

## Testing

- **Unit (JVM, JUnit):** class-diff logic, dex packaging, socket protocol framing.
- **Integration (emulator on CI, GitHub Actions AVD):** sample Compose app lives in the repo. Scripted: boot emulator, edit a source fixture, run one cycle, assert new text visible via `uiautomator dump`. Two E2E paths: golden path (successful reload) and incompatible change (asserts clean error, no corruption).
- **Agent:** standalone JVMTI test host on device via `adb shell app_process`.

## Risks

- `HotReloader` is a Compose runtime internal API — may shift across Compose versions. Mitigation: reflective access with capability check at attach time, `Activity.recreate()` fallback always available.
- Method-body edits that introduce **new lambdas** compile to new synthetic classes on some Kotlin configurations (invokedynamic desugaring) — those surface as incompatible changes in v1 and route to the rebuild path.
- JVMTI behavior varies slightly across OEM ART builds. Mitigation: agent reports capability errors explicitly; CI covers stock emulator images for a range of API levels.
- Gradle tooling API compile latency on very large projects may exceed comfortable reload time. Mitigation: measure first; embedded compiler fast-path is a known later upgrade, not a v1 requirement.

## Future (explicitly deferred)

Structural changes via dex injection (new classes are legal — load fresh dex, redefine old bodies to call into it), literal fast-path, resource reload, IDE plugin on top of the CLI, KMP, multi-device broadcast.
