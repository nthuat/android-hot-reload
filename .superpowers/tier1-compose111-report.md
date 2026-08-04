# Tier-1 on Compose 1.11: fix report

## Problem (confirmed before touching code)

`ComposeInvalidator.keysForClass` found Compose group keys reflectively, via a
`Class.forName("<facade>$KeyMeta")` lookup on a holder class the compiler used to emit
alongside every Compose file. On Compose ~1.7 (this repo's `sample/`) that still works. On
Compose 1.11.4 (Google's JetNews) it silently degrades to tier 2, which rebuilds the whole
composition and wipes every `remember`.

Verified with `javap -v` on both generations' own `FunctionKeyMeta.class`
(`androidx.compose.runtime:runtime-android`, pulled from the Gradle cache):

- **1.7.6**: `@kotlin.annotation.Retention(RUNTIME)`, `@Target(CLASS)` only — the compiler puts
  one repeatable `@FunctionKeyMeta` per composable on a sibling `<Facade>$KeyMeta` class,
  reflectable at runtime.
- **1.11.4**: `@kotlin.annotation.Retention(BINARY)`, `@Target(CLASS, FUNCTION)` — confirmed via
  `javap` on a real compiled JetNews class (`AppDrawerKt.class`) that the compiler now emits
  `@FunctionKeyMeta` directly on each composable's own compiled **method**, in
  `RuntimeInvisibleAnnotations`. BINARY retention means no on-device reflection can ever see it,
  on any Compose version — this is a class-file-format fact, not a bug to route around.
  `app:compileDebugKotlin`'s own build output for JetNews additionally printed
  `generateFunctionKeyMetaClasses is deprecated ... replaced by emitting annotations on
  functions instead`, independently confirming the same thing from the compiler's own mouth.

## Fix

Moved key extraction to the host, where `RuntimeInvisibleAnnotations` are exactly as readable
as visible ones.

1. **`cli/.../KeyMetaExtractor.kt`** (new): ASM (`org.ow2.asm:asm-tree:9.7.1`, added as an
   explicit dependency — verified `r8`'s jar carries no usable `org.objectweb.asm` classes, it's
   fully repackaged) reads both class- and method-level `@FunctionKeyMeta`, unwrapping the
   compiler-generated `FunctionKeyMeta$Container` when more than one lands on the same element.
   `keysFor(ChangedClass)` unions the class's own keys with whatever a legacy `$KeyMeta` sibling
   on disk holds (same outer/`outerKt` candidate derivation as the old on-device
   `keysForClass`, so host and device agree on where to look).
2. **`Protocol.kt`**: LOAD_DEX records grew a third `\n`-delimited field — space-separated keys,
   may be empty. `LoadDexEntry` replaces the old `Pair<String,String>`. `agent.cpp`'s
   `ParseLoadDexRecords`/`HandleLoadDex`/`NotifyRuntime` updated to match byte-for-byte, and to
   union keys across whichever records actually got redefined (skipped ones don't contribute).
3. **`ComposeInvalidator.reload`**: signature grew a `keys: IntArray` parameter
   (`([Ljava/lang/String;[I)Ljava/lang/String;` on the JNI side). Uses the CLI-supplied keys when
   present; falls back to the old on-device `keysForClass` lookup only when empty (older CLI, or
   a case extraction missed) — chain and logging unchanged otherwise, so tier reporting stays
   honest (tier1 only reported when invalidation with real keys actually succeeded).

Both the agent and the runtime ship with the CLI, so the wire format changed freely — no
backward-compat shim.

## Tests

- `KeyMetaExtractorTest`: 8 cases against **real compiled fixtures**, not hand-written bytes —
  copied from this repo's own `sample/` build output (legacy single-key and
  `Container`-wrapped-multi-key holder classes) and from a genuine Compose 1.11.4 / Kotlin
  2.4.10 build (JetNews's `AppDrawerKt.class`, method-level, 6 real keys). Committed under
  `cli/src/test/resources/keymeta-fixtures/`.
- `ProtocolTest` / `AgentClientTest`: updated + extended for the 3-field record format and the
  new keys field.
- Full existing suite (102 cli tests) stays green.

## Verification

### 1. Build

```
./gradlew build -x lint         → BUILD SUCCESSFUL (138 tasks)
./gradlew :cli:installDist       → BUILD SUCCESSFUL
./gradlew :agent:assembleDebug   → BUILD SUCCESSFUL
```
`Agent_OnAttach` confirmed exported (`nm -D`) in both
`stripped_native_libs/debug/.../arm64-v8a/libhotreload_agent.so` and the `x86_64` counterpart.

### 2. Compose 1.7 path (no regression)

Device `R5CX51BENMM` (Samsung SM-F731B, Android 15) — `dumpsys trust` showed `deviceLocked=0`
(unlocked), so full `adb input`/UI automation was usable.

```
e2e/run-e2e.sh → E2E PASS
✓ reloaded 1 class(es) in 3799ms [tier1 — remember state preserved]: dev.thuat.hotreload.sample.feature.GreetingKt
  (compile 3.1s · diff 0.0s · dex 0.5s · push 0.2s · redefine 0.1s)
```
CLI-reported tier and the runtime's own `HotReload` logcat line both agreed on `tier1` (the
script's own assertions check both). Counter state (`Count: 2`) survived. Still tier1 after the
fix — the legacy `$KeyMeta` holder path is exercised by `KeyMetaExtractor.keysFor`'s sibling
lookup on the host now, instead of the old on-device fallback, and produces the same key set.

### 3. Compose 1.11 path (the fix) — JetNews

`publishToMavenLocal` for `:gradle-plugin` and `:hotreload-runtime` at `0.1.4` (version left
unchanged, unreleased). JetNews's root `build.gradle.kts` bumped from
`id("dev.thuat.hotreload") version "0.1.3"` to `"0.1.4"` (mavenLocal already wired in its
`settings.gradle.kts`). Rebuilt, installed, bootstrapped against `com.example.jetnews`.

Interacted with the running app first: tapped into an article ("A Little Thing about Android
Module Paths") to push real navigation/composition state onto the stack. Captured pid: `31737`.

Edited `app/src/main/java/com/example/jetnews/ui/home/PostCardTop.kt`'s author-line `Text` (a
composable on the Home list, off-screen behind the open article) and ran `cycle`:

```
✓ reloaded 2 class(es) in 7703ms [tier1 — remember state preserved]:
  com.example.jetnews.ui.home.ComposableSingletons$PostCardTopKt, com.example.jetnews.ui.home.PostCardTopKt
  (compile 5.6s · diff 0.2s · dex 1.4s · push 0.4s · redefine 0.1s)
```

`adb logcat -d -s HotReload`:
```
HotReload: tier1: group-key invalidation, keys=[-1535180838, 1283892245, -831055977, 660863676, -830472155, 1143168064, 593381361]
```

**This is the class of edit that reported tier2 before the fix** — Compose 1.11.4 emits no
`$KeyMeta` holder at all (confirmed via `javap` above), so the old on-device-only lookup found
zero keys for `PostCardTopKt` and fell through the chain every time, for every edit, on this
Compose generation. It now reports tier1 with real keys because `KeyMetaExtractor` reads them
straight off the compiled `.class` files `dexClasses` had already split out.

**State preservation, concretely:**
- `adb shell pidof com.example.jetnews` → `31737` before *and* after the reload — same process,
  never restarted (rules out tier3 outright).
- `uiautomator dump` immediately after the reload showed the app **still on the article detail
  screen** ("A Little Thing about Android Module Paths", full body text, byline) — the
  navigation/composition state a whole-composition rebuild (tier2) or activity recreate (tier3)
  would have reset was untouched.
- Pressing back afterward surfaced the Home list with the edit applied and visible:
  `text="🔥 TIER1-1114 Android Studio Team"` (was `"🔥 RELOADED Android Studio Team"`,
  itself a residual edit from earlier exploration of this same file — this session's own new
  edit is the `TIER1-1114` marker) — `uiautomator dump` confirms the edit landed on the live
  process, not a fresh install.

### 4. Timings

| Path | Total | compile | diff | dex | push | redefine |
|---|---|---|---|---|---|---|
| Compose 1.7 (sample, `GreetingKt`) | 3799ms | 3.1s | 0.0s | 0.5s | 0.2s | 0.1s |
| Compose 1.11.4 (JetNews, `PostCardTopKt` ×2 classes) | 7703ms | 5.6s | 0.2s | 1.4s | 0.4s | 0.1s |

JetNews's larger/cold-cache module accounts for essentially all of the delta (compile+dex); the
new host-side ASM extraction itself is a few-millisecond in-memory pass over already-split
`.class` files, not a separate compile.

## Outcome

Both Compose generations reach tier1 with `remember`/navigation state intact. No version bump
(stays 0.1.4, unreleased). Nothing published, no tags cut.
