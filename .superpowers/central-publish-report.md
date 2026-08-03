# Maven Central publish-readiness report

Goal: make `:gradle-plugin` (`dev.thuat:gradle-plugin`) and `:runtime` (`dev.thuat:hotreload-runtime`)
publishable to Maven Central under `dev.thuat`, without actually publishing. Publishing itself is
explicitly out of scope; this covers everything up to it.

## Mechanism chosen

[`com.vanniktech.maven.publish`](https://github.com/vanniktech/gradle-maven-publish-plugin), as
directed — it targets the Central Portal directly (no legacy OSSRH `nexus-staging` flow), and
auto-detects `com.android.library` / `java-gradle-plugin` / `org.jetbrains.kotlin.jvm` to wire up
the right publication, sources jar, and javadoc jar with no hand-rolled bundle-zip/upload code.

**Version pinned: `0.34.0`**, not the newest (`0.37.0`). Verified via the plugin's own changelog
and by hitting real failures locally:
- `0.37.0`/`0.36.0`/`0.35.0` require Kotlin Gradle Plugin ≥ 2.2.0 (repo pins `2.1.0` — bumping
  Kotlin repo-wide was out of scope and riskier than pinning an older, still-fully-Central-Portal
  plugin version).
- `0.35.0` (the newest one still compatible with Kotlin 2.1.0) requires Gradle ≥ 8.13; this repo's
  wrapper is `8.11.1` (deliberately pinned — the CLI's Gradle Tooling API dependency must match the
  wrapper version it connects to). `0.35.0`'s `publishToMavenCentral()` calls
  `ProjectLayout.getSettingsDirectory()`, an API added in Gradle 8.12+, and failed immediately with
  `NoSuchMethodError`-style breakage on 8.11.1.
- `0.34.0` requires only Gradle ≥ 8.5 / Kotlin ≥ 1.9.20 / AGP ≥ 8.0.0 — fits this repo exactly,
  already dropped OSSRH support (Central-Portal-only, same as newer releases), and has config-cache
  support. This is the newest version actually compatible with the existing toolchain; bumping
  Gradle/Kotlin to unlock a newer plugin version is a separate, larger change not requested here.

Both `:gradle-plugin` and `:runtime` build.gradle.kts have near-identical `mavenPublishing { }`
blocks (POM metadata + conditional signing are byte-for-byte the same). I first tried factoring
that into a shared `gradle/publishing.gradle.kts` applied via `apply(from = ...)`, but Kotlin DSL
scripts loaded that way don't see plugin classes brought in through the enclosing script's
`plugins { }` block — `configure<MavenPublishBaseExtension>` failed to resolve (`Unresolved
reference`). Reverted to inline duplication (documented in both files) rather than reaching for
`buildSrc`/a convention plugin for two call sites.

## Work done

- `gradle/libs.versions.toml`: added `vanniktechMavenPublish = "0.34.0"` and the
  `vanniktech-maven-publish` plugin alias.
- `runtime/build.gradle.kts`: replaced the hand-rolled `maven-publish` + `afterEvaluate` publication
  block with `com.vanniktech.maven.publish`'s auto-detected `AndroidSingleVariantLibrary("release")`
  (same single unqualified "release" variant as before, now with sources+javadoc jars for free).
  Added the full POM block, `publishToMavenCentral()`, and conditional `signAllPublications()`.
  Version bumped `0.1.0-SNAPSHOT` → `0.1.2`.
- `gradle-plugin/build.gradle.kts`: same pattern, letting the auto-detected `GradlePlugin` platform
  add jars to both the main `pluginMaven` publication and skip them correctly on the
  `dev.thuat.hotreload` plugin-marker publication. Version bumped to `0.1.2`.
- `gradle-plugin/src/main/kotlin/.../HotReloadPlugin.kt`: `DEFAULT_RUNTIME_COORDINATE` fallback
  constant bumped `0.1.0-SNAPSHOT` → `0.1.2` (kept in sync with the real published version; matching
  test assertion in `HotReloadPluginRootApplyRealBuildTest.kt` updated too).
- `README.md`: Quickstart now leads with a "Maven Central (once `v0.1.2` is published)" section,
  explicitly marked not-live-yet with a link to `docs/releasing.md`, followed by the existing
  JitPack instructions relabeled "JitPack (works today)" — kept fully intact and functional, nothing
  there points at a not-yet-real Central artifact. Fixed the stale `0.1.0-SNAPSHOT` version string in
  the mavenLocal section to `0.1.2`.
- `docs/releasing.md` (new): one-time Central Portal account setup (namespace verification already
  done, token generation, GPG key generation/export — including a documented GnuPG-version gotcha
  hit and reproduced during this work), the exact publish command, CI secret wiring, and the manual
  post-publish steps (Publish button, propagation delay) plus a note that Gradle Plugin Portal setup
  is a deliberately separate future step.

## Signing design

`signAllPublications()` marks GPG signing as **required** for every non-SNAPSHOT version — calling
it unconditionally would break `publishToMavenLocal` for any contributor without a key once the
version left `-SNAPSHOT`. Both modules gate it: `if (project.hasProperty("signingInMemoryKey"))
signAllPublications()`. `ORG_GRADLE_PROJECT_signingInMemoryKey` (env, for CI) or
`signingInMemoryKey` (gradle.properties) is the only trigger; absent, signing is skipped entirely
and the build stays green.

## Verification

**1. `./gradlew build -x lint`** (`JAVA_HOME` = JDK 21) — green, 137 tasks, `BUILD SUCCESSFUL`.

**2. `./gradlew publishToMavenLocal` with no signing key anywhere** (no env vars, empty
`~/.gradle/gradle.properties`) — succeeds, no `.asc` files produced (graceful skip confirmed).
Full POMs, `~/.m2/repository/dev/thuat/{hotreload-runtime,gradle-plugin}/0.1.2/*.pom`:

```xml
<!-- hotreload-runtime-0.1.2.pom -->
<name>Android Hot Reload Runtime</name>
<description>In-app runtime (a ContentProvider + reflection hook into Jetpack Compose's HotReloader) that the android-hot-reload Gradle plugin injects into debug builds so JVMTI-redefined classes trigger a recomposition.</description>
<url>https://github.com/nthuat/android-hot-reload</url>
<licenses><license>
  <name>The Apache License, Version 2.0</name>
  <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
  <distribution>repo</distribution>
</license></licenses>
<developers><developer>
  <id>nthuat</id><name>Thuat Nguyen</name><email>thuat26.ng@gmail.com</email>
</developer></developers>
<scm>
  <connection>scm:git:git://github.com/nthuat/android-hot-reload.git</connection>
  <developerConnection>scm:git:ssh://git@github.com/nthuat/android-hot-reload.git</developerConnection>
  <url>https://github.com/nthuat/android-hot-reload</url>
</scm>
```

```xml
<!-- gradle-plugin-0.1.2.pom -->
<name>Android Hot Reload Gradle Plugin</name>
<description>Gradle plugin (id "dev.thuat.hotreload") that wires the android-hot-reload runtime dependency and Compose compiler function-key metadata flag into a project's debug build.</description>
<url>https://github.com/nthuat/android-hot-reload</url>
<!-- same licenses / developers / scm blocks as above -->
```

Plugin marker POM (`dev/thuat/hotreload/dev.thuat.hotreload.gradle.plugin/0.1.2/...pom`) also
published, correctly separate from the main `gradle-plugin` artifact coordinate.

**3. Sources + javadoc jars, both modules** (`ls ~/.m2/repository/dev/thuat/.../0.1.2/`):
```
gradle-plugin-0.1.2.jar / -sources.jar / -javadoc.jar / .module / .pom
hotreload-runtime-0.1.2.aar / -sources.jar / -javadoc.jar / .module / .pom
```
(Android javadoc jar is real generated API doc output via AGP's bundled Kotlin-doc tooling, not an
empty placeholder — Central-compliant either way.)

**4. Signing, end to end, throwaway key:**
- Generated a throwaway RSA-2048 GPG key in an isolated `GNUPGHOME` (1-day expiry, no passphrase),
  never touching the user's real keyring.
- Hit and fixed a real bug along the way: `ORG_GRADLE_PROJECT_signingInMemoryKeyId` set to GnuPG's
  16-char "long" key id threw `IllegalStateException: The key ID must be in a valid form` — Gradle's
  signing plugin wants the 8-char short form or nothing. Documented in `docs/releasing.md`:
  **omit `signingInMemoryKeyId` entirely** (it's optional; Gradle picks the sole key in the
  in-memory keyring).
- With `ORG_GRADLE_PROJECT_signingInMemoryKey` (full armored key) + `...KeyPassword=""` set,
  `./gradlew publishToMavenLocal` produced `.asc` files for every artifact of both modules
  (`.jar.asc`, `.aar.asc`, `-sources.jar.asc`, `-javadoc.jar.asc`, `.module.asc`, `.pom.asc`,
  including the plugin marker POM) — 12 signature files total.
- Deleted the throwaway `GNUPGHOME` and exported key file, unset the env vars, confirmed
  `~/.gradle/gradle.properties` has no signing entries, then re-ran `publishToMavenLocal` — succeeds
  again with zero `.asc` files. No key material left in the repo or in `~/.gradle/gradle.properties`
  at any point.

**5. `e2e/run-e2e.sh`** — `ANDROID_SERIAL=R5CX51BENMM` (Samsung SM-F731B, Android 15), device
attached and unlocked throughout. Composite build (`sample/` via `includeBuild`) unaffected by the
publishing changes. Result: **E2E PASS** — golden path (`tier1 — remember state preserved`,
`compile 3.7s · dex 1.1s · push 0.2s · redefine 0.1s`) and the incompatible-change rejection path
(`JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED`, exit code 2) both behaved correctly.

**6. Version consistency** — `grep -rln "0.1.0-SNAPSHOT"` outside `build/` now only matches:
  - `gradle-plugin/src/test/kotlin/.../RuntimeCoordinateDerivationTest.kt` — arbitrary fixture
    strings for a generic coordinate-parsing test, not tied to the real project version; left as is.
  - `docs/superpowers/plans/2026-08-01-android-hot-reload-v1.md` and five `.superpowers/*-report.md`
    files — dated historical records of past decisions; rewriting them would misrepresent history,
    left untouched. (This repo's existing convention already keeps historical reports
    frozen — see the many prior `.superpowers/*-report.md` files with their own stale
    coordinates/dates.)
  All *live* build files and docs (`README.md`, both module `build.gradle.kts`, `HotReloadPlugin.kt`)
  are on `0.1.2`.
  `grep -rln "v0.1.1"` still finds it only in the JitPack quickstart section of `README.md` (an
  actually-published, immutable tag — correct to leave) and historical reports; no new `v0.1.2` git
  tag was cut as part of this task since Central publishing (which would make a same-numbered
  release trustworthy) wasn't performed and cutting a same-version GitHub Release without a
  corresponding published artifact would be misleading.

## Exact command to publish, once credentials exist

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
# ORG_GRADLE_PROJECT_mavenCentralUsername / ...mavenCentralPassword / ...signingInMemoryKey /
# ...signingInMemoryKeyPassword set as env vars (CI) or in ~/.gradle/gradle.properties (local)
./gradlew :gradle-plugin:publishToMavenCentral :hotreload-runtime:publishToMavenCentral
```
Then go to https://central.sonatype.com/publishing/deployments and click **Publish** to release
(automatic release was deliberately not enabled). Full walkthrough, including GPG key generation/
export and the propagation-delay expectations, in `docs/releasing.md`.

## Constraints honored

- Did not publish to Maven Central or the Gradle Plugin Portal.
- Did not touch `/Users/admin/Projects/Interview/Mobile/demos/orderbook-demo`.
- Composite build (`sample/`) and mavenLocal routes both still work — e2e (which depends on the
  composite route) passed.
- No credentials or key material committed; nothing added to `~/.gradle/gradle.properties`.
