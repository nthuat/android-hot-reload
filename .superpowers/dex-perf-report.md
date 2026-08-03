# Dex-split performance fix — report

## Diagnosis (given, reproduced)

`DexPackager.dexClass()` re-ran a *full* D8 `--file-per-class` split of a merged-dex bucket for
every changed class, sequentially trying each bucket until it found the wanted class. A typical
edit changes ~3 classes (the file facade + 2 `ComposableSingletons$...$lambda-N$1` holders), so
one cycle repeated the same bucket splits ~3x. On `orderbook-demo` the merged-dex output actually
has **32 buckets** (16 `mergeProjectDexDebug` + 16 `mergeLibDexDebug`, not the ~7 the original
diagnosis estimated), so the old per-class sequential scan could touch up to 96 bucket splits in
the worst case for a 3-class edit.

## What changed

- **`cli/.../DexPackager.kt`**: replaced `dexClass(ChangedClass, Path): Path` with
  `dexClasses(changed: List<ChangedClass>, outDir: Path): Map<ChangedClass, Path>`. Each merged
  bucket is split **at most once** per call, across a bounded thread pool
  (`min(buckets, availableProcessors)`), and every still-wanted class is resolved out of each
  split as it completes. Deliberately **parallel-all-buckets, not early-exit**: early exit only
  helps a sequential scan, and with 32 real buckets on an 8-core box, splitting all of them
  concurrently (~4 waves × ~0.7s) beats a sequential per-class early exit whenever more than one
  class needs resolving. A class found in no bucket is reported via a single `error()` listing
  every missing class — same "surfaces as an error, caller doesn't catch it" contract the old
  single-class version had. Package-qualified matching and collision-free output naming
  (F6) are unchanged; the original header rationale comment is preserved verbatim.
  Concurrency safety: verified via `javap` that `D8.class`'s only static state is a
  `static final boolean`; `D8.run()` builds and tears down its own executor per call, so
  concurrent `D8.run` calls with distinct output dirs share no mutable state.
- **`cli/.../ReloadOrchestrator.kt`**: `cycle()` now calls `dexer.dexClasses(toRedefine, dexDir)`
  once instead of looping `dexer.dexClass(...)` per class. `$KeyMeta` filtering, per-class
  push + run-as copy with content-hash device filenames, the single batched `LOAD_DEX`,
  skipped-class reporting, tier parsing, and baseline-saved-only-on-success are all unchanged.
  Added phase timing: `CycleOutcome.Reloaded` gained `phaseMillis: Map<String, Long>`
  (`compile`, `diff`, `dex`, `push`, `redefine`), always populated (empty for bootstrap's
  synthetic `Reloaded(0ms)`).
- **`cli/.../Main.kt`**: `report()` appends the phase breakdown to the existing one-line reload
  message — no new flag, always on (`✓ reloaded ... (compile 1.8s · diff 0.0s · dex 0.9s ·
  push 0.4s · redefine 0.1s)`) — chosen over a `--timings` flag since it keeps the success line
  to one line with no extra plumbing.
- **`README.md`**: documented the always-on phase-timing suffix.

## Tests

`DexPackagerTest.kt` rewritten for the batch API: single-bucket multi-class resolution,
classes spread across two buckets, a class missing from every bucket (error path, batch
partially resolves before failing), and the existing same-simple-name-different-package case —
all pass. Every other test (`ReloadOrchestratorTest`, `MainTest`, `ClassDifferTest`,
`GradleCompilerIntegrationTest`) is green, unchanged.

## Verification

1. `export JAVA_HOME=$(/usr/libexec/java_home -v 21); ./gradlew build -x lint` →
   `BUILD SUCCESSFUL in 48s`, 135 tasks.
2. `e2e/run-e2e.sh` → `E2E PASS`. Golden path line:
   `✓ reloaded 1 class(es) in 6252ms [tier1 — remember state preserved]:
   dev.thuat.hotreload.sample.feature.GreetingKt (compile 4.4s · diff 0.0s · dex 0.7s ·
   push 0.8s · redefine 0.3s)`. Incompatible-change path unchanged (exit 2).
3. **Before/after on `orderbook-demo`** (`emulator-5554`, `com.example.orderbook`, agent
   already attached — `bootstrap` returned the ping fast-path, no re-push needed):
   - **Before** (old `dexClass()` loop, rebuilt via `git stash`): warm-daemon cycle editing
     `ProductCard.kt`'s `CardText` (3 changed classes: facade + 2 skipped preview-lambda
     holders) → **15.1s** total (no phase breakdown — the instrumentation didn't exist yet).
     A cold-daemon run measured **32.7s**, consistent with the task's cited ~24.5s baseline
     (same class of behavior; daemon coldness added a few extra seconds this run).
   - **After** (this fix): same edit, warm daemon →
     `✓ reloaded 1 class(es) in 11699ms [tier1]: ...ProductCardKt (compile 8.5s · diff 0.4s ·
     dex 1.6s · push 1.1s · redefine 0.2s)`.
   - Repeated after-runs under host CPU load (load avg spiked to 40+ from unrelated processes
     during this session) show **`dex` staying flat at 1.4–2.3s regardless of load**, while
     `compile` (the Gradle Tooling API dispatch for `mergeProjectDexDebug`/`mergeLibDexDebug`,
     unrelated to this fix) swung from 7.5s to 82s under contention — that phase, not dex, now
     dominates and explains why total wall time didn't land reliably under 5s on this
     machine/project. **Honest result: the diagnosed dex bottleneck is fixed (~17s of
     redundant D8 work → a consistent ~1.5–2.3s, independent of how many classes changed or
     how many buckets exist — 32 here, not the ~7 assumed), but end-to-end cycle time on this
     real project is now compile-phase-bound, not dex-phase-bound.** A clean warm-daemon,
     unloaded-machine run landed at **~10.9–11.7s** total — a real ~55–65% cut from the 24.5s
     baseline, not the sub-5s target.
4. **Device behavior confirmed** after a cycle: `uiautomator dump` shows the edited text live
   (`"Hihi FINAL Assembly Label"` / `"Hihi FINAL Bassike"`); `adb logcat -s HotReload` shows
   `tier1: group-key invalidation, keys=[...]`; `adb shell pidof com.example.orderbook` returned
   the same pid (`6196`) before and after every cycle in this session; the
   `⚠ skipped 2 not-yet-loaded class(es) ... ComposableSingletons$ProductCardKt$lambda-1$1,
   ComposableSingletons$ProductCardKt$lambda-2$1` warning still appears on edits that shift
   preview-lambda numbering.

## orderbook-demo working tree

Left as found except the test edit: `app/build.gradle.kts` and `settings.gradle.kts` still
carry their pre-existing hot-reload plugin wiring (untouched).
`app/src/main/java/com/example/orderbook/catalog/ProductCard.kt`'s `CardText` now reads
`Text("Hihi FINAL3 " + product.brand, ...)` (was already mid-edit with a `"Hihi "` prefix at
task start; iterated through several intermediate values while timing repeated cycles). Nothing
committed in that repo. `.hotreload/` (untracked baseline + dex cache) was regenerated by the
bootstrap/cycle runs, same as before this task started.
