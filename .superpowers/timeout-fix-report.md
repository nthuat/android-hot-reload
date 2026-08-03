# Timeout fix: bound adb and agent-socket calls so a dead device fails fast

## Bug

Emulator went `offline` mid-session (wedged, contended host). The next `cli cycle` hung
indefinitely — no output, no error, no timeout — until `kill -9` (exit 137). Root cause: no
timeouts anywhere in the device path.

- `RealProcessRunner.run()` called `proc.waitFor()` with no timeout. Worse, it read
  `stdout`/`stderr` to completion *before* `waitFor()`, so even adding a timed `waitFor()` alone
  would not have helped — a hung process never closes its pipes, so the blocking
  `readText()` call would still wait forever.
- `AgentClient` opened a `Socket(host, port)` with no connect timeout, and read the reply with no
  `SO_TIMEOUT` — a dead/wedged agent blocked the read forever.

## Fix

1. **`ProcessRunner.kt`** — `ProcessRunner.run()` now takes a `timeoutMs` parameter
   (default `DEFAULT_ADB_TIMEOUT_MS = 30_000`). `RealProcessRunner` drains stdout/stderr on
   background daemon threads concurrently with a timed `proc.waitFor(timeoutMs, MILLISECONDS)`,
   so the timeout actually bounds the call instead of being moot behind a blocking read. On
   expiry: `proc.destroyForcibly()` and returns `ProcessResult(-1, ..., timedOut = true)` — a
   distinct flag rather than an overloaded exit code.
2. **`AgentClient.kt`** — `Socket()` + `connect(InetSocketAddress, connectTimeoutMs)` with
   `DEFAULT_CONNECT_TIMEOUT_MS = 5_000`, and `soTimeout = readTimeoutMs` with
   `DEFAULT_READ_TIMEOUT_MS = 15_000` (comfortably above the runtime's internal ~2s tier-1 wait
   and the normal ~100-900ms round trip).
3. **`ReloadOrchestrator.kt`**:
   - `failureOrNull()` now checks `timedOut` first and reports a message naming the timeout
     (e.g. `"push agent.so timed out after 30s — device may be offline or unresponsive; check
     \`adb devices\`, and re-run \`bootstrap\` after restarting the app"`), distinct from a
     normal non-zero exit code.
   - New `agentFailureMessage()` distinguishes `SocketTimeoutException` (agent-socket timeout)
     from any other connection failure, so the message names which side stalled.
   - New `deviceNotReadyError()` runs `adb.getState()` (new `Adb.getState()`, wraps
     `adb get-state`) at the very start of both `bootstrap()` and `cycle()` — fails fast with
     `DeviceError` if the serial is gone or the device isn't in state `"device"` (offline/
     unauthorized), before sinking a compile or any device I/O into a dead device. Checked in
     `cycle()` too, not just `bootstrap()`, since a device can die between cycles — exactly what
     was reproduced.
4. All timeout/offline paths map to `CycleOutcome.DeviceError` → exit code 3 (existing
   `Main.kt` `exitWith` mapping, unchanged).

## Tests added

- `ProcessRunnerTest.kt` (new): `RealProcessRunner.run()` against a real `sleep 30` process with
  an injected 200ms timeout — asserts it returns `timedOut = true` and completes in well under
  5s, plus a fast-path sanity check that a normal command is not marked timed out.
- `AgentClientTest.kt`: new test against a loopback `ServerSocket` that accepts but never
  replies, with an injected 200ms read timeout — asserts `SocketTimeoutException` within the
  bound instead of hanging.
- `ReloadOrchestratorTest.kt`:
  - `bootstrap fails fast with DeviceError when device is offline, before any other adb call` —
    fake runner returns `"offline\n"` for `get-state`; asserts `DeviceError` naming "offline" and
    exactly one adb call (nothing else attempted).
  - `cycle fails fast with DeviceError when device is offline, before compiling` — same, for
    `cycle()`.
  - `bootstrap reports DeviceError naming the timeout when an adb call times out, and does not
    save baseline` — fake runner returns `ProcessResult(timedOut = true)` for the `push
    agent.so` step; asserts `DeviceError` reason contains "timed out" and
    `.hotreload/baseline.txt` is not written.
  - Existing bootstrap-failure tests updated for the new leading `get-state` call in the adb
    sequence (call-count assertion bumped 4 → 5).
- All pre-existing tests still pass unmodified in behavior (only the `ProcessRunner`/
  `SequencedRunner` fakes' signatures were updated to the new `run(args, timeoutMs)` shape).

## Verification

1. `./gradlew build -x lint` — **BUILD SUCCESSFUL** (root build, all modules, `JAVA_HOME` = 21).
2. `e2e/run-e2e.sh` — **E2E PASS** against the booted `Pixel_3a_API_34_extension_level_7_x86_64`
   emulator (golden reload path with `tier1`, and the incompatible-change path with exit 2).
3. **Dead-device demo (real, not simulated in a unit test)**:
   - `cli cycle --serial emulator-9999 ...` (nonexistent serial): exits immediately —
     `✗ device/agent: adb get-state failed (exit 1): error: device 'emulator-9999' not found`,
     **exit code 3**, elapsed **0s**.
   - `cli cycle --adb <fake wedged adb script that sleeps 3600s> ...` (reproduces the actual
     reported hang — a call that never returns): —
     `✗ device/agent: adb get-state timed out after 30s — device may be offline or unresponsive;
     check \`adb devices\`, and re-run \`bootstrap\` after restarting the app`,
     **exit code 3**, elapsed **30s** (bounded by `DEFAULT_ADB_TIMEOUT_MS`, not infinite).
4. **Healthy-path regression check**: real `bootstrap` + `cycle` against
   `com.example.orderbook` on `emulator-5554` (orderbook-demo, reinstalled + relaunched since the
   emulator was restarted) — `bootstrap` succeeded in 6s; `cycle` on an edited `ProductCard.kt`
   Composable reported
   `✓ reloaded 1 class(es) in 27879ms [tier1 — remember state preserved]: ... (compile 20.6s ·
   diff 0.5s · dex 3.3s · push 2.8s · redefine 0.7s)`, exit 0 — none of the new timeouts fired
   during normal operation.
