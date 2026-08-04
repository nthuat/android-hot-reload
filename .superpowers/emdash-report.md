# Em-dash / en-dash copy-editing pass

## Scope and approach

Removed every em-dash (`—`) from user-facing text: `README.md`, `docs/CONTRIBUTING.md`,
`docs/releasing.md`, the two `warn()` strings in `install.sh`, and every string literal
(not comment) in `cli/src/main`, `runtime/src/main`, `gradle-plugin/src/main`, and
`agent/src/main/cpp/agent.cpp`. Each sentence was rewritten individually — colon, comma,
semicolon, parentheses, full stop + new sentence, or a small restructure, chosen per
sentence rather than one mechanical substitution. Code comments were left untouched, as
instructed. En-dashes used as punctuation were also removed; the two numeric ranges found
(`~1.7–3.8s`, `15–30 minutes`) were converted to plain-hyphen ranges (`~1.7-3.8s`,
`15-30 minutes`), not stripped of their range meaning.

## Consistency fix: tier suffix format

`Main.kt`'s `tierSuffix` changed from `" [$it — ${...}]"` to `" [$it: ${...}]"`. Every
README code block showing sample CLI output (`[tier1 — remember state preserved]`) was
updated to match (`[tier1: remember state preserved]`). Verified against a real device
reload — see evidence below.

## Files changed

**Docs/prose** (commit `bc9e1c7`): `README.md`, `docs/CONTRIBUTING.md`,
`docs/releasing.md`, `install.sh`.

**Code string literals** (commit `8cad966`): `cli/src/main/kotlin/dev/thuat/hotreload/cli/Main.kt`,
`ReloadOrchestrator.kt`, `CliVersion.kt`, `DexPackager.kt`,
`gradle-plugin/src/main/kotlin/dev/thuat/hotreload/gradle/HotReloadPlugin.kt`,
`agent/src/main/cpp/agent.cpp`.

No test asserted the exact em-dash-bearing strings (only comments in test files referenced
them), so no test files needed updating. `./gradlew build -x lint` passed with these
changes in place.

## Em-dash counts, before → after

| File | Before | After |
|---|---|---|
| README.md | 34 | 0 |
| docs/CONTRIBUTING.md | 5 | 0 |
| docs/releasing.md | 15 | 0 |
| install.sh | 6 (2 in user-facing `warn()` strings, 4 in comments) | 4 (all in comments, left alone per scope) |
| cli/src/main, runtime/src/main, gradle-plugin/src/main, agent/src/main/cpp (string literals only) | ~19 | 0 |

Remaining em-dashes anywhere in scope are exclusively inside `//` / `/** */` code
comments, which the task explicitly says to leave alone (verified by grepping and
excluding comment-prefixed lines).

## Verification

1. `grep -c '—' README.md docs/CONTRIBUTING.md docs/releasing.md install.sh` →
   `README.md:0`, `docs/CONTRIBUTING.md:0`, `docs/releasing.md:0`, `install.sh:4` (the 4
   are code comments, correctly untouched). Zero em-dashes remain in string literals
   under `cli/src/main`, `runtime/src/main`, `gradle-plugin/src/main`,
   `agent/src/main/cpp` (confirmed by filtering out comment-prefixed lines from the
   grep results).
2. `./gradlew build -x lint` → `BUILD SUCCESSFUL in 1m 24s`, 143 actionable tasks, all
   unit test suites (cli, gradle-plugin, runtime) green.
3. Device check: `adb -s R5CX51BENMM shell dumpsys trust | grep deviceLocked` →
   `deviceLocked=1` (device attached but **locked**). `e2e/run-e2e.sh` was run anyway
   and failed at `E2E FAIL: baseline UI not visible` (exit 1) because its
   `uiautomator dump`/tap-based assertions need an unlocked screen; this is a device
   state issue, not a regression from this change. To get real evidence of the actual
   reload output despite the lock, the CLI's `bootstrap`/`cycle` commands were run
   directly against the sample app on the same device via `adb`/`am start` (which work
   fine on a locked screen) — see the output below, captured from an actual on-device
   reload.
4. **Real CLI output** (from the manual `cli cycle` run against `sample/`, device
   `R5CX51BENMM`):
   ```
   ✓ reloaded 1 class(es) in 4754ms [tier1: remember state preserved]: dev.thuat.hotreload.sample.feature.GreetingKt (compile 3.8s · diff 0.0s · dex 0.6s · push 0.2s · redefine 0.1s)
   ```
   **README's matching code block** (`README.md` lines 220-221, format-identical, sample
   numbers):
   ```
   ✓ reloaded 1 class(es) in 1980ms [tier1: remember state preserved]: com.example.FooKt
     (compile 0.8s · diff 0.0s · dex 0.7s · push 0.4s · redefine 0.1s)
   ```
   The tier-suffix and phase-timing format match verbatim (`[tier1: ...]`, the `·`-joined
   phase breakdown in parens). Numbers differ because it's a different run/machine, as
   expected.
5. README re-read top to bottom after editing. It reads naturally; no section needed a
   substantial restructure beyond the sentence-level em-dash removal. The three-step
   quickstart and all `<details>` sections are unchanged in structure.

## Outstanding item

`e2e/run-e2e.sh` did **not** PASS in this session because device `R5CX51BENMM` was
locked throughout (`deviceLocked=1`), which blocks its `uiautomator`/`input tap`-based UI
assertions. This is unrelated to the em-dash changes (the underlying `cycle` command,
called directly, works and produces the exact output shown above, including the new
`tier1:` format). Re-run `e2e/run-e2e.sh` after unlocking the device to get a clean PASS.
