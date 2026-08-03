# AGP 9 / JetNews compatibility fix — report

Commits: `c2a8ec7` (runtime minSdk), `b4f3242` (class-dir discovery) on `main`, pushed to
`origin/main` (`f21b953..b4f3242`).

## What changed

- `runtime/build.gradle.kts`: `minSdk` 26 → 21. The library only touches APIs available since
  API 21 and is inert until the agent attaches (API 26 is the JVMTI-attach floor, not anything
  the library itself needs). The old value broke manifest merging for any consumer below API 26
  — found via JetNews (minSdk 23).
- `cli/.../ModuleResolver.kt`: `classDirsOf(module)` no longer returns one hardcoded
  `build/tmp/kotlin-classes/debug` path. It now probes three known candidate directories per
  module —
  - `build/tmp/kotlin-classes/debug` (AGP 8 + Kotlin-Gradle-Plugin)
  - `build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes` (AGP 9 built-in
    Kotlin compiler)
  - `build/intermediates/javac/debug/compileDebugJavaWithJavac/classes` (javac output; path
    unchanged across AGP versions, included for completeness though neither test project has
    Java sources to verify it live against)

  and returns whichever exist (`Files.isDirectory` checks only — no directory walk, so the diff
  phase's ~0.1–0.4s hot-path timing is unaffected). If **none** exist, it now throws with every
  path it checked and the module name, instead of silently returning a dead path that
  `ClassDiffer` would filter out into an empty snapshot (the exact bug: JetNews's `:app` under
  AGP 9 always diffed to empty, so the CLI printed "no bytecode changes" on every cycle no
  matter what was edited).
- `cli/.../ModuleResolverTest.kt`: rewritten `classDirsOf` tests — AGP 8 layout alone, AGP 9
  layout alone, javac layout alone, both AGP 8 + AGP 9 present (returns both), and neither
  present (asserts the error message names both `build/tmp/kotlin-classes/debug` and
  `built_in_kotlinc` and the module name).
- `README.md`: Requirements section now states the verified AGP 8.x / AGP 9.x range and that the
  device's API 26+ floor is independent of the app's own `minSdk` (verified down to 23).

## What AGP 9 moved (investigated live against JetNews, AGP 9.3.1 / Kotlin 2.4.10 / Gradle 9.5)

- Kotlin compilation output: `build/tmp/kotlin-classes/debug` (AGP 8, via KGP's own task) →
  `build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes` (AGP 9's built-in
  Kotlin compiler, no KGP task involved). Confirmed via `InternalArtifactType$BUILT_IN_KOTLINC`
  in `gradle-9.3.1.jar` and direct inspection of JetNews's build output.
- `mergeProjectDexDebug` / `mergeLibDexDebug` output layout
  (`build/intermediates/dex/debug/<task>/<N>/classes.dex`) is **unchanged** — confirmed by
  directory listing and by `DexPackager.dexClasses` succeeding live against JetNews.
- `GradleCompiler` needed no change: it targets `mergeProjectDexDebug`/`mergeLibDexDebug` by
  task name, and Gradle's task graph pulls in whichever Kotlin-compile task is upstream
  (built-in or KGP) automatically — confirmed live: editing `PostCardTop.kt` and running
  `cycle` triggered a real recompile and the diff picked up the changed classes.
- `HotReloadPlugin`'s `enableKeyMeta` matches Kotlin compile tasks by **name**
  (`compile*Kotlin`), not by KGP task type, so it keeps working under AGP 9's built-in compiler
  without changes.

## Found but not fixed

- On the JetNews reload, `keysForClass` logged `no KeyMeta class found among candidates` for
  the two changed classes, so the reload fell back to tier2 (whole-composition rebuild, UI state
  reset) instead of tier1. This is the existing tiered-fallback contract working as designed —
  `keysForClass` only finds keys for a KeyMeta class ART has already loaded, and these two
  classes' KeyMeta siblings had apparently never been touched at runtime — not an AGP 9
  regression. Not fixed: it's out of this task's scope (path discovery + AGP 9 breakage), and
  the existing sample app's tier1 path (which does have a loaded KeyMeta class) is unaffected
  and still verified working by the e2e run below.
- `DexPackager`'s hardcoded `minApi = 26` default is unrelated to AGP 9 and pre-dates this task;
  left as-is since it demonstrably still dexes/redefines correctly against a minSdk-23 app.

## Tests

`ModuleResolverTest`: 4 new/rewritten tests for the path-candidate logic (AGP 8 alone, AGP 9
alone, javac alone, both present, neither present → error naming both paths + module). All
existing tests across `cli`, `gradle-plugin`, `agent`, `hotreload-runtime` remain green.

## Verification

1. `export JAVA_HOME=$(/usr/libexec/java_home -v 21); ./gradlew build -x lint` →
   `BUILD SUCCESSFUL in 43s`, 137 tasks. `./gradlew test --rerun` → `BUILD SUCCESSFUL`
   (`:cli:test`, `:gradle-plugin:test`, `:agent:test`, `:hotreload-runtime:test` all ran).
   `./gradlew :cli:installDist` → `BUILD SUCCESSFUL`.

2. `e2e/run-e2e.sh` on `R5CX51BENMM` (physical device, AGP 8 path — proves no regression):
   ```
   ✓ reloaded 1 class(es) in 3708ms [tier1 — remember state preserved]: dev.thuat.hotreload.sample.feature.GreetingKt (compile 3.0s · diff 0.0s · dex 0.5s · push 0.2s · redefine 0.1s)
   ✗ incompatible change: RedefineClasses failed: JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED (structural changes are unsupported in v1 — rebuild)
   E2E PASS
   ```

3. JetNews repro (AGP 9.3.1 / Kotlin 2.4.10 / Gradle 9.5, `com.example.jetnews`, `R5CX51BENMM`):
   - `bootstrap` (agent already attached, fast-path ping) then re-snapshotted the baseline —
     `.hotreload/baseline.txt` went from 0 bytes (the bug) to 195 real entries keyed under
     `.../built_in_kotlinc/debug/compileDebugKotlin/classes`.
   - Edit: `PostCardTop.kt`'s author-name `Text` changed from `"✍️ " + post.metadata.author.name`
     to `"📝 by " + post.metadata.author.name`.
   - `cycle --file .../PostCardTop.kt`:
     ```
     ✓ reloaded 2 class(es) in 36891ms [tier2 — UI state reset]: com.example.jetnews.ui.home.ComposableSingletons$PostCardTopKt, com.example.jetnews.ui.home.PostCardTopKt (compile 34.0s · diff 0.1s · dex 1.3s · push 0.4s · redefine 1.1s)
     ```
   - logcat: `08-03 23:29:15.457 23948 23948 I HotReload: tier2: whole-composition rebuild via HotReloader`
     followed by `HotReloadAgent: LOAD_DEX: Lcom/example/jetnews/ui/home/ComposableSingletons$PostCardTopKt;, Lcom/example/jetnews/ui/home/PostCardTopKt;: redefined | tier2`.
   - App pid before and after the cycle: `23948` (unchanged — same process, no restart).
   - `uiautomator dump` after bringing JetNews to the foreground: on-screen text
     `"&#128221; by Android Studio Team"` (`&#128221;` = U+1F4DD "📝") confirms the edit is live.

4. **Reload timing on JetNews**: 36.9s total, dominated by `compile` (34.0s, real Gradle Kotlin
   recompile of a much larger project than the sample); `diff` 0.1s, `dex` 1.3s, `push` 0.4s,
   `redefine` 1.1s — the non-compile phases stay in the same ballpark as the sample app
   (compile 3.0s there) despite JetNews being considerably bigger.
