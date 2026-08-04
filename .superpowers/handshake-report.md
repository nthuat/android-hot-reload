# Runtime/CLI version handshake — fix report

## The defect

`PING` returned only `pong:<pkg>`, no version. The agent ships inside `cli.zip` (always matched
to the CLI), but the runtime library comes from the Gradle plugin version, set independently by
the consumer project. A newer CLI talking to an older runtime hit exactly the failure mode this
closes: 0.1.5 added Compose group keys to `LOAD_DEX`'s wire format; against an older runtime,
`ComposeInvalidator.reload`'s new signature makes `GetStaticMethodID` fail, the agent skips the
notify call, and the CLI still prints `✓ reloaded` — silently-wrong state.

## The mechanism

1. **Runtime exposes its version.** `runtime/build.gradle.kts` now generates a `BuildConfig`
   constant (`HOTRELOAD_RUNTIME_VERSION`) from the module's own `version`, via AGP's built-in
   `buildConfigField` (no new dependency). `ComposeInvalidator.runtimeVersion(): String` (new
   `@JvmStatic` method) returns it.
2. **Agent reports it.** `agent.cpp`'s new `ReadRuntimeVersion` calls that method via JNI and
   falls back to the literal `"unknown"` if the class isn't loaded or the method doesn't exist
   (`GetStaticMethodID`'s pending `NoSuchMethodError` is cleared, never thrown). PING replies now
   carry `"pong:<pkg>:<runtimeVersion>"`.
3. **Protocol.kt** documents the extended framing byte-for-byte against `agent.cpp`, keeps
   `pingPackageOf` working (splits on the *first* `:` after the prefix — package names can't
   contain `:`), and adds `pingRuntimeVersionOf` / `UNKNOWN_RUNTIME_VERSION`.
4. **CLI's own version** is baked into the jar from `cli/build.gradle.kts`'s own `version =
   "0.1.6"` via a templated classpath resource (`hotreload-cli-version.txt`, `Copy.expand`), read
   by the new `CliVersion.kt` — not a second hand-maintained literal.
5. **Comparison rule: exact match.** `ReloadOrchestrator.checkRuntimeVersion` (pure, unit-tested)
   returns `CycleOutcome.DeviceError` naming both versions plus the fix (`hotReloadInstallCli` or
   pin `HOTRELOAD_VERSION`) only on a genuine mismatch. `null`/`"unknown"` is *not* a mismatch —
   it's surfaced as a warning (`CycleOutcome.Reloaded.warning`, printed by `Main.kt`) so the CLI
   stays usable against every already-published runtime, none of which speak this handshake yet.
   Documented as exact-match rather than a range: the protocol has already changed once
   mid-series with no compatibility shim, so no verified range exists to encode.
6. Wired into **both** `bootstrap()` (both its ping-success paths) and `cycle()`'s
   `verifyAgentIdentity` (renamed internally to return an `IdentityCheck{error, warning}` so the
   warning threads through to the eventual `Reloaded` outcome).

## Tests added

- `ProtocolTest`: extended-PING round trip, old two-field reply → `null` version (not a crash),
  the explicit `"unknown"` literal, and a version string containing colons/other unusual
  characters (`0.1.6-SNAPSHOT+build:42 (dirty) ☕`) — package extraction and version extraction
  both still correct.
- `ReloadOrchestratorTest`: pure `checkRuntimeVersion`/`unknownRuntimeVersionWarning` unit tests,
  plus `bootstrap()`/`cycle()` integration tests via a fake one-shot PING socket server: matching
  version proceeds with no warning; mismatched version → `DeviceError` naming both versions and
  stops before pushing the agent `.so` / before LOAD_DEX; unknown version proceeds with a
  warning naming the CLI's version.
- All pre-existing tests still pass unmodified.

## Verification

**1. Build — all green:**
```
./gradlew build -x lint :cli:installDist :agent:assembleDebug
BUILD SUCCESSFUL in 6s   144 actionable tasks: 14 executed, 130 up-to-date
```
`Agent_OnAttach` confirmed exported for both ABIs:
```
arm64-v8a/libhotreload_agent.so: 000000000007a328 T Agent_OnAttach
x86_64/libhotreload_agent.so:    0000000000077440 T Agent_OnAttach
```

**2. Matching versions, real device (`R5CX51BENMM`, Samsung SM-F731B, Android 15):**
`adb shell dumpsys trust` → `deviceLocked=0` (unlocked) before touching it.
`ANDROID_SERIAL=R5CX51BENMM e2e/run-e2e.sh` →
```
✓ reloaded 0 class(es) in 0ms: 
✓ reloaded 1 class(es) in 3625ms [tier1 — remember state preserved]: dev.thuat.hotreload.sample.feature.GreetingKt (compile 2.9s · diff 0.0s · dex 0.5s · push 0.2s · redefine 0.0s)
✗ incompatible change: ... (structural changes are unsupported in v1 — rebuild)
E2E PASS
```
Reaches tier1, no warning printed (both sides at 0.1.6) — no regression.

**3. Genuine mismatch → exit 3.** A real published runtime can't demonstrate a *known* mismatch —
every published version (≤0.1.5) predates this handshake entirely and reports "unknown" (see #4).
To exercise the actual mismatch code path, the runtime module was temporarily republished to
`~/.m2` under a distinguishable version (`0.1.6-fakemismatch`) — same handshake code, different
version string — then immediately reverted (`git diff runtime/build.gradle.kts` shows only the
tracked `0.1.6`; the fake-version artifact has been deleted from `~/.m2` post-verification). A
scratch app (outside the repo, deleted after) declared `debugImplementation("dev.thuat:hotreload-runtime:0.1.6-fakemismatch")`
directly. Driving it with the locally-built CLI:
```
✗ device/agent: runtime version mismatch: this CLI is 0.1.6 but the on-device runtime library is
  0.1.6-fakemismatch — align them: run './gradlew hotReloadInstallCli' in the consumer project,
  or pin the plugin version to 0.1.6 (e.g. HOTRELOAD_VERSION=v0.1.6 with install.sh)
EXIT CODE: 3
```
Both versions named, exit 3, no `✓ reloaded`.

**4. Unknown case — what a real 0.1.5 runtime actually produces.** A scratch app (outside the
repo, deleted after) applied `dev.thuat.hotreload` version **0.1.5** at the root only, pulling
the real, published `dev.thuat:hotreload-runtime:0.1.5` from Maven Central (confirmed via
`:app:dependencies`). Driving it with the locally-built 0.1.6 CLI:
```
⚠ on-device runtime version unknown (predates this handshake) — this CLI is 0.1.6; verify the
  plugin version matches it if reload behaves oddly
✓ reloaded 0 class(es) in 0ms: 
EXIT CODE: 0
```
**This is the intended, documented outcome**: 0.1.5 predates `ComposeInvalidator.runtimeVersion()`
entirely, so the agent's `GetStaticMethodID` lookup fails and it reports `"unknown"`, not a
mismatch — the CLI warns and proceeds rather than hard-failing every runtime published before
today. A genuine *mismatch* (item 3) can only ever fire between two builds that both speak this
handshake (≥0.1.6-and-beyond); day one, everything already published takes the unknown path.

Both scratch apps and the fake `~/.m2` artifact have been deleted; the mismatch-test device
package (`dev.thuat.hotreload.sample`, reused across both scratch apps and the earlier e2e run)
has been uninstalled from the device.

## Constraints honored

No publish, no tags/releases, version stays `0.1.6`. Nothing under
`/Users/admin/Projects/Interview/Mobile/demos/orderbook-demo` or `/Users/admin/Projects/compose-samples`
was touched.
