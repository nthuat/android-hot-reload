# Release-process fix report

Fixes the process that shipped a stale `cli.zip` on the `v0.1.6` GitHub release (built before the
version-handshake commits, even though the tag contained them). The published asset was already
corrected by hand before this work started; this fix is about the process, so it can't happen
again silently.

## What changed

1. **Pinned the `distZip`/`distTar` layout** (`cli/build.gradle.kts`): `distributionBaseName` set
   to `"cli"` and `archiveVersion` cleared on both archive tasks, so the output is always
   `cli.zip`/`cli.tar` with a `cli/` root directory, regardless of the project's `version`. Before
   this, adding `version` (needed for `CliVersion.kt`) silently renamed the output to
   `cli-<version>.zip` with a `cli-<version>/` root, which both `install.sh` and `InstallCliTask`
   hard-code as `cli/`.
2. **Added `scripts/verify-release-asset.sh`** (POSIX `sh`): checks a `cli.zip` (local path or
   downloaded from a release tag) for exactly one `cli/bin/cli` entry, both agent `.so` files
   (`arm64-v8a`, `x86_64`), a versioned jar name (`cli/lib/cli-<version>.jar`), and that the jar's
   own `hotreload-cli-version.txt` agrees with the version argument. Fails loudly with a specific
   message per broken invariant.
3. **Updated `docs/releasing.md`** with an explicit, ordered CLI-zip section: build from a clean
   checkout of the tag, verify the local zip, upload, verify the published URL. States the
   `v0.1.6` incident as the reason. CI auto-verification on release publish was **not** wired in:
   it would need a `release: { types: [published] }` job, and what to *do* on a mismatch
   (un-publish? file an issue?) is a bigger design question than this fix covers, so it's called
   out as a manual step in the doc rather than half-wired.
4. **Added `--version` / `-v`** to the CLI (`Main.kt`), checked before `--project`/`--package` are
   even parsed, printing `CliVersion.VERSION` and exiting 0. Backed by a pure `isVersionFlag`
   predicate with unit tests (`MainTest.kt`).

Left untouched: an unrelated, already-in-progress uncommitted change (app-module auto-detection
across `ReloadOrchestrator.kt`, `HotReloadPlugin.kt`, `HotReloadWrapperScript.kt`,
`InstallCliTask.kt`, `AppModuleHint.kt` and their tests) that was sitting in the working tree
before this task started. It's a separate feature, not part of this release-process fix, so it
was neither committed nor reverted.

## Commits (pushed to `origin/main`)

- `53a9740` fix(cli): pin distZip layout to cli.zip with a cli/ root, independent of version
- `d80c28f` feat(cli): add --version / -v flag
- `bc6172c` chore(release): add scripts/verify-release-asset.sh
- `4b0beef` docs(releasing): wire release asset verification into the CLI zip process

## Verification evidence

### 1. `./gradlew build -x lint`

```
BUILD SUCCESSFUL in 4s
143 actionable tasks: 11 executed, 132 up-to-date
```

### 2. `./gradlew :cli:distZip` from a clean state, project version still `0.1.6`

```
$ ./gradlew :cli:clean :cli:distZip
...
BUILD SUCCESSFUL in 23s

$ unzip -l cli/build/distributions/cli.zip
Archive:  cli/build/distributions/cli.zip
  Length      Date    Time    Name
---------  ---------- -----   ----
        0  ...   cli/
        0  ...   cli/lib/
   108281  ...   cli/lib/cli-0.1.6.jar
        ...
        0  ...   cli/bin/
     8970  ...   cli/bin/cli
     3188  ...   cli/bin/cli.bat
        0  ...   cli/agent/
        0  ...   cli/agent/arm64-v8a/
  2453008  ...   cli/agent/arm64-v8a/libhotreload_agent.so
        0  ...   cli/agent/x86_64/
  2262808  ...   cli/agent/x86_64/libhotreload_agent.so
```

File is `cli.zip` (not `cli-0.1.6.zip`), root directory is `cli/` (not `cli-0.1.6/`), jar inside
is still correctly versioned (`cli-0.1.6.jar`), matching the "correct build" signature the
incident report named.

### 3. Verifier: PASS against the published `v0.1.6` asset, FAIL against a deliberately broken zip

**Pass, against the corrected published asset:**
```
$ sh scripts/verify-release-asset.sh 0.1.6
verify-release-asset.sh: downloading https://github.com/nthuat/android-hot-reload/releases/download/v0.1.6/cli.zip
verify-release-asset.sh: PASS: /var/folders/.../cli.zip matches version 0.1.6 (cli/bin/cli present once, both agent .so present, jar name and baked-in version agree).
```

**Fail #1** (constructed by deleting `cli/agent/x86_64/libhotreload_agent.so` from a known-good zip):
```
$ sh scripts/verify-release-asset.sh 0.1.6 bad.zip
verify-release-asset.sh: checking local zip bad.zip against version 0.1.6
verify-release-asset.sh: FAIL: missing cli/agent/x86_64/libhotreload_agent.so
```

**Fail #2** (constructed by renaming `cli/lib/cli-0.1.6.jar` to `cli/lib/cli.jar` inside a
known-good zip, reproducing the actual `v0.1.6` incident shape):
```
$ sh scripts/verify-release-asset.sh 0.1.6 incident-shaped.zip
verify-release-asset.sh: checking local zip incident-shaped.zip against version 0.1.6
verify-release-asset.sh: FAIL: no cli/lib/cli-<version>.jar entry found -- found a bare cli.jar instead? That's the v0.1.6 stale-build symptom: this project's version property renames the jar, so its absence means the archive predates that commit.
```

### 4. `--version`

```
$ /tmp/hotreload-version-check/cli/bin/cli --version
0.1.6
$ /tmp/hotreload-version-check/cli/bin/cli -v
0.1.6
```

### 5. `e2e/run-e2e.sh`

Device `R5CX51BENMM`: `dumpsys trust` showed `deviceLocked=0` (unlocked). Load average was 3.58
(not the reported 25); ran `./gradlew --stop` first regardless (stopped 2 stray daemons).

```
== build tool + agent + sample ==
BUILD SUCCESSFUL in 14s
...
== install + launch ==
Success
== click counter twice (state probe) ==
== bootstrap ==
✓ reloaded 0 class(es) in 0ms:
== golden path: edit composable body, cycle, assert new text + preserved state ==
✓ reloaded 1 class(es) in 9108ms [tier1: remember state preserved]: dev.thuat.hotreload.sample.feature.GreetingKt (...)
== incompatible path: add a function, expect exit 2 and clean error ==
✗ incompatible change: RedefineClasses failed: JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED (structural changes are unsupported in v1; rebuild)
  → run a full rebuild + reinstall, then 'hotreload bootstrap' again
E2E PASS
```

## Cleanup

- Removed the leftover worktree at `/tmp/v016` (`git worktree remove /tmp/v016 --force`).
- No Maven Central publish, no tags cut, no release assets re-uploaded.
