# Dex-archive read — investigation report: BLOCKED, reverted

## Status: BLOCKED — no code shipped, `main` unchanged

The core premise — "AGP's `dexBuilderDebug` already emits per-class dex files... read the
changed class's dex bytes DIRECTLY, skip the merge tasks and our own D8 split" — does **not**
hold on a real project. Verified empirically on-device: pushing a class's bytes straight from
`dexBuilderDebug`'s per-class archive causes `RedefineClasses` to fail with
`JVMTI_ERROR_ILLEGAL_ARGUMENT`, reproducibly, for a trivial, non-structural, single-string-literal
body edit. The merged-dex-then-split approach already in `main` was re-confirmed working on the
exact same edit. All code changes were reverted; `main` is exactly as it was before this task
(`git status`/`git diff` clean against `HEAD`).

## What was implemented (then reverted)

1. `GradleCompiler.compile()`: `$appModule:dexBuilderDebug` + `$appModule:mergeLibDexDebug`
   instead of `mergeProjectDexDebug` + `mergeLibDexDebug`.
2. `DexPackager.dexClasses()`: two-tier lookup —
   - Tier 1 (new, no D8): loose per-class file under
     `<appModule>/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/<pkg>/<Class>.dex`,
     falling back to a `ZipFile` scan of hash-named `*_N.jar` files in that same directory
     (confirmed real on `orderbook-demo`: namespaced R classes for every androidx library on the
     compile classpath are bundled there as a *merged* `classes.dex`, not loose files) — a
     descriptor-substring match inside a jar entry pushes that entry's bytes whole, since
     `RedefineClasses` accepts a multi-class dex and picks the target out by descriptor.
   - Tier 2 (unchanged D8-split logic from before this task, scoped to `mergeLibDexDebug`'s
     buckets only): for classes in another Gradle module the app depends on via
     `project(":other")`.
3. Rewrote `DexPackagerTest.kt` for the two-tier shape (archive loose file, archive jar entry,
   library-merged-dex fallback, batch spanning both, same-simple-name-different-package,
   missing-everywhere error path). Updated `GradleCompilerIntegrationTest.kt` and `README.md`
   comments to match.
4. All of this passed `./gradlew build -x lint` and `e2e/run-e2e.sh` (which exercises the library
   fallback via `sample/feature`'s `Greeting.kt` — tier1, state preserved). **The failure only
   surfaced during the real-project device verification step (§ below), which is exactly what
   this task's spec called out as the load-bearing risk to check before trusting any of the
   above.**

## The blocking finding

### Sub-project ("library module") dex archive: investigated, does not exist where expected

Before even reaching the failure, the "mirror today's project+lib coverage" instruction sent me
looking for library-module classes under `app/build/intermediates/sub_project_dex_archive`. That
directory is **empty** on both `orderbook-demo` (single `:app` + a `:baselineprofile` test module
— nothing to populate it) and, more importantly, on this repo's own `sample/` project (`:app` +
`:feature`, a real `project(":feature")` dependency) **even after a full `assembleDebug`**.
`:feature`'s classes are dexed via a Gradle artifact transform that only materializes when
something resolves the module's `DEX_ARCHIVE`-typed artifact — requesting `dexBuilderDebug` alone
never triggers it; only `mergeLibDexDebug` (or `assembleDebug`) does, and its real output lands in
a hash-keyed, unpredictable path (`feature/build/.transforms/<hash>/transformed/
bundleLibRuntimeToDirDebug/bundleLibRuntimeToDirDebug_dex/<pkg>/<Class>.dex`) rather than anywhere
under `sub_project_dex_archive`. Given that fragility, the implementation above kept
`mergeLibDexDebug` running and reused the pre-existing (already-tested, already-safe) D8-split
logic for library classes rather than trying to read that transform-cache path directly.

### The real blocker: `dexBuilderDebug`'s per-class dex is not the merge tasks' input byte-for-byte

Reproduced on `orderbook-demo` (`emulator-5554`, package `com.example.orderbook`, agent attached),
editing `ProductCard.kt`'s `CardText` (`"Run13 " + product.brand` → `"Run14 " + product.brand`,
a pure string-literal change, no structural change by any definition):

- `.hotreload/dex/com_example_orderbook_catalog_ProductCardKt.dex` (this task's new tier-1 read,
  byte-identical to
  `app/build/intermediates/project_dex_archive/debug/dexBuilderDebug/out/com/example/orderbook/catalog/ProductCardKt.dex`)
  is **23,100 bytes**.
- The same class, extracted the *old* way — `./gradlew :app:mergeProjectDexDebug`, then
  `D8 --intermediate --min-api 26 --file-per-class` on the bucket that contains it — is
  **20,972 bytes**. Different size, different content. The merge step is not a no-op pass-through
  for this class; it does real linking/resolution work that the standalone per-class
  `dexBuilderDebug` output hasn't had done yet.
- Pushing the 23,100-byte (archive) version and calling `LOAD_DEX` — both through the full CLI
  cycle and through a hand-written raw-protocol probe that bypasses all CLI bookkeeping (see
  below) — fails:
  ```
  RedefineClasses failed: JVMTI_ERROR_ILLEGAL_ARGUMENT (structural changes are unsupported in v1 — rebuild)
  ```
- Pushing the 20,972-byte (merge-then-split) version for the *exact same edit* succeeds:
  ```
  status: 0
  detail: Lcom/example/orderbook/catalog/ProductCardKt;: redefined | tier1
  ```
- Reverting all code and re-running the identical cycle through the unmodified (main-branch) CLI
  confirms it: `✓ reloaded 1 class(es) in 8825ms [tier1 — remember state preserved]:
  com.example.orderbook.catalog.ProductCardKt (compile 6.3s · diff 0.2s · dex 0.9s · push 0.8s ·
  redefine 0.7s)`, with `adb logcat -s HotReload` showing `tier1: group-key invalidation`.

The raw-protocol probe (a ~30-line Python script speaking `Protocol.encodeRequest`/
`decodeReply`'s wire format directly against the agent's forwarded socket) was written
specifically to rule out a bug in my own CLI code (wrong path, wrong descriptor, batching
interaction) as the cause — it isolates the question to "does ART's `RedefineClasses` accept
these exact bytes for this exact class," independent of anything else in the pipeline, and
confirms the archive bytes themselves are the problem, not how they're delivered.

This is precisely the failure mode the task's own spec flagged as the load-bearing risk
("Crucially, the original reason we switched to merged-dex extraction still holds here... This
must be verified empirically on device, not assumed") and its own required fallback
("if on-device verification shows redefinition now fails... STOP, revert to the merged-dex path,
and report BLOCKED with the evidence"). The specific error differs from the METHOD_DELETED example
named in the spec (`JVMTI_ERROR_ILLEGAL_ARGUMENT` here, not
`JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_DELETED`), but the substance is identical: a
class that should redefine cleanly is rejected by ART because of *which pipeline stage* the bytes
came from, not because of anything in the actual source edit.

## Secondary lever: Gradle configuration cache

Tried `--configuration-cache` on `orderbook-demo` for `:app:dexBuilderDebug :app:mergeLibDexDebug`
(flag only, nothing written to the project). Cache stored on the first run, reused on the second;
wall time for a real edit was statistically indistinguishable from the same command without the
flag (6.35s with vs. 6.4–6.5s without, across a warm daemon — noise-level difference). Not
adopted: no measured win, and per the task's own instruction not to spend long here once that was
clear.

## Verification performed

- `export JAVA_HOME=$(/usr/libexec/java_home -v 21); ./gradlew build -x lint` — green, both before
  writing the archive-read code and after reverting it.
- `e2e/run-e2e.sh` — `E2E PASS` with the archive-read implementation in place (before the
  real-project check surfaced the blocker); golden path exercised the library (`:feature`)
  fallback tier and succeeded with `tier1`, counter state preserved.
- Real-device verification on `orderbook-demo` is what caught the problem (see above) — this is
  the check that did its job.
- Post-revert: rebuilt `:cli:installDist` and re-ran the identical `ProductCard.kt` edit through
  the now-reverted, original CLI — succeeds (`tier1`, `remember` state preserved, pid unchanged).

## orderbook-demo working tree

Left as found apart from the required test edit and CLI-generated state:
- `app/src/main/java/com/example/orderbook/catalog/ProductCard.kt`: `CardText`'s brand line now
  reads `Text("Run14 " + product.brand, ...)` (was unprefixed `product.brand` at task start;
  iterated through several intermediate values while probing the failure — left at the final
  state, not reverted, per the task's instructions).
- `.hotreload/` (untracked baseline + dex cache) exists, regenerated by the `bootstrap`/`cycle`
  runs in this session.
- `app/build.gradle.kts` and `settings.gradle.kts` still carry their pre-existing hot-reload
  plugin wiring from before this task — untouched by me.
- Nothing committed in that repo.

## Recommendation

Do not pursue reading `dexBuilderDebug`'s per-class archive directly for `RedefineClasses`
payloads; it is a pre-merge intermediate artifact, not equivalent to the merge tasks' output
despite being their direct input, and ART rejects it even for trivially-compatible edits. If the
per-cycle Gradle cost of `mergeProjectDexDebug` is worth attacking again, the next angle would
need to explain *why* the archive's per-class dex differs structurally from the merged-and-resplit
version (global synthetics linking is one plausible mechanism — `global_synthetics_project` exists
as a sibling `dexBuilderDebug` output, though it was empty for this specific class — but this
would need its own on-device-verified investigation, not an assumption) rather than trying to
route around the merge step outright.
