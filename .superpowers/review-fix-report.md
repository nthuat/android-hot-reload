# Fix wave: 10 code-review findings — report

Branch `feature/v1`, PR #1. All 10 findings implemented; none contested.

## Commits

1. `0bd1f50` fix(cli): F1, F2, F5, F6, F8 (CLI side), F9 (CLI side), F10 (CLI side)
2. `0d37c23` fix(agent): F3, F4, F8 (agent side), F9 (agent side), F10 (agent side)
3. `d8a9677` fix(runtime): F7
4. `a0d2db5` test(sample): member-composable fixture (ScreenHost) for F7 evidence
5. `1c74b7b` docs: spec updates for protocol/socket/bootstrap changes

Pushed to `origin/feature/v1` (`9fc94e0..1c74b7b`); PR #1 CI will re-run.

## Per-finding summary

- **F1** — Every `adb push`/`run-as`/`attach-agent`/`forward` exit code is now checked in
  `bootstrap()` and `cycle()`; non-zero → `DeviceError` with stderr, baseline never advances.
  Each cycle's device dex filename is content-hash-prefixed so a failed push can't leave a
  stale file to be silently redefined. Old dex files under `code_cache/hotreload` are not
  cleaned up (documented, acceptable for v1 — see `ReloadOrchestrator.kt` comment).
  Unit-tested with a fake `ProcessRunner`.
- **F2** — `ClassDiffer`/`BaselineStore` now key by `(classDir, relPath)` via a new `ClassKey`,
  not bare `relPath`. Baseline file format bumped to v2 (tab-delimited, not shared/checked-in
  state, safe to bump). Tested with two module dirs sharing a relative path.
- **F3** — `ReleaseStringUTFChars` only called when `GetStringUTFChars` returned non-null.
- **F4** — `g_started` now latches only after `ServerThread` reports a successful bind+listen
  via a condvar; `pthread_create`'s return value is checked; thread is detached.
- **F5** — `Main.kt`'s watch filter is now segment-based (`isWatchableDir`, pure, tested), not
  substring-matching the absolute path.
- **F6** — `DexPackager` matches D8's file-per-class output by package-qualified relative path,
  not bare simple name; output filenames are fully-qualified. Tested with two same-simple-name
  classes in different packages sharing one merged dex.
- **F7** — `ComposeInvalidator.keysForClass` now tries both `<outer>$KeyMeta` and
  `<outer>Kt$KeyMeta` candidates, unioning keys, using the redefined class's own classloader.
  New `ScreenHost.Body` member-composable fixture in the sample, wired into `MainActivity`.
  **On-device evidence** (Pixel_3a_API_34_x86_64 emulator): editing `ScreenHost.Body`'s body and
  running `cli cycle` produced:
  ```
  ✓ reloaded 1 class(es) in 3799ms [tier1 — remember state preserved]: dev.thuat.hotreload.sample.feature.ScreenHost
  ```
  logcat: `HotReload: tier1: group-key invalidation, keys=[-1589430184]` and
  `HotReloadAgent: LOAD_DEX: Ldev/thuat/hotreload/sample/feature/ScreenHost;: redefined | tier1`.
  UI showed the edited text; counter state (`Count: 0`, unrelated to the edited file) survived;
  no crash.
- **F8** — `Protocol.STATUS_ERROR` (0x03) added, distinct from `STATUS_FAIL`; agent returns it
  for malformed-payload/unreadable-dex paths (environmental), keeps `STATUS_FAIL` for real
  `RedefineClasses`/incompatibility rejections; orchestrator maps `STATUS_ERROR` → `DeviceError`.
- **F9** — LOAD_DEX now carries every changed class from one edit as a single message (records
  joined by `Protocol.RECORD_SEP`, 0x1E). Orchestrator pushes all dex files first, then sends
  one batched message; agent resolves every class before calling `RedefineClasses(n, defs)`
  once — atomic, no mid-batch partial swap. `NotifyRuntime`/`ComposeInvalidator.reload` now
  receive every redefined class's binary name in one call.
- **F10** — Socket renamed per-package (`hotreload-agent-<package>`, derived from
  `/proc/self/cmdline`). Every accepted connection is authenticated via `SO_PEERCRED`.
  **Empirically verified on-device**: legitimate `adb forward`-bridged CLI connections arrive
  as adbd's own uid, not the app's — on this Pixel_3a_API_34 x86_64 emulator, adbd runs as
  `AID_ROOT` (uid 0), logged as `accepted peer uid=0 pid=430 (self uid=10195)` in earlier runs
  and `uid=2000 pid=430` (`AID_SHELL`) after a later `pm clear`/relaunch — both accepted per the
  documented policy (self uid, or root, or shell); any other uid is rejected and logged.

## An undocumented bug found and fixed along the way

Stress-testing bootstrap (required to exercise F10's SO_PEERCRED path repeatedly) surfaced a
real, reproducible on-device SIGSEGV: **calling `bootstrap()` a second time while an agent from
a previous `bootstrap()` was already attached and running crashed the app.** Root-caused by
bisection (isolated agent.cpp from CLI, direct rapid pings vs. repeated `adb push`+`run-as cp`,
reverted to the pre-fix-wave agent.cpp to confirm it reproduces there too) to: `bootstrap()`
unconditionally re-pushes and re-`cp`s `agent.so` on every call, even when already attached.
`cp` truncates-and-rewrites its destination in place (same inode, not an atomic rename), and the
target file is the exact one the *already-running* process has `mmap`'d as executable code — a
not-yet-faulted-in code page can read back mismatched bytes the next time it's touched. Fixed by
having `bootstrap()` ping first and return immediately if an already-attached agent is already
responsive, only falling through to push/copy/attach when it isn't. This is folded into the F1
commit (`ReloadOrchestrator.kt`) since it's the same "don't touch device state you don't need
to" theme, and is called out explicitly in that commit message and the updated spec doc.
(This is *not* one of the 10 numbered findings — flagging it here since it wasn't in the
original review and materially changed `bootstrap()`'s shape.)

## Verification (all done)

1. `./gradlew build -x lint` — green (clean build, `./gradlew clean build -x lint`).
2. `./gradlew :agent:assembleDebug` — `nm`/`llvm-nm -D` shows `Agent_OnAttach` in both
   `arm64-v8a` and `x86_64` `.so` outputs.
3. `e2e/run-e2e.sh` — PASS x3 consecutively (Pixel_3a_API_34_x86_64, already-running healthy
   emulator reused per instructions), tier1 line present in each run.
4. F7 member-composable tier-1 evidence — see above.
5. `sample/app assembleRelease` — `strings` on `classes*.dex` from the unsigned release APK
   shows only the `androidx.compose.runtime.internal.FunctionKeyMeta*` annotation types
   themselves, no generated `<Facade>$KeyMeta` classes (`GreetingKt$KeyMeta`,
   `ScreenHostKt$KeyMeta`, etc. all absent) — confirms the pre-existing release-strip fix still
   holds with the new `ScreenHost.kt` file in the module.

## Nothing contested

All 10 findings matched the code as described and were fixed as specified; no finding was found
to be wrong on closer inspection.
