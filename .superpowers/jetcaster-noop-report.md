# Jetcaster tier1-no-op investigation — report

Commit: `a146f23` (`fix(cli): union pre-compile and post-compile group keys for LOAD_DEX`) on
`main`, pushed to `origin/main` (`6f66872..a146f23`).

## Status

Hardening shipped and verified safe. **Root cause not confirmed.** Extensive on-device testing,
including replaying the user's own reported edits from a clean baseline on the same device,
could not reproduce "CLI reports tier1, UI does not change." Per the task's own STOP clause,
this report presents the evidence and a proposed next step rather than claiming a fix that
wasn't verified against the actual failure.

## What was tested and how

Device `R5CX51BENMM` (Samsung, Android 15), confirmed unlocked
(`deviceLocked=0`) throughout. Built the CLI locally (`./gradlew :cli:installDist`) and invoked
`cli/build/install/cli/bin/cli` directly (not the published 0.1.6 wrapper), so every test ran
against the fix under investigation.

The Jetcaster checkout's `.hotreload/baseline.txt` was dated ~14 minutes after the app's
`firstInstallTime`, meaning several edit/reload cycles had already run there before this
investigation started, presumably the ones behind the reported transcript. To get a clean,
reasoned starting point: reverted `Home.kt`/`EpisodeListItem.kt` to `git HEAD`, ran
`./gradlew :mobile:assembleDebug -x lint`, installed fresh, launched, bootstrapped — establishing
a baseline where the CLI's hash tracking, the on-disk classes, and the running app's composition
all agree. Then re-applied the user's exact reported edits (same padding value, same "Hehe"
literals, same removed modifier line) by hand from the captured diff, and ran real reload cycles
against them with an opt-in `HOTRELOAD_DEBUG_KEYS=1` env var added to `ReloadOrchestrator.cycle()`
that prints each redefined class's pre-compile ("old") keys, freshly-compiled ("new") keys, and
their union to stderr.

### Leading hypothesis: tested, not confirmed

For every edit that let `RedefineClasses` succeed — the padding value change, both string-literal
substitutions (`stringResource(...)` → `"Hehe"`, `episode.title` → `"Hehe"`), and removing a
`.background(...)` call from a modifier chain — **old and new keys were identical**, both for
the classes in `Home.kt` and `EpisodeListItem.kt`. Example (`HomeKt`, 30 keys, one representative
line):

```
DEBUG com.example.jetcaster.ui.home.HomeKt: old=[645563762, 855486526, -1160944919, ...] new=[645563762, 855486526, -1160944919, ...] union=[...]
```

`tier1` was reported and the UI genuinely updated in every one of these cases (`uiautomator dump`
before/after confirmed real text changes; `adb logcat -s HotReload HotReloadAgent` showed
`tier1: group-key invalidation, keys=[...]` immediately followed by
`LOAD_DEX: ...: redefined | tier1`). This matches reading Compose's own runtime source
(`androidx.compose.runtime:runtime-android:1.11.4`, `HotReloader.kt` /
`Recomposer.invalidateGroupsWithKey`): `@FunctionKeyMeta` keys are durable by design — the whole
mechanism Live Edit depends on — so ordinary body edits (literal swaps, modifier-chain edits)
don't renumber them. `KeyMetaExtractor.extractKeys` was already reading them correctly.

The one edit that *did* shift a key (adding a new conditionally-composed `Text()`, i.e. genuinely
restructuring a composable's group tree) broke `RedefineClasses` outright with
`JVMTI_ERROR_UNSUPPORTED_REDEFINITION_SCHEMA_CHANGED`, which the CLI already reports correctly as
`Incompatible`, not a false `tier1`. Same result for two `:core:designsystem` edits tried for the
cross-module case (`ContentScale.Crop` → `FillHeight` default value, `Alignment.Center` →
`BottomEnd`, and a `1.5f` → `2.5f` literal in `ImageBackground.kt`) — ART's `RedefineClasses`
rejected all three as schema changes, again correctly surfaced as `Incompatible`. So the specific
mechanism this task's hypothesis names (new keys sent, old keys still live in the slot table,
`invalidateGroupsWithKey`'s `Unit` return silently swallowing the mismatch) never actually
triggered in any edit tried, on this device, against this app, at this Compose version.

### A confounding false lead on JetNews

While regression-testing JetNews, the on-screen "highlighted post" card showed `🔥 RELOADED
Android Studio Team` — text that exists in *no* current source file. Force-stopping and
relaunching the app (fresh process, zero live redefinitions, only the installed APK's own
bytecode) still showed it, which localized it: that text is baked into the *installed APK
itself* (`lastUpdateTime` several hours before this session), from a source revision that has
since been edited (to `"✅ TIER1 " + ...`) without a rebuild+install in between. A fresh
`assembleDebug` + install immediately showed the correct current text, and a subsequent hot-reload
cycle on top of that clean install worked correctly (`tier1`, real UI change, confirmed via
`uiautomator dump` + logcat). This was a stale, long-running dev process carrying unrelated
history, not a reproduction of the reported bug — flagged here in case it resurfaces, since it
looks superficially similar.

### What shipped anyway, and why

`cli/.../KeySelection.kt` (new) + `ReloadOrchestrator.cycle()`: before `compiler.compile()`
overwrites any class file, `keysSnapshot(allClassDirs())` extracts every class's current
(pre-compile) keys into `Map<Path, List<Int>>`. After compiling, each redefined class's
`LoadDexEntry.keys` is now `resolvedKeysFor(changed, oldKeys)` — the union of that pre-compile
snapshot and `KeyMetaExtractor.keysFor(changed)` (the post-compile keys, as before this change).
This directly implements the fix direction the task named ("invalidate the keys present in the
running app... or the union of old and new keys") and is real, tested hardening for the case
where keys *do* shift on some edit type not covered by this investigation's testing. It is a
verified no-op for every case actually reproduced here (old == new whenever redefinition
succeeds), so it carries no regression risk against the sample app, JetNews, or Jetcaster's
tested edits. `KeyMetaExtractor.keysFor`'s legacy-`$KeyMeta`-sibling candidate derivation was
factored into `KeyMetaExtractor.legacyKeyMetaCandidates` so both the pre- and post-compile paths
look up the same candidate sibling paths.

Also added: an opt-in `HOTRELOAD_DEBUG_KEYS=1` env var, checked once per redefined class in
`cycle()`, printing `old=/new=/union=` key sets to stderr. Zero cost when unset; kept as
instrumentation for whoever picks this investigation back up.

### Tier-report honesty

Not changed. `invalidateGroupsWithKey` (`androidx.compose.runtime.HotReloaderKt`, and the
`Recomposer`/`HotReloader` Companion fallbacks) returns `Unit` at every level reachable via
public or accessible-via-reflection API — there is no positive "did this actually match a scope"
signal short of reflecting into `CompositionImpl`'s private `slotStorage` field to call
`SlotStorage.invalidateGroupsWithKey(key): List<RecomposeScopeImpl>?` directly, bypassing the
public wrapper. That is materially more invasive, private-API-shaped reflection than anything
currently in `ComposeInvalidator.kt`, and without a reproduced failure case to validate it
against, implementing it now would be guessing. Recommending this as the next step if the bug
recurs (see below) rather than shipping it unverified.

## Verification run

- **New unit tests**: `KeySelectionTest.kt` (5 tests: snapshot extraction, snapshot omits
  keyless classes, union of disjoint old/new keys — using a synthetic disjoint "old" set against
  a real Compose 1.11 fixture's real "new" keys to prove the union logic itself, fallback to
  new-only when no pre-compile entry exists, legacy `$KeyMeta` sibling union) +
  `KeyMetaExtractorTest.kt` (2 new tests for the factored-out `legacyKeyMetaCandidates`). All
  pass: `./gradlew :cli:test`.
- **`./gradlew build -x lint`**: green.
- **`e2e/run-e2e.sh`** (`ANDROID_SERIAL=R5CX51BENMM`): `E2E PASS` — golden tier1 path (state
  preserved) and the incompatible-change path (`JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED`,
  correctly reported, exit 2) both still correct.
- **JetNews** (`/Users/admin/Projects/compose-samples/JetNews`): clean install, bootstrap, edit
  `PostCardTop.kt`, cycle — `tier1`, real UI change confirmed via `uiautomator dump` (`"✅
  TIER1-REGRESSION-CHECK Android Studio Team"` → `"✅ TIER1 Android Studio Team"` after reverting
  to the user's original marker), logcat confirms `tier1: group-key invalidation` +
  `redefined | tier1`, `old == new` for both redefined classes (as expected — this was a plain
  string-literal edit).
- **Jetcaster**: multiple real edits (`:mobile` and `:core:designsystem`) redefined correctly,
  `tier1` reported honestly in every case (correct successes and correctly-reported
  `Incompatible` failures), `uiautomator dump` confirmed real UI changes for every accepted edit.
  Could not produce a false-`tier1` case despite direct attempts, including replaying the
  reported failure's own edits.

## What was touched in the user's checkouts

- **Jetcaster**: left `build.gradle.kts`, `gradle.properties`, `Home.kt`, `EpisodeListItem.kt`
  exactly as found (their edits intact — verified byte-for-byte against the original `git diff`
  captured at the start of this session). Rebuilt and reinstalled the app from that exact source
  twice (once mid-investigation, once at the end) to establish clean, reproducible states —
  no source files were altered by this. `.hotreload/` was deleted and regenerated by
  `bootstrap`. Left the device with a fresh install matching the user's current source (Home
  screen correctly shows their "Hehe" edits).
- **JetNews**: same treatment — `build.gradle.kts`, `PostCardTop.kt`, `PostCards.kt` left
  exactly as found. Rebuilt/reinstalled once to get a clean baseline (the app process had ~14+
  hours of accumulated, undocumented hot-reload history predating this session — see the
  confounding lead above). `.hotreload/` deleted and regenerated by `bootstrap`.
- **android-hot-reload tool repo**: a concurrent, unrelated session had uncommitted work in
  progress in this same checkout (`AppModuleHint.kt`, `--app-module` not-found hint, and related
  `gradle-plugin`/test changes) when this investigation started. That work was left untouched in
  the working tree (still uncommitted, exactly as found) — this investigation's commit
  (`a146f23`) contains only the 5 files it authored:
  `cli/.../KeyMetaExtractor.kt`, `cli/.../ReloadOrchestrator.kt`, `cli/.../KeySelection.kt` (new),
  `cli/.../KeyMetaExtractorTest.kt`, `cli/.../KeySelectionTest.kt` (new).

## Proposed next step, since the actual root cause is still open

The `.hotreload/baseline.txt` timestamp gap found at the start of this session (~14 minutes of
undocumented activity between install and the reported failure) is the one piece of evidence not
accounted for. Recommend, next time this reproduces: capture `adb logcat -s HotReload
HotReloadAgent` continuously from `bootstrap` through the failing cycle (not just around a single
`cycle` call), and check whether a full rebuild+reinstall happened out-of-band (e.g. via Android
Studio's Run button) between the CLI's `bootstrap` and the failing `cycle` — that would desync the
CLI's baseline-hash tracking from what's actually installed without necessarily involving Compose
keys at all. If that's ruled out and keys are confirmed to genuinely differ next time (via the
now-available `HOTRELOAD_DEBUG_KEYS=1`), the shipped fix should already close it; if keys still
match but the UI still doesn't update, the `SlotStorage.invalidateGroupsWithKey` reflection
described above is the next diagnostic to reach for a real match/no-match signal.
