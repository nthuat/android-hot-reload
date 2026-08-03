# Derive runtime coordinate from the plugin's own resolved location

## What changed

`HotReloadPlugin` no longer defaults `runtimeCoordinate` to a hardcoded
`dev.thuat:hotreload-runtime:<version>` string. Instead, at `apply()` time it inspects the jar
the plugin class itself was loaded from (`HotReloadPlugin::class.java.protectionDomain
.codeSource.location`) and derives group+version from it, then combines that with the fixed
artifact id `hotreload-runtime` (the runtime always publishes alongside the plugin, same group,
same version, in whatever repo resolved the plugin).

Derivation order, each pure and unit-tested:
1. `META-INF/maven/*/pom.properties` inside the jar, if present.
2. Gradle module-cache layout — anchored on the `files-2.1` marker segment.
3. mavenLocal/Maven-repo layout — anchored on the `.m2/repository` marker segments (not "count
   from the artifact backwards to the root", which would swallow the absolute filesystem prefix
   into the "group" — caught by a failing test during development and fixed).

Falls back to the old hardcoded `dev.thuat:hotreload-runtime:0.1.0-SNAPSHOT` default, logged at
`project.logger.info`, when none of the above apply — this is the expected, normal path for
`includeBuild` composite builds, where Gradle substitutes the included project regardless of
what coordinate string is configured.

`hotreload.runtimeCoordinate.set(...)` is unchanged and still wins over derivation (Gradle
`Property` convention semantics: an explicit `.set()` always overrides `.convention()`).

New file: `gradle-plugin/src/main/kotlin/dev/thuat/hotreload/gradle/RuntimeCoordinateDerivation.kt`
(pure parsing functions + the jar-reading entry point).
New tests: `gradle-plugin/src/test/kotlin/dev/thuat/hotreload/gradle/RuntimeCoordinateDerivationTest.kt`.

## Commits

- `740dad2` fix: derive hotreload-runtime coordinate from the plugin's own jar
- `4d57932` docs: drop runtimeCoordinate override from quickstart, bump to v0.1.1
- Tag `v0.1.1` cut and pushed, JitPack build confirmed green.

## Verification

### 1. Root build
```
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew build -x lint         → BUILD SUCCESSFUL (135 tasks)
./gradlew :cli:installDist      → BUILD SUCCESSFUL
./gradlew :gradle-plugin:test   → BUILD SUCCESSFUL, all 13 RuntimeCoordinateDerivationTest
                                   cases + both existing HotReloadPluginTest cases pass
```

### 2. Composite build (sample/, includeBuild) — PARTIAL
No emulator or physical device was reachable in this session (`adb devices` returned an empty
list throughout; no Android device visible in `system_profiler SPUSBDataType` either) — despite
the task description assuming `R5CX51BENMM` was attached. **`e2e/run-e2e.sh` could not be run**;
this is not proven end-to-end.

What *was* verified: `cd sample && ../gradlew :app:assembleDebug -x lint` succeeds, and
`--info` output confirms the exact fallback path this case is supposed to exercise:
```
hotreload: could not derive the runtime coordinate from the plugin's own classpath location
(/Users/.../transforms/.../gradle-plugin-0.1.0-SNAPSHOT.jar) — falling back to the built-in
default 'dev.thuat:hotreload-runtime:0.1.0-SNAPSHOT'. This is expected for composite
builds/includeBuild...
```
Notably, under `includeBuild` the plugin *is* loaded from a jar (Gradle materializes one via an
artifact transform) — not a bare classes directory as originally assumed — but its path matches
neither the Gradle-cache nor the Maven-repo marker, so derivation correctly returns null and the
old default (which the composite substitution resolves correctly, as before) is used unchanged.
Build succeeds; the reload-cycle and on-device text assertions in `run-e2e.sh` remain unproven
pending device availability.

### 3. mavenLocal — PASS
```
./gradlew publishToMavenLocal
```
Throwaway copy of `sample/` outside the repo (deleted after), `settings.gradle.kts` switched to
`mavenLocal(); google(); mavenCentral()` only (no `includeBuild`), plugin applied as
`id("dev.thuat.hotreload") version "0.1.0-SNAPSHOT"`, **no `runtimeCoordinate` block**:
```
./gradlew :app:dependencies --configuration debugRuntimeClasspath
...
+--- dev.thuat:hotreload-runtime:0.1.0-SNAPSHOT
```
Confirmed on-disk layout matches the parser's assumption:
`~/.m2/repository/dev/thuat/gradle-plugin/0.1.0-SNAPSHOT/gradle-plugin-0.1.0-SNAPSHOT.jar`.

### 4. JitPack (tag v0.1.1) — PASS
Cut `v0.1.1` from the fix, pushed, polled `https://jitpack.io/api/builds/com.github.nthuat/
android-hot-reload/v0.1.1` — came back `"status":"ok"` (already built by the time it was
checked). Verified both artifacts serve directly:
```
gradle-plugin/v0.1.1/gradle-plugin-v0.1.1.jar        → HTTP 200
hotreload-runtime/v0.1.1/hotreload-runtime-v0.1.1.aar → HTTP 200
```
Throwaway copy of `sample/` outside the repo (deleted after) consuming
`maven("https://jitpack.io")` at `v0.1.1`, **no `runtimeCoordinate` block**:
```
./gradlew :app:dependencies --configuration debugRuntimeClasspath
...
+--- com.github.nthuat.android-hot-reload:hotreload-runtime:v0.1.1
```
Confirmed cache layout matches the parser's assumption:
`~/.gradle/caches/modules-2/files-2.1/com.github.nthuat.android-hot-reload/gradle-plugin/v0.1.1/
<sha1>/gradle-plugin-v0.1.1.jar`.

### 5. v0.1.1 release
README quickstart updated (dropped the `runtimeCoordinate` block, bumped `v0.1.0` → `v0.1.1`).
`./gradlew :cli:distZip` → `cli.zip` attached to a new GitHub release:
https://github.com/nthuat/android-hot-reload/releases/tag/v0.1.1
Asset download verified: `curl -L .../v0.1.1/cli.zip` → 16.8 MB, unzips to
`cli/lib/cli.jar` + `cli/agent/{arm64-v8a,x86_64}/libhotreload_agent.so` + launch scripts.

### 6. Real device reload — NOT DONE
No device or emulator was available in this session, so the final "one real reload on the
physical device, tier1, pid unchanged, on-device text changed" proof could not be performed.
**This step remains outstanding** — re-run `e2e/run-e2e.sh` (or a manual `cli run` cycle) once
`R5CX51BENMM` (or any API 26+ device) is actually reachable via `adb devices`, against whichever
consumer setup (mavenLocal is fastest) is convenient.

## Outstanding

- Composite-build e2e (`e2e/run-e2e.sh`) and the on-device reload proof are unverified pending
  device/emulator access — everything else (unit tests, root build, mavenLocal, JitPack, release)
  is verified with evidence above.
