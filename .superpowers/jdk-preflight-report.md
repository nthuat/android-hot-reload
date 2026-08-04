# JDK preflight check — verification report

## The defect

When the CLI drove a consumer's Gradle build with a JVM too new for that project's Gradle
version, the only output was a bare version number under `* What went wrong:` — no mention of
Java, Gradle, or what to do about it — categorised as a `compile error`. Root cause, confirmed by
instrumenting a direct `GradleCompiler` call against `gradle-tooling-api:8.11.1` under a real JDK
26: Gradle's own `org.gradle.api.JavaVersion.toVersion(String)` throws
`IllegalArgumentException(rawVersionString)` when it doesn't recognize a JDK feature release —
the cause chain ends in `java.lang.IllegalArgumentException: 26.0.2`, and that bare `26.0.2` is
exactly what reaches the console.

## Fix

- `cli/src/main/kotlin/dev/thuat/hotreload/cli/JdkPreflight.kt` (new): pure version-ceiling logic
  (`gradleJdkCeiling`, sourced from Gradle's own compatibility matrix, doc'd inline with where
  every number comes from), reads the *consumer* project's `gradle/wrapper/gradle-wrapper.properties`
  to learn their Gradle version, falls back to this tool's own bundled Tooling API version
  (`8.11.1`, matches `gradle/libs.versions.toml`) when no wrapper file exists at all — that's a
  known version, not an unknown one — and only falls back to a genuinely conservative floor (JDK
  19) when a wrapper file exists but its version can't be parsed.
- `Main.kt` runs `jdkPreflightCheck` once at startup, before `bootstrap`/`cycle`/`run` touch any
  device or compile work.
- `ReloadOrchestrator.cycle()` also runs `unsupportedJvmHint` on any compile failure to catch the
  late case (project-pinned `org.gradle.java.home`, a toolchain mismatch) that preflight can't see
  coming — appends the same actionable hint, never swallows the raw Gradle output.
- New `CycleOutcome.EnvironmentError` (exit code 3, same taxonomy slot as `DeviceError` — 0 ok /
  1 compile / 2 incompatible / 3 environment-or-device) replaces the wrong `DeviceError`/
  `CompileError` categorisation.
- `--java-home <path>` (`GradleCompiler.compile()` → `BuildLauncher.setJavaHome(File)`) lets a
  user point just this tool's build daemon at a supported JDK without touching their shell.
- README's CLI details section documents the failure mode and both fixes.

## Tests

- `cli/src/test/kotlin/dev/thuat/hotreload/cli/JdkPreflightTest.kt` — 19 pure-function tests:
  ceiling table boundaries (8.3/8.5/8.8/8.10/8.14/9.0/9.1/9.4), unknown-version conservative
  fallback, at/below/above-ceiling pass/fail, the bare-version late-case detector (positive and
  negative), wrapper-file parsing (present/absent/unparseable), and an unreadable `--java-home`
  override not false-positiving.
- `GradleCompilerIntegrationTest` — two new tests: a bogus `--java-home` reaches the connector and
  fails (proves it isn't dropped), a valid one still builds (proves it's forwarded correctly).
- `HotReloadPluginRootApplyRealBuildTest` untouched — still not re-pinned to a specific version.

## Verification

**1. Build green**

```
$ ./gradlew build -x lint            → BUILD SUCCESSFUL
$ ./gradlew :cli:installDist         → BUILD SUCCESSFUL (exit 0)
```

**2. Reproduce the original failure, before/after**

System default JDK is 26 (`/usr/libexec/java_home` with no `-v`). Reproduced honestly per the
task brief: a scratch clone of this repo with `sample/gradle/gradle-daemon-jvm.properties`
removed (the pin that would otherwise force JDK 21 regardless of `JAVA_HOME`), sample app built
and installed on the attached physical device (`R5CX51BENMM`, Samsung SM-F731B, Android 15),
bootstrapped once under JDK 21, then `cycle` run under `JAVA_HOME=`JDK 26.

Before (current `main` at the time, i.e. pre-fix code, same scratch setup):

```
✗ compile error:

FAILURE: Build failed with an exception.

* What went wrong:
26.0.2

* Try:
...
Could not execute build using connection to Gradle distribution 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip'.
exit=1
```

After (fixed code, identical setup):

```
✗ environment: JDK 26 is too new for Gradle 8.11.1, which supports up to JDK 23 for the build daemon.
  → macOS: export JAVA_HOME=$(/usr/libexec/java_home -v 21)
  → or: pass --java-home <path to a JDK 23 or older>
exit=3
```

Note the ceiling resolved to `Gradle 8.11.1` (this tool's bundled Tooling API version — the
scratch sample has no wrapper file of its own, exactly like the real `sample/`), not the
maximally-conservative fallback — confirmed empirically that a wrapper-less project really does
run on the Tooling API's bundled Gradle version.

**3. `--java-home` fixes it**

Same scratch setup, `JAVA_HOME` still pointing at JDK 26, `--java-home $(/usr/libexec/java_home -v 21)`:

```
✓ reloaded 1 class(es) in 4611ms [tier1 — remember state preserved]: dev.thuat.hotreload.sample.feature.GreetingKt (compile 3.9s · diff 0.0s · dex 0.5s · push 0.2s · redefine 0.1s)
exit=0
```

A real reload on the physical device, tier1 (state preserved).

**4. `e2e/run-e2e.sh`**

Device lock check first, as required:

```
$ adb -s R5CX51BENMM shell dumpsys trust | grep deviceLocked
 User "Chủ sở hữu" (id=0, ...) trustState=UNTRUSTED, trustManaged=0, deviceLocked=0, ...
```

`deviceLocked=0` — unlocked. Ran with `ANDROID_SERIAL=R5CX51BENMM`, `JAVA_HOME` = JDK 21:

```
== golden path: edit composable body, cycle, assert new text + preserved state ==
✓ reloaded 1 class(es) in 3659ms [tier1 — remember state preserved]: ...
== incompatible path: add a function, expect exit 2 and clean error ==
✗ incompatible change: RedefineClasses failed: JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED ...
E2E PASS
```

No preflight/environment output anywhere in this run — confirms requirement 5 (no false positive
on the normal path) against the real, correctly-configured JDK 21 + pinned `sample/` setup.

**5. Negative control: Gradle 9.5 (JetNews) does not false-positive**

`/Users/admin/Projects/compose-samples/JetNews` (Gradle 9.5.0), `JAVA_HOME` = JDK 26 (too new for
Gradle 8.x, fine for 9.5's ceiling of 26):

```
$ hotreload bootstrap --project .../JetNews --package com.example.jetnews --serial R5CX51BENMM ...
✓ reloaded 0 class(es) in 0ms:
exit=0
```

No environment error — preflight correctly let it through. Isolated further with a bogus
`--serial` (so nothing but preflight + the immediate `adb get-state` call can run): still no
environment error, failure surfaces as the expected `device/agent: adb get-state failed`, proving
preflight passed silently rather than being skipped by some other path.

## Scope notes

- No Maven Central publish, no tags/releases cut.
- Version stayed at 0.1.4, not bumped.
- `orderbook-demo` untouched; `compose-samples` (throwaway) used only as the Gradle-9.5 negative
  control, no modifications made.
