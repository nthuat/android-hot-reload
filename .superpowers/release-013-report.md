# 0.1.3 release prep — status report

## Summary

Steps 1–4 are done and verified. Steps 5–6 (physical-device proof: JetNews AGP 9
reload + `e2e/run-e2e.sh`) are **blocked**: device `R5CX51BENMM` is not connected
to this machine (`adb devices` empty, and it doesn't show up in
`system_profiler SPUSBDataType` either — not a lock-screen issue, it's simply not
plugged in / paired right now). Commits are made locally on `main` but **not
pushed**, since the task's own verification checklist requires the on-device AGP 9
proof before calling this done, and I won't fabricate that evidence.

## Commits (local main, not yet pushed)

- `9b6e6bf` — `revert: remove hotReloadInstallCli task, deferred to 0.1.4`
  (clean, self-contained; a plain `git revert 9b6e6bf` restores
  `InstallCliTask.kt`, `CliInstallSupport.kt`, the `HotReloadPlugin.kt`
  registration, and both test files, since nothing has touched these files
  since `eabfc56`)
- `c3d8bdb` — `release: bump version to 0.1.3, update README for deferred CLI task`
  (build files, `DEFAULT_RUNTIME_COORDINATE`, matching test assertion,
  README + CONTRIBUTING quickstart, README's Gradle-task callout now says
  "0.1.4+ ... not available yet")

Both were already present in the working tree when this session started —
I verified their content rather than re-doing the work, then ran the full
verification pass below.

## Verification evidence

**1. `./gradlew build -x lint` — green, no `JAVA_HOME` set.**
```
$ echo $JAVA_HOME   → (empty)
$ java -version      → openjdk 26.0.2 (system default, NOT 21)
$ cat gradle/gradle-daemon-jvm.properties
toolchainVersion=21
$ ./gradlew build -x lint
BUILD SUCCESSFUL in 4s
137 actionable tasks: 8 executed, 129 up-to-date
```
`gradle-daemon-jvm.properties` pinning to 21 still means `gradlew` works with an
unset `JAVA_HOME` and a JDK 26 system default — confirmed.
Also ran `:gradle-plugin:test` directly — green — to double check the
`CliInstallSupportTest` removal and the trimmed
`HotReloadPluginCoordinatorTest` didn't break anything.

**2. `./gradlew :cli:installDist` — green, both ABI `.so` present.**
```
BUILD SUCCESSFUL in 1s
31 actionable tasks: 4 executed, 27 up-to-date
$ find cli/build/install -iname '*.so'
cli/build/install/cli/agent/arm64-v8a/libhotreload_agent.so
cli/build/install/cli/agent/x86_64/libhotreload_agent.so
```

**3. `./gradlew publishToMavenLocal` — both artifacts at 0.1.3, complete POMs, signed.**
```
$ ls ~/.m2/repository/dev/thuat/hotreload-runtime/0.1.3/
hotreload-runtime-0.1.3.aar(.asc) .module(.asc) .pom(.asc) -sources.jar(.asc) -javadoc.jar(.asc)
$ ls ~/.m2/repository/dev/thuat/gradle-plugin/0.1.3/
gradle-plugin-0.1.3.jar(.asc) .module(.asc) .pom(.asc) -sources.jar(.asc) -javadoc.jar(.asc)
```
Plugin marker POM also published:
`~/.m2/repository/dev/thuat/hotreload/dev.thuat.hotreload.gradle.plugin/0.1.3/...pom(.asc)`.
Both POMs checked for `<name> <description> <url> <licenses> <developers> <scm>` —
all present in both.

**4. Task genuinely absent from the built 0.1.3 plugin (mavenLocal consumer).**
Built a throwaway consumer project (`/tmp/hotreload-consumer-013`, deleted after)
applying `id("dev.thuat.hotreload") version "0.1.3"` from `mavenLocal` root-style
(coordinator mode). `./gradlew tasks --all` succeeded (proves the plugin resolved
and configured cleanly) and:
```
$ ./gradlew tasks --all | grep -i hotreload
(no output)
```
No `hotReloadInstallCli`, no hotreload task of any kind — confirmed gone from the
published 0.1.3 artifact.

**5. JetNews AGP 9 physical-device reload — BLOCKED, not run.**
`adb devices -l` returns empty and the Samsung device isn't present in
`system_profiler SPUSBDataType` — `R5CX51BENMM` is not physically connected /
paired to this machine right now, so `dumpsys trust` can't even be queried.
This is not a "locked screen" situation — the device isn't reachable at all.
Did NOT touch JetNews (manifest `overrideLibrary` workaround still in place,
untested), did NOT install/bootstrap/edit anything on-device. No AGP 9 reload
evidence, no tier, no timing.

**6. `e2e/run-e2e.sh` — BLOCKED, not run** (same device dependency as #5).

## What's needed to finish

Connect `R5CX51BENMM` (USB or paired), then:
- re-check `adb -s R5CX51BENMM shell dumpsys trust | grep deviceLocked`,
- in JetNews, remove the `tools:overrideLibrary="dev.thuat.hotreload.runtime"`
  manifest workaround, point it at 0.1.3 from mavenLocal, rebuild, install,
  bootstrap, edit a composable, confirm reload + pid-unchanged + note tier/timing,
- run `e2e/run-e2e.sh` on the same device for the AGP 8 no-regression check.

Once both pass, push `main` (currently 2 commits ahead of `origin/main`,
unpushed) — no tags/GitHub release/Maven Central publish, per the maintainer's
instruction that those are manual Portal-click steps.
