# Fix: stale `adb forward` cross-app LOAD_DEX — report

Branch `main`. Bug found on a real device (R5CX51BENMM): `adb forward tcp:46837
localabstract:hotreload-agent-<pkg>` is one GLOBAL per-device mapping. `bootstrap()`
re-established it; `cycle()` never did — it just trusted whatever the forward currently pointed
at. Bootstrapping a second app on the same device (another project, or `e2e/run-e2e.sh`
bootstrapping the sample app) silently repointed it, and every later `cycle` for the first app
sent its LOAD_DEX to the second app's agent.

## Repro (before the fix)

1. Bootstrapped `com.example.orderbook` (project `orderbook-demo`, pid 359) — forward pointed at
   `hotreload-agent-com.example.orderbook`.
2. Bootstrapped `dev.thuat.hotreload.sample` (pid 858) on the same device — silently repointed
   the same `tcp:46837` mapping to `hotreload-agent-dev.thuat.hotreload.sample`.
3. Edited `orderbook-demo`'s `ProductCard.kt` and ran `cli cycle --package com.example.orderbook`
   with the stale forward still pointing at the sample app:
   ```
   ✗ device/agent: cannot read dex: /data/data/com.example.orderbook/code_cache/hotreload/078229b837f1-com_example_orderbook_catalog_ProductCardKt.dex
   ```
   Exit code 3. Failed safely here only because the sample app's agent can't read orderbook's
   app-private data dir — with a same-descriptor loaded class in both apps this would have
   silently redefined the wrong running app.

## The fix — three layers

1. **`ReloadOrchestrator.cycle()` now re-issues `adb forward` for its own package on every
   call**, before doing any other work (mirrors what `bootstrap()` already did). `adb forward` is
   idempotent and cheap (one local round trip).
2. **PING now carries the agent's own package name.** `agent.cpp` caches `g_pkg_name` (from
   `/proc/self/cmdline`, the same string used for the per-package socket name) and replies
   `"pong:<pkg>"` to `CMD_PING` (`Protocol.PING_REPLY_PREFIX` / `Protocol.pingPackageOf` on the
   CLI side — documented, byte-for-byte contract). `cycle()` calls a new
   `verifyAgentIdentity()` right after the forward, before any compile/dex/push/LOAD_DEX: a
   reply naming a different (or no) package → `CycleOutcome.DeviceError` naming both the
   expected and actual package, baseline untouched, no LOAD_DEX attempted.
3. **Per-package local port**, so two independently-run sessions on one machine don't fight over
   one fixed port. `ReloadOrchestrator.derivePort(pkg)` = `46837 + (pkg.hashCode() and 0x0FFF)` —
   deterministic across separate `bootstrap`/`cycle` processes with no shared state (4096-port
   range, ~1/4096 collision chance between two given packages — documented ceiling).
   `ReloadConfig.localPort` defaults to this; `--port` (new CLI flag, `Main.kt`) overrides it
   explicitly.

## Tests added

- `ReloadOrchestratorTest`: `cycle re-issues the adb forward for its own package before
  compiling` — fake `ProcessRunner`, asserts the forward argv.
- `ReloadOrchestratorTest`: `cycle reports DeviceError when the agent's ping reply names a
  different package, and never reaches LOAD_DEX` — fake TCP agent replying `pong:` + a different
  package; asserts `DeviceError` naming both packages, baseline not saved, only 2 adb calls ran
  (get-state + forward — compile/dex/push never happened).
- `ReloadOrchestratorTest`: `derivePort is deterministic for the same package` /
  `derivePort differs for two different packages`.
- `ProtocolTest`: `pingPackageOf` extracts the package from `"pong:<pkg>"`, returns `null` for
  anything else (`"pong"`, `""`, garbage).
- All pre-existing tests (`AdbTest`, `AgentClientTest`, `ProtocolTest`, `ReloadOrchestratorTest`,
  `MainTest`) still pass.

## Verification

- `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`; root `./gradlew build -x lint` → BUILD
  SUCCESSFUL. `./gradlew :cli:installDist :agent:assembleDebug` → BUILD SUCCESSFUL.
  `strings libhotreload_agent.so | grep pong:` → present on both ABIs; `nm -D ... |
  grep Agent_OnAttach` → exported on both `arm64-v8a` and `x86_64`.
- `ANDROID_SERIAL=R5CX51BENMM e2e/run-e2e.sh` → **E2E PASS** (golden tier-1 reload path +
  incompatible-change path both green).
- **Repro-and-fix, on-device (R5CX51BENMM):**
  - Bootstrapped orderbook, then bootstrapped the sample app → both now land on *different*
    derived ports (`49019` for orderbook, `48026` for sample) instead of colliding on one fixed
    `46837` — layer 3 in effect.
  - Adversarially forced orderbook's own derived port to point at the sample app's socket
    (recreating the exact collision the bug report describes, now on a per-package port instead
    of the old fixed one) and ran `cycle --package com.example.orderbook` with a real edit to
    `ProductCard.kt`:
    ```
    ✓ reloaded 1 class(es) in 6030ms [tier1 — remember state preserved]: com.example.orderbook.catalog.ProductCardKt (compile 4.5s · diff 0.1s · dex 0.9s · push 0.4s · redefine 0.1s)
      ⚠ skipped 2 not-yet-loaded class(es), ...: ComposableSingletons$ProductCardKt$lambda-1$1, ...$lambda-2$1
    ```
    Exit code 0. `adb forward --list` after the cycle showed the mapping self-corrected back to
    `hotreload-agent-com.example.orderbook`. Before the fix this exact setup produced the
    `cannot read dex: /data/data/com.example.orderbook/...` error (captured above); after the
    fix, `cycle()`'s own forward re-issue + identity check make the adversarial repoint a
    non-issue — it self-heals every call.
  - Device correctness: `adb logcat -s HotReload` showed
    `tier1: group-key invalidation, keys=[...]` and `HotReloadAgent: LOAD_DEX: ...: redefined |
    skipped 2: ... | tier1`. `pidof com.example.orderbook` was `5187` before and after the cycle
    (no process restart). `uiautomator dump` on the foregrounded orderbook app showed the edited
    text (`"HOTFIX Assembly Label"`, `"HOTFIX Bassike"`, `"HOTFIX Nike"`) — the edit landed in
    the live app, not the sample app.

## Consumer project (`orderbook-demo`) — what's modified

Only `app/src/main/java/com/example/orderbook/catalog/ProductCard.kt` (the requested test edit —
`CardText`'s brand label text, used as the on-device probe for this fix). `.hotreload/baseline.txt`
and `.hotreload/dex/*.dex` were touched by the `bootstrap`/`cycle` runs used for reproduction and
have been reverted to their committed state via `git checkout`. `app/build.gradle.kts` and
`settings.gradle.kts` show as modified in `git status` but predate this session (already dirty
before any work here — a local-Maven/`includeBuild` setup change, untouched by this task) and
were left as found. Not committed anywhere in that project per instructions.

## Commit

`fix(cli): re-point the adb forward per cycle and verify agent identity` on `main`, pushed to
`origin/main` for CI.
