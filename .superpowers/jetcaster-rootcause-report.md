# Jetcaster tier1 false-success — root cause report

## Status

**Root cause proven with direct evidence.** The tier report was dishonest: `ComposeInvalidator`
claimed `tier1: remember state preserved` for a reload where the specific key belonging to the
edited composable's class had just thrown an exception during invalidation. Fixed in
`runtime/src/main/kotlin/dev/thuat/hotreload/runtime/ComposeInvalidator.kt` (+ a unit-tested pure
helper). The exact JVM condition that makes `invalidateGroupsWithKey` throw for some keys and not
others was **not** pinned down — see "What wasn't nailed down" — but that gap does not weaken the
fix: any thrown exception is now treated as proof the batch cannot honestly be tier1, regardless of
why it was thrown.

## Reproduction and the decisive probe

Confirmed the task's repro on device `R5CX51BENMM` (unlocked, `deviceLocked=0` throughout): the
running app (`com.example.jetcaster`, pid 17224) kept rendering the literal string `"Hehe"` in the
`HomeAppBar`'s `SearchBar` placeholder after the CLI reported `tier1` for an edit that changed it to
`"HOME-RELOAD-OK"`. `uiautomator dump` located the stale text precisely: a `TextView` inside the
search bar's `content-desc="Tìm kiếm"` region, i.e. `HomeAppBar`'s `placeholder = { Text(...) }`
lambda in `mobile/.../ui/home/Home.kt`.

Per the task's own decisive-experiment framing, instrumented that exact lambda with
`android.util.Log.i("HOTRELOAD_PROBE", "new body running")` instead of guessing from pixels. First
attempt (against the original pid 17224, using the CLI's existing `HOTRELOAD_DEBUG_KEYS=1` env var,
already shipped in commit `a146f23`, to see the actual keys the CLI computed):

```
DEBUG com.example.jetcaster.ui.home.ComposableSingletons$HomeKt: old=[...11 keys...] new=[same] union=[same]
DEBUG com.example.jetcaster.ui.home.HomeKt: old=[...30 keys...] new=[same] union=[same]
✓ reloaded 11 class(es) ... [tier1: remember state preserved]: ...
```

`adb logcat -s HotReload HotReloadAgent`, same cycle:

```
W HotReload: invalidateGroupsWithKey(201647209) failed: InvocationTargetException: null
W HotReload: invalidateGroupsWithKey(680707003) failed: InvocationTargetException: null
... (11 lines total, ALL 11 keys belonging to ComposableSingletons$HomeKt)
W HotReload: invalidateGroupsWithKey(645563762) failed: InvocationTargetException: null
W HotReload: invalidateGroupsWithKey(855486526) failed: InvocationTargetException: null
W HotReload: invalidateGroupsWithKey(-1160944919) failed: InvocationTargetException: null
W HotReload: invalidateGroupsWithKey(1024247919) failed: InvocationTargetException: null
... (4 more, out of HomeKt's 30 keys)
I HotReload: tier1: group-key invalidation, keys=[...all 41 keys...]
I HotReloadAgent: LOAD_DEX: ...ComposableSingletons$HomeKt;, ...HomeKt;: redefined | tier1
```

**15 of 41 keys threw `InvocationTargetException` — including every single one of
`ComposableSingletons$HomeKt`'s 11 keys**, which is exactly the class holding the edited
`placeholder` lambda (a non-capturing composable lambda; Compose hoists it into a
`ComposableSingletons$<File>Kt` singleton holder — confirmed via `javap`/dexdump on the built app).
The `HOTRELOAD_PROBE` log line never appeared. `tier1` was still reported, because the pre-fix
`invalidateGroupsWithKeys` returned `true` the moment **any** key call in the whole batch didn't
throw (26 of `HomeKt`'s own keys happened not to) — it never checked whether the specific class the
user edited actually invalidated cleanly.

This directly answers the task's probe question: **the redefined method body was never entered.**
Not a rendering/recomposition problem — the recompose scope for that composable was never
triggered to re-run at all, because its invalidation calls failed and the failure was swallowed as
a per-key warning instead of affecting the reported outcome.

## Why this shows up on Jetcaster and not the sample/JetNews (so far)

Confirmed keys are stable across the literal-text/padding-value/modifier-chain edits actually
tried here (`old == new` in every `HOTRELOAD_DEBUG_KEYS` dump above) — consistent with the prior
investigation (`jetcaster-noop-report.md`). So this is not a key-renumbering bug; the keys sent
were the right ones. The failure is `invalidateGroupsWithKey` itself throwing for a subset of an
otherwise-correct key set.

## What wasn't nailed down

`InvocationTargetException.message` is `null` (Java reflection wraps the real cause, and
`ComposeInvalidator`'s pre-fix logging only printed the wrapper's own message, never
`t.cause`), so the underlying reason a subset of keys throw is not identified. Two attempts to get
the real cause:

- **jdb attach** (`adb forward tcp:9999 jdwp:17224` + `jdb -attach`): had to abandon this — jdb's
  default *global* uncaught-exception breakpoint (armed automatically on attach, separate from the
  explicit `catch InvocationTargetException` set here) stopped an unrelated agent thread on an
  *expected* `ClassNotFoundException` from `keysForClass`'s own candidate-probing (by design,
  caught internally) and wedged the agent's socket handling (a `cycle` timed out at 15s while
  suspended). Disarmed it (`ignore uncaught java.lang.Throwable`) and detached; the app recovered
  (pid unchanged, responsive). Not pursued further given the disruption risk to a device the user
  is actively working on.
- **Instrumented local runtime build**: republished a locally-patched `hotreload-runtime:0.1.6` to
  `mavenLocal` (temporarily prepended to Jetcaster's `settings.gradle.kts` repos, reverted after)
  and reran the exact edit that had thrown. It **did not reproduce** — 0 of 41 keys threw, clean
  tier1, probe fired, UI updated correctly. Tried the same edit twice more, and tried the
  cross-file ordering from the original repro (`EpisodeListItem.kt` first, then `Home.kt`, matching
  the task's steps 4→5) on the same fresh process — still clean every time. The one variable that
  differs between "it threw" and "it didn't" is that the original pid (17224) had already been
  through the user's own two prior cycles (plus, unavoidably, whatever `am attach-agent`/JVMTI
  state five cycles and one `jdb` attach/detach leaves behind) before the throwing cycle, while
  every reproduction attempt here started from a freshly bootstrapped process. This narrows the
  candidate space (state that accumulates in the JVMTI-instrumented process across repeated
  `RedefineClasses` calls — Compose's own `Recomposer`/`CompositionImpl` internals, most likely,
  since that's what `invalidateGroupsWithKey` walks) without proving it; reported as evidence, not
  fact, per this task's own instruction not to guess.

The fix below does not depend on resolving this: it treats *any* thrown exception as disqualifying
for tier1, independent of why it was thrown.

## The fix

`runtime/src/main/kotlin/dev/thuat/hotreload/runtime/ComposeInvalidator.kt`: extracted the
per-key invalidation loop into a top-level `invalidateAll(keys, invalidate, onFailure): Boolean`
(no Android imports, so it's a plain-JVM unit test target — see below). Changed the semantics from
**any** key succeeding (the bug) to **all** keys succeeding:

```kotlin
internal fun invalidateAll(keys: List<Int>, invalidate: (Int) -> Unit, onFailure: (Int, Throwable) -> Unit): Boolean {
    var failures = 0
    for (key in keys) {
        try {
            invalidate(key)
        } catch (t: Throwable) {
            failures++
            onFailure(key, t)
        }
    }
    return failures == 0
}
```

`invalidateGroupsWithKeys` now delegates to this and returns its result unchanged. `reload()`'s
three-tier chain is untouched otherwise: if this returns `false`, it falls through to tier2 (whole-
composition rebuild via `HotReloader` — loses `remember` state but unconditionally re-executes
every composable, so it cannot leave stale bytecode on screen the way the false tier1 did) instead
of reporting a success that wasn't verified. A clean call (no exception) still isn't *proof* of a
real invalidation — `invalidateGroupsWithKey` returns `Unit` whether it matched a live group or
matched nothing — that residual gap is called out in the updated doc comment and is unchanged from
before; only the newly-demonstrated failure mode (a **thrown** exception, which the API's `Unit`
return can never produce on a genuine no-op) is what this fix closes.

## Tests

`runtime/src/test/kotlin/dev/thuat/hotreload/runtime/ComposeInvalidatorTest.kt` (new; also added
`testImplementation(libs.junit4)` / `kotlin("test")` to `runtime/build.gradle.kts` — the module had
no test source set before). 6 cases, including one built directly from the real repro's ratio (15
throwing keys out of 41, matching the actual logcat capture above) to assert the fix's behavior
against the exact scenario that was observed, not just a synthetic one:

- all keys succeed → `true`
- one key throwing → `false` (and the failure callback receives exactly that key)
- 15-of-41 throwing (the Jetcaster repro's own ratio) → `false`
- every key throwing → `false`
- empty key list → `true` (vacuous; `reload()`'s caller already guards on non-empty, but the
  helper itself must not misreport an empty batch)
- the failure callback receives the actual thrown `Throwable`, not a wrapped/lossy copy

`./gradlew :hotreload-runtime:testDebugUnitTest` — 6/6 pass.

## Verification

**Jetcaster, before the fix (real repro, evidence above):** `tier1` reported for a batch where the
edited class's own keys all threw; probe never fired; UI stayed on the pre-edit text.

**Jetcaster, after the fix, live device:** Published the fixed runtime to `mavenLocal`, temporarily
pointed Jetcaster's `settings.gradle.kts` at it (reverted after), rebuilt, reinstalled (fresh pid),
bootstrapped, and ran real cycles with the *exact* same edit (`Home.kt`'s `HomeAppBar` placeholder)
plus the same `HOTRELOAD_PROBE` instrumentation, including the cross-file ordering from the
original repro:

```
I HotReload: tier1: group-key invalidation, keys=[...41 keys, all clean...]
I HotReloadAgent: LOAD_DEX: ...ComposableSingletons$HomeKt;, ...HomeKt;: redefined | tier1
I HOTRELOAD_PROBE: new body running v4-FIXED
```

`uiautomator dump` confirmed the on-screen text actually changed (`"HOME-RELOAD-OK-v4-FIXED"`) —
repeated across 4 separate cycles (different edits, different orderings), all clean, all correct.
As noted above, none of these reproduced the original throw, so this demonstrates "the fix doesn't
regress the working path," not "the fix flips a live false-positive" — the unit test using the
repro's own 15/41 ratio is what directly proves the fix's behavior on the failure itself.

**No regression:**
- `./gradlew build -x lint`: green.
- `e2e/run-e2e.sh` (`ANDROID_SERIAL=R5CX51BENMM`, confirmed `deviceLocked=0` beforehand): `E2E PASS`
  — golden tier1 path and the incompatible-change path (`JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED`,
  exit 2) both correct. The sample app consumes the runtime via `includeBuild("..")`, so this run
  used the fixed `ComposeInvalidator` directly.
- JetNews: temporarily re-pointed its already-present `mavenLocal()`-first setup (from a prior,
  unrelated investigation session — untouched by this one except for a version bump reverted after)
  at the fixed runtime, rebuilt, reinstalled, bootstrapped, edited `PostCardTop.kt`, cycled:
  `tier1: group-key invalidation`, real UI change confirmed via `uiautomator dump`
  (`"✅ TIER1-FIXED-RUNTIME Android Studio Team"`), no exceptions. Reverted the version pin and
  `mavenLocal` publish, rebuilt/reinstalled from the reverted source afterward.

## What was touched

- **This repo**: `runtime/build.gradle.kts` (test deps), `runtime/src/main/kotlin/.../ComposeInvalidator.kt`
  (the fix), `runtime/src/test/kotlin/.../ComposeInvalidatorTest.kt` (new). Nothing else — did not
  touch, build on, commit, or revert the other session's uncommitted work
  (`AppModuleHint.kt`, `ReloadOrchestrator.kt`, `HotReloadPlugin.kt`, `HotReloadWrapperScript.kt`,
  `InstallCliTask.kt` + tests); verified via `git diff --stat` before finishing that only the three
  `runtime/` paths above are mine.
- **Jetcaster checkout**: left `Home.kt` / `EpisodeListItem.kt` / `settings.gradle.kts` exactly as
  found (byte-identical `git diff` to the start of this session, verified) — used, edited further
  for diagnosis, then reverted back to the user's own `RELOAD-OK` / `HOME-RELOAD-OK` edits.
  Rebuilt + reinstalled from that reverted source at the end (real Maven Central `v0.1.6` runtime,
  not the diagnostic local one), regenerated `.hotreload` and the `hotreload` wrapper via
  `./gradlew hotReloadInstallCli`, and bootstrapped — device now shows the user's own edits
  correctly and is ready for the next `cycle`.
- **JetNews checkout**: `PostCardTop.kt` and `build.gradle.kts` reverted to their pre-existing
  (already-uncommitted, from a prior session) state; `.hotreload` regenerated fresh; `~/.m2`'s
  temporary `0.1.6` publish removed (the pre-existing `0.1.3`/`0.1.4` local publishes from the
  earlier session were left untouched).
- **`~/.m2/repository/dev/thuat/hotreload-runtime`**: no `0.1.6` entry remains (published twice for
  testing, removed both times).

## Honest scope note

The tier-report honesty fix is real and directly targets the exact failure mechanism the task's
own probe proved (bytecode not executed, silently downgraded to a false success). What remains
genuinely open is *why* a subset of keys throw at all on a longer-lived, multiply-redefined
process — narrowed to "something about accumulated JVMTI/Compose runtime state across repeated
`RedefineClasses` calls," not proven. If this resurfaces, the next step is capturing the actual
wrapped cause (`t.cause`, not `t.message`) directly in `ComposeInvalidator`'s existing warning log
— a one-line change deferred here only because it's a logging improvement, not something this
report's evidence required to establish the fix.
