# Android Hot Reload v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Open-source, editor-agnostic hot reload for Jetpack Compose on real Android devices: save a `.kt` file, see the composable body change on device in a few seconds with state preserved.

**Architecture:** A JVM CLI watches sources, compiles via the Gradle tooling API, diffs class outputs, dexes changed classes with D8, pushes them over ADB, and a JVMTI agent inside the debuggable app calls `RedefineClasses` then triggers whole-app recomposition through a small runtime library. Spec: `docs/superpowers/specs/2026-08-01-android-hot-reload-design.md`.

**Tech Stack:** Kotlin 2.1.0, Gradle 8.11.1 + tooling API, AGP 8.7.3, Compose BOM 2024.12.01, `com.android.tools:r8:8.5.35` (D8), NDK r27 + CMake 3.22.1 (C++ JVMTI agent), JUnit 4, GitHub Actions + android-emulator-runner.

## Global Constraints

- minSdk 26 (JVMTI attach floor), compileSdk 35, JVM target 17.
- Debuggable builds only; the tool must never affect release builds.
- Reliability rule from spec: never leave the app in silently-wrong state — every failure surfaces to the CLI user with a stated fallback (rebuild or `Activity.recreate()`).
- Maven coordinates: group `dev.hotreload`, version `0.1.0-SNAPSHOT`. Package prefix `dev.hotreload.*`.
- Agent socket name (abstract namespace): `hotreload-agent`. Wire protocol: request `[4-byte BE length][1-byte cmd][payload]`, reply `[4-byte BE length][1-byte status][UTF-8 detail]`. Commands: `0x01` PING, `0x02` LOAD_DEX. Statuses: `0x00` OK, `0x02` FAIL.
- On-device paths: push to `/data/local/tmp/hotreload/`, then `run-as <pkg> cp` into the app's `code_cache/hotreload/` (SELinux blocks agent loading from `/data/local/tmp`).
- File length ≤ 800 lines; prefer small focused files. Immutable data classes for results.

## File Structure

```
android-hot-reload/
├── settings.gradle.kts            # :cli, :gradle-plugin, :runtime, :agent
├── build.gradle.kts               # plugin versions only
├── gradle/libs.versions.toml
├── cli/
│   └── src/main/kotlin/dev/hotreload/cli/
│       ├── Main.kt                # arg parsing, subcommands: bootstrap|cycle|run
│       ├── Protocol.kt            # wire framing (shared spec with agent)
│       ├── AgentClient.kt         # TCP socket to forwarded agent port
│       ├── ClassDiffer.kt         # baseline capture + changed-class detection
│       ├── BaselineStore.kt       # persist baseline to .hotreload/baseline.json
│       ├── DexPackager.kt         # D8: one .class -> one .dex
│       ├── ModuleResolver.kt      # source file -> gradle module path
│       ├── GradleCompiler.kt      # tooling API incremental compile
│       ├── Adb.kt                 # adb command construction + execution
│       ├── ProcessRunner.kt       # interface + real impl (for Adb testability)
│       └── ReloadOrchestrator.kt  # cycle 0 bootstrap + one reload cycle + watch loop
│   └── src/test/kotlin/dev/hotreload/cli/   # unit tests per file above
├── gradle-plugin/
│   └── src/main/kotlin/dev/hotreload/gradle/HotReloadPlugin.kt
│   └── src/test/kotlin/dev/hotreload/gradle/HotReloadPluginTest.kt  # TestKit
├── runtime/
│   └── src/main/kotlin/dev/hotreload/runtime/
│       ├── ComposeInvalidator.kt  # reflective HotReloader invalidate-all + recreate fallback
│       ├── ActivityTracker.kt     # foreground activity via lifecycle callbacks
│       └── HotReloadInitProvider.kt  # ContentProvider auto-init
│   └── src/main/AndroidManifest.xml
├── agent/
│   ├── build.gradle.kts           # android library, externalNativeBuild
│   └── src/main/cpp/
│       ├── CMakeLists.txt
│       ├── include/jvmti.h        # vendored from OpenJDK (GPLv2+Classpath Exception, see LICENSE note)
│       └── agent.cpp              # Agent_OnAttach, socket server, RedefineClasses, JNI notify
├── sample/                        # standalone composite build (mirrors real usage)
│   ├── settings.gradle.kts        # includeBuild("..") + :app, :feature
│   ├── app/                       # applies dev.hotreload plugin, Compose activity
│   └── feature/                   # library module with Greeting() composable (multi-module proof)
├── e2e/
│   └── run-e2e.sh                 # emulator golden path + incompatible-change path
└── .github/workflows/ci.yml
```

---

### Task 1: Repo scaffold + sample app

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`
- Create: `cli/build.gradle.kts`, `gradle-plugin/build.gradle.kts`, `runtime/build.gradle.kts`, `agent/build.gradle.kts` (agent native build lands in Task 9 — plain empty android-library here)
- Create: `runtime/src/main/AndroidManifest.xml`
- Create: `sample/settings.gradle.kts`, `sample/build.gradle.kts`, `sample/gradle.properties`, `sample/app/**`, `sample/feature/**`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: buildable multi-project; `sample/feature` exposes `dev.hotreload.sample.feature.Greeting(name: String)` composable and `sample/app` shows it plus a `remember` counter button (state-preservation probe used by E2E). Runtime module publishes coordinates `dev.hotreload:runtime:0.1.0-SNAPSHOT` consumed via composite build.

- [ ] **Step 1: Root build files**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "android-hot-reload"
include(":cli", ":gradle-plugin", ":runtime", ":agent")
```

`gradle/libs.versions.toml`:
```toml
[versions]
kotlin = "2.1.0"
agp = "8.7.3"
composeBom = "2024.12.01"
r8 = "8.5.35"
toolingApi = "8.11.1"

[libraries]
gradle-tooling-api = { module = "org.gradle:gradle-tooling-api", version.ref = "toolingApi" }
r8 = { module = "com.android.tools:r8", version.ref = "r8" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
junit4 = { module = "junit:junit", version = "4.13.2" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
android-library = { id = "com.android.library", version.ref = "agp" }
android-application = { id = "com.android.application", version.ref = "agp" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

Root `build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```

`gradle.properties`:
```properties
android.useAndroidX=true
org.gradle.jvmargs=-Xmx2g
```

- [ ] **Step 2: Tool module build files**

`cli/build.gradle.kts`:
```kotlin
plugins { alias(libs.plugins.kotlin.jvm); application }
application { mainClass.set("dev.hotreload.cli.MainKt") }
dependencies {
    implementation(libs.gradle.tooling.api)
    implementation(libs.r8)
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16") // tooling API logs
    testImplementation(libs.junit4)
    testImplementation(kotlin("test"))
}
kotlin { jvmToolchain(17) }
```

`gradle-plugin/build.gradle.kts`:
```kotlin
plugins { alias(libs.plugins.kotlin.jvm); `java-gradle-plugin` }
group = "dev.hotreload"
version = "0.1.0-SNAPSHOT"
gradlePlugin {
    plugins {
        create("hotreload") {
            id = "dev.hotreload"
            implementationClass = "dev.hotreload.gradle.HotReloadPlugin"
        }
    }
}
dependencies {
    testImplementation(libs.junit4)
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}
kotlin { jvmToolchain(17) }
```

`runtime/build.gradle.kts`:
```kotlin
plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
group = "dev.hotreload"
version = "0.1.0-SNAPSHOT"
android {
    namespace = "dev.hotreload.runtime"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // compileOnly: the app supplies its own Compose runtime; we only reflect into it
    compileOnly(platform(libs.compose.bom))
    compileOnly("androidx.compose.runtime:runtime")
}
```

`runtime/src/main/AndroidManifest.xml`:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <provider
            android:name="dev.hotreload.runtime.HotReloadInitProvider"
            android:authorities="${applicationId}.hotreload-init"
            android:exported="false" />
    </application>
</manifest>
```

`agent/build.gradle.kts` (native build added in Task 9):
```kotlin
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.hotreload.agent"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}
```

Placeholder Kotlin sources are NOT needed — empty modules build fine.

- [ ] **Step 3: Sample composite build**

`sample/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    includeBuild("..")
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
includeBuild("..")
rootProject.name = "hotreload-sample"
include(":app", ":feature")
```

`sample/gradle.properties`:
```properties
android.useAndroidX=true
org.gradle.jvmargs=-Xmx2g
```

`sample/build.gradle.kts`: same plugin aliases block as root, all `apply false`, plus a local versions copy — simplest is a literal plugins block:
```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
```

`sample/feature/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.hotreload.sample.feature"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
}
```

`sample/feature/src/main/kotlin/dev/hotreload/sample/feature/Greeting.kt`:
```kotlin
package dev.hotreload.sample.feature

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun Greeting(name: String) {
    Text(text = "Hello, $name!")
}
```

`sample/app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dev.hotreload")
}
android {
    namespace = "dev.hotreload.sample"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.hotreload.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}
dependencies {
    implementation(project(":feature"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
}
```
NOTE: `id("dev.hotreload")` will fail until Task 8 ships the plugin. Until then leave the line commented with `// enable in Task 8: id("dev.hotreload")` and add it in Task 8.

`sample/app/src/main/AndroidManifest.xml`:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="HotReload Sample" android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```
(Add `implementation("androidx.appcompat:appcompat:1.7.0")` to app deps for the theme, or use `android:theme="@android:style/Theme.Material.Light.NoActionBar"` and skip the dependency — choose the latter, fewer deps.)

`sample/app/src/main/kotlin/dev/hotreload/sample/MainActivity.kt`:
```kotlin
package dev.hotreload.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hotreload.sample.feature.Greeting

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(modifier = Modifier.padding(32.dp)) {
                Greeting(name = "World")
                var count by remember { mutableIntStateOf(0) }
                Button(onClick = { count++ }) { Text("Count: $count") }
            }
        }
    }
}
```
The counter is the E2E state-preservation probe: click it, reload `Greeting`, assert count survives.

- [ ] **Step 4: Verify both builds**

Run: `./gradlew build -x lint` (root; use `gradle wrapper --gradle-version 8.11.1` first to generate the wrapper)
Expected: BUILD SUCCESSFUL

Run: `cd sample && ../gradlew :app:assembleDebug -x lint`
Expected: BUILD SUCCESSFUL, `sample/app/build/outputs/apk/debug/app-debug.apk` exists

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: repo scaffold with cli/gradle-plugin/runtime/agent modules and sample composite build"
```

---

### Task 2: Wire protocol framing (CLI side)

**Files:**
- Create: `cli/src/main/kotlin/dev/hotreload/cli/Protocol.kt`
- Test: `cli/src/test/kotlin/dev/hotreload/cli/ProtocolTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```kotlin
  object Protocol {
      const val CMD_PING: Byte = 0x01
      const val CMD_LOAD_DEX: Byte = 0x02
      const val STATUS_OK: Byte = 0x00
      const val STATUS_FAIL: Byte = 0x02
      fun encodeRequest(cmd: Byte, payload: ByteArray): ByteArray
      fun decodeReply(input: java.io.InputStream): Reply
  }
  data class Reply(val status: Byte, val detail: String)
  ```
  LOAD_DEX payload text format (UTF-8): `"<class descriptor>\n<device dex path>"`, e.g. `"Ldev/hotreload/sample/feature/GreetingKt;\n/data/data/dev.hotreload.sample/code_cache/hotreload/GreetingKt.dex"`.

- [ ] **Step 1: Write failing tests**

```kotlin
package dev.hotreload.cli

import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.test.assertEquals

class ProtocolTest {
    @Test
    fun `encodeRequest frames cmd and payload with BE length prefix`() {
        val payload = "hello".toByteArray()
        val framed = Protocol.encodeRequest(Protocol.CMD_LOAD_DEX, payload)
        val buf = ByteBuffer.wrap(framed)
        assertEquals(payload.size + 1, buf.int)          // length covers cmd byte + payload
        assertEquals(Protocol.CMD_LOAD_DEX, buf.get())
        val rest = ByteArray(payload.size); buf.get(rest)
        assertEquals("hello", String(rest))
    }

    @Test
    fun `encodeRequest with empty payload frames just the cmd`() {
        val framed = Protocol.encodeRequest(Protocol.CMD_PING, ByteArray(0))
        val buf = ByteBuffer.wrap(framed)
        assertEquals(1, buf.int)
        assertEquals(Protocol.CMD_PING, buf.get())
    }

    @Test
    fun `decodeReply parses status and detail`() {
        val detail = "GreetingKt: ok".toByteArray()
        val frame = ByteBuffer.allocate(4 + 1 + detail.size)
            .putInt(1 + detail.size).put(Protocol.STATUS_OK).put(detail).array()
        val reply = Protocol.decodeReply(ByteArrayInputStream(frame))
        assertEquals(Protocol.STATUS_OK, reply.status)
        assertEquals("GreetingKt: ok", reply.detail)
    }

    @Test(expected = java.io.EOFException::class)
    fun `decodeReply throws on truncated stream`() {
        Protocol.decodeReply(ByteArrayInputStream(byteArrayOf(0, 0, 0, 5, 0)))
    }
}
```

- [ ] **Step 2: Run tests, verify fail**

Run: `./gradlew :cli:test --tests "dev.hotreload.cli.ProtocolTest"`
Expected: FAIL — `Protocol` unresolved

- [ ] **Step 3: Implement**

```kotlin
package dev.hotreload.cli

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

data class Reply(val status: Byte, val detail: String)

object Protocol {
    const val CMD_PING: Byte = 0x01
    const val CMD_LOAD_DEX: Byte = 0x02
    const val STATUS_OK: Byte = 0x00
    const val STATUS_FAIL: Byte = 0x02

    fun encodeRequest(cmd: Byte, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + 1 + payload.size)
            .putInt(1 + payload.size)
            .put(cmd)
            .put(payload)
            .array()

    fun decodeReply(input: InputStream): Reply {
        val data = DataInputStream(input)
        val len = data.readInt()
        if (len < 1) throw EOFException("invalid reply length $len")
        val status = data.readByte()
        val detail = ByteArray(len - 1)
        data.readFully(detail)
        return Reply(status, String(detail, Charsets.UTF_8))
    }
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :cli:test --tests "dev.hotreload.cli.ProtocolTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add cli/src
git commit -m "feat: wire protocol framing for CLI-agent socket"
```

---

### Task 3: Class diff + baseline store

**Files:**
- Create: `cli/src/main/kotlin/dev/hotreload/cli/ClassDiffer.kt`
- Create: `cli/src/main/kotlin/dev/hotreload/cli/BaselineStore.kt`
- Test: `cli/src/test/kotlin/dev/hotreload/cli/ClassDifferTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```kotlin
  data class ChangedClass(val classFile: java.nio.file.Path, val binaryName: String, val descriptor: String)
  // binaryName "dev.hotreload.sample.feature.GreetingKt", descriptor "Ldev/hotreload/sample/feature/GreetingKt;"

  class ClassDiffer {
      fun snapshot(classDirs: List<java.nio.file.Path>): Map<String, String>  // relPath -> sha256 hex
      fun diff(baseline: Map<String, String>, current: Map<String, String>, classDirs: List<java.nio.file.Path>): DiffResult
  }
  data class DiffResult(val changed: List<ChangedClass>, val added: List<String>, val removed: List<String>)
  // added/removed are relPaths — v1 treats them as incompatible (rebuild path)

  class BaselineStore(val file: java.nio.file.Path) {  // <project>/.hotreload/baseline.json
      fun load(): Map<String, String>   // empty map if missing
      fun save(snapshot: Map<String, String>)
  }
  ```

- [ ] **Step 1: Write failing tests**

```kotlin
package dev.hotreload.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassDifferTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun writeClass(dirName: String, relPath: String, content: String): java.nio.file.Path {
        val f = tmp.root.toPath().resolve(dirName).resolve(relPath)
        java.nio.file.Files.createDirectories(f.parent)
        java.nio.file.Files.write(f, content.toByteArray())
        return f
    }

    @Test
    fun `unchanged tree diffs to empty`() {
        writeClass("out", "com/foo/Bar.class", "AAAA")
        val differ = ClassDiffer()
        val dirs = listOf(tmp.root.toPath().resolve("out"))
        val base = differ.snapshot(dirs)
        val result = differ.diff(base, differ.snapshot(dirs), dirs)
        assertTrue(result.changed.isEmpty() && result.added.isEmpty() && result.removed.isEmpty())
    }

    @Test
    fun `modified class shows up with binary name and descriptor`() {
        writeClass("out", "com/foo/Bar.class", "AAAA")
        val differ = ClassDiffer()
        val dirs = listOf(tmp.root.toPath().resolve("out"))
        val base = differ.snapshot(dirs)
        writeClass("out", "com/foo/Bar.class", "BBBB")
        val result = differ.diff(base, differ.snapshot(dirs), dirs)
        assertEquals(1, result.changed.size)
        assertEquals("com.foo.Bar", result.changed[0].binaryName)
        assertEquals("Lcom/foo/Bar;", result.changed[0].descriptor)
    }

    @Test
    fun `new class file is reported as added not changed`() {
        writeClass("out", "com/foo/Bar.class", "AAAA")
        val differ = ClassDiffer()
        val dirs = listOf(tmp.root.toPath().resolve("out"))
        val base = differ.snapshot(dirs)
        writeClass("out", "com/foo/New.class", "CCCC")
        val result = differ.diff(base, differ.snapshot(dirs), dirs)
        assertTrue(result.changed.isEmpty())
        assertEquals(listOf("com/foo/New.class"), result.added)
    }

    @Test
    fun `baseline store round-trips and defaults to empty`() {
        val store = BaselineStore(tmp.root.toPath().resolve(".hotreload/baseline.json"))
        assertTrue(store.load().isEmpty())
        store.save(mapOf("a/B.class" to "abc123"))
        assertEquals(mapOf("a/B.class" to "abc123"), store.load())
    }
}
```

- [ ] **Step 2: Run tests, verify fail**

Run: `./gradlew :cli:test --tests "dev.hotreload.cli.ClassDifferTest"`
Expected: FAIL — unresolved references

- [ ] **Step 3: Implement**

`ClassDiffer.kt`:
```kotlin
package dev.hotreload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.relativeTo
import kotlin.streams.asSequence

data class ChangedClass(val classFile: Path, val binaryName: String, val descriptor: String)
data class DiffResult(val changed: List<ChangedClass>, val added: List<String>, val removed: List<String>)

class ClassDiffer {
    fun snapshot(classDirs: List<Path>): Map<String, String> =
        classDirs.filter(Files::isDirectory).flatMap { dir ->
            Files.walk(dir).use { stream ->
                stream.asSequence()
                    .filter { it.toString().endsWith(".class") }
                    .map { it.relativeTo(dir).toString().replace('\\', '/') to sha256(it) }
                    .toList()
            }
        }.toMap()

    fun diff(baseline: Map<String, String>, current: Map<String, String>, classDirs: List<Path>): DiffResult {
        val changed = current.filter { (rel, hash) -> baseline[rel] != null && baseline[rel] != hash }
            .keys.map { rel ->
                val binaryName = rel.removeSuffix(".class").replace('/', '.')
                ChangedClass(
                    classFile = classDirs.map { it.resolve(rel) }.first(Files::exists),
                    binaryName = binaryName,
                    descriptor = "L${rel.removeSuffix(".class")};",
                )
            }
        return DiffResult(
            changed = changed,
            added = (current.keys - baseline.keys).sorted(),
            removed = (baseline.keys - current.keys).sorted(),
        )
    }

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
            .joinToString("") { "%02x".format(it) }
}
```

`BaselineStore.kt` (no JSON library — format is `hash relPath` lines, one per class):
```kotlin
package dev.hotreload.cli

import java.nio.file.Files
import java.nio.file.Path

class BaselineStore(val file: Path) {
    fun load(): Map<String, String> {
        if (!Files.exists(file)) return emptyMap()
        return Files.readAllLines(file).filter { it.isNotBlank() }.associate { line ->
            val (hash, rel) = line.split(' ', limit = 2)
            rel to hash
        }
    }

    fun save(snapshot: Map<String, String>) {
        Files.createDirectories(file.parent)
        Files.write(file, snapshot.map { (rel, hash) -> "$hash $rel" }.sorted())
    }
}
```
(File keeps `.json` name from spec? No — name it `baseline.txt`; update `BaselineStore` callers accordingly. Test above uses the path it's given, so no test change needed.)

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :cli:test --tests "dev.hotreload.cli.ClassDifferTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add cli/src
git commit -m "feat: class output diffing and baseline persistence"
```

---

### Task 4: Dex packaging (D8)

**Files:**
- Create: `cli/src/main/kotlin/dev/hotreload/cli/DexPackager.kt`
- Test: `cli/src/test/kotlin/dev/hotreload/cli/DexPackagerTest.kt`

**Interfaces:**
- Consumes: `ChangedClass` from Task 3.
- Produces:
  ```kotlin
  class DexPackager(private val minApi: Int = 26) {
      // one .class -> one single-class dex named <SimpleName>.dex in outDir; returns dex path
      fun dexClass(changed: ChangedClass, outDir: java.nio.file.Path): java.nio.file.Path
  }
  ```

- [ ] **Step 1: Write failing tests**

The test compiles a tiny Java class at runtime (javax.tools) so no fixture files are needed:

```kotlin
package dev.hotreload.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DexPackagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun compileFixture(): ChangedClass {
        val src = tmp.root.toPath().resolve("Fixture.java")
        Files.write(src, "public class Fixture { public int answer() { return 42; } }".toByteArray())
        val rc = ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", tmp.root.absolutePath, src.toString())
        assertEquals(0, rc)
        return ChangedClass(tmp.root.toPath().resolve("Fixture.class"), "Fixture", "LFixture;")
    }

    @Test
    fun `produces a dex file with DEX magic containing the class`() {
        val out = tmp.root.toPath().resolve("dex")
        val dex = DexPackager().dexClass(compileFixture(), out)
        assertTrue(Files.exists(dex))
        assertEquals("Fixture.dex", dex.fileName.toString())
        val bytes = Files.readAllBytes(dex)
        assertEquals("dex\n", String(bytes, 0, 4))              // DEX magic
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("LFixture;"))
    }
}
```

- [ ] **Step 2: Run test, verify fail**

Run: `./gradlew :cli:test --tests "dev.hotreload.cli.DexPackagerTest"`
Expected: FAIL — `DexPackager` unresolved

- [ ] **Step 3: Implement**

```kotlin
package dev.hotreload.cli

import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class DexPackager(private val minApi: Int = 26) {
    fun dexClass(changed: ChangedClass, outDir: Path): Path {
        val work = Files.createTempDirectory("hotreload-d8")
        try {
            D8.run(
                D8Command.builder()
                    .addProgramFiles(changed.classFile)
                    .setMinApiLevel(minApi)
                    .setOutput(work, OutputMode.DexIndexed)
                    .build()
            )
            Files.createDirectories(outDir)
            val simpleName = changed.binaryName.substringAfterLast('.')
            val target = outDir.resolve("$simpleName.dex")
            Files.move(work.resolve("classes.dex"), target, StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            work.toFile().deleteRecursively()
        }
    }
}
```
Known ceiling: D8 desugaring of a NEW lambda in an edited body emits an extra synthetic class in the dex; ART `RedefineClasses` will reject the unknown class and the CLI routes to the rebuild path (spec risk item). No handling here.

- [ ] **Step 4: Run test, verify pass**

Run: `./gradlew :cli:test --tests "dev.hotreload.cli.DexPackagerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add cli/src
git commit -m "feat: single-class dex packaging via D8"
```

---

### Task 5: Module resolution + Gradle compile

**Files:**
- Create: `cli/src/main/kotlin/dev/hotreload/cli/ModuleResolver.kt`
- Create: `cli/src/main/kotlin/dev/hotreload/cli/GradleCompiler.kt`
- Test: `cli/src/test/kotlin/dev/hotreload/cli/ModuleResolverTest.kt`
- Test: `cli/src/test/kotlin/dev/hotreload/cli/GradleCompilerIntegrationTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```kotlin
  class ModuleResolver(private val projectDir: java.nio.file.Path) {
      fun moduleOf(sourceFile: java.nio.file.Path): String?   // ":feature", ":app"; null if outside any module
      fun allModules(): List<String>                            // discovered by build.gradle(.kts) walk
      fun classDirsOf(module: String): List<java.nio.file.Path> // <module>/build/tmp/kotlin-classes/debug
  }

  class GradleCompiler(private val projectDir: java.nio.file.Path) {
      fun compile(module: String): CompileResult  // runs "<module>:compileDebugKotlin"
  }
  data class CompileResult(val success: Boolean, val output: String)
  ```

- [ ] **Step 1: Write failing ModuleResolver tests**

```kotlin
package dev.hotreload.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModuleResolverTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun project(): java.nio.file.Path {
        val root = tmp.root.toPath()
        Files.createFile(root.resolve("settings.gradle.kts"))
        for (m in listOf("app", "feature")) {
            Files.createDirectories(root.resolve("$m/src/main/kotlin"))
            Files.createFile(root.resolve("$m/build.gradle.kts"))
        }
        return root
    }

    @Test
    fun `maps source file to its module gradle path`() {
        val root = project()
        val src = root.resolve("feature/src/main/kotlin/Foo.kt")
        assertEquals(":feature", ModuleResolver(root).moduleOf(src))
    }

    @Test
    fun `file outside any module resolves to null`() {
        val root = project()
        assertNull(ModuleResolver(root).moduleOf(root.resolve("README.md")))
    }

    @Test
    fun `discovers all modules and their class dirs`() {
        val root = project()
        val resolver = ModuleResolver(root)
        assertEquals(listOf(":app", ":feature"), resolver.allModules().sorted())
        assertEquals(
            listOf(root.resolve("feature/build/tmp/kotlin-classes/debug")),
            resolver.classDirsOf(":feature"),
        )
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :cli:test --tests "dev.hotreload.cli.ModuleResolverTest"`
Expected: FAIL

- [ ] **Step 3: Implement ModuleResolver**

```kotlin
package dev.hotreload.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo

class ModuleResolver(private val projectDir: Path) {
    // ponytail: module = any dir with build.gradle(.kts) one or more levels below root; no settings.gradle parsing
    fun allModules(): List<String> =
        Files.walk(projectDir, 3).use { stream ->
            stream.filter { it.fileName.toString() in BUILD_FILES && it.parent != projectDir }
                .map { ":" + it.parent.relativeTo(projectDir).toString().replace(java.io.File.separatorChar, ':') }
                .toList()
        }.distinct()

    fun moduleOf(sourceFile: Path): String? {
        var dir = sourceFile.parent
        while (dir != null && dir != projectDir) {
            if (BUILD_FILES.any { Files.exists(dir.resolve(it)) }) {
                return ":" + dir.relativeTo(projectDir).toString().replace(java.io.File.separatorChar, ':')
            }
            dir = dir.parent
        }
        return null
    }

    fun classDirsOf(module: String): List<Path> {
        val moduleDir = projectDir.resolve(module.removePrefix(":").replace(':', java.io.File.separatorChar))
        return listOf(moduleDir.resolve("build/tmp/kotlin-classes/debug"))
    }

    private companion object {
        val BUILD_FILES = setOf("build.gradle.kts", "build.gradle")
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :cli:test --tests "dev.hotreload.cli.ModuleResolverTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Implement GradleCompiler with integration test against sample**

`GradleCompiler.kt`:
```kotlin
package dev.hotreload.cli

import org.gradle.tooling.GradleConnector
import java.io.ByteArrayOutputStream
import java.nio.file.Path

data class CompileResult(val success: Boolean, val output: String)

class GradleCompiler(private val projectDir: Path) {
    fun compile(module: String): CompileResult {
        val out = ByteArrayOutputStream()
        return GradleConnector.newConnector()
            .forProjectDirectory(projectDir.toFile())
            .connect()
            .use { connection ->
                try {
                    connection.newBuild()
                        .forTasks("$module:compileDebugKotlin")
                        .setStandardOutput(out)
                        .setStandardError(out)
                        .run()
                    CompileResult(true, out.toString())
                } catch (e: Exception) {
                    CompileResult(false, out.toString() + "\n" + (e.message ?: e.toString()))
                }
            }
    }
}
```

`GradleCompilerIntegrationTest.kt` (tagged slow — needs the sample project; skipped when sample isn't built):
```kotlin
package dev.hotreload.cli

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertTrue

class GradleCompilerIntegrationTest {
    private val sample = Paths.get(System.getProperty("hotreload.sampleDir", "../sample")).toAbsolutePath().normalize()

    @Test
    fun `compiles feature module and produces kotlin-classes output`() {
        assumeTrue(Files.exists(sample.resolve("settings.gradle.kts")))
        val result = GradleCompiler(sample).compile(":feature")
        assertTrue(result.success, result.output)
        assertTrue(Files.exists(sample.resolve("feature/build/tmp/kotlin-classes/debug")))
    }

    @Test
    fun `broken source yields failure with compiler output`() {
        assumeTrue(Files.exists(sample.resolve("settings.gradle.kts")))
        val src = sample.resolve("feature/src/main/kotlin/dev/hotreload/sample/feature/Greeting.kt")
        val original = Files.readAllBytes(src)
        try {
            Files.write(src, (String(original) + "\nval broken: =").toByteArray())
            val result = GradleCompiler(sample).compile(":feature")
            assertTrue(!result.success)
            assertTrue(result.output.contains("Greeting.kt"), result.output)
        } finally {
            Files.write(src, original)
        }
    }
}
```

- [ ] **Step 6: Run integration test**

Run: `./gradlew :cli:test --tests "dev.hotreload.cli.GradleCompilerIntegrationTest"`
Expected: PASS (both tests; needs ANDROID_HOME set — document in README later)

- [ ] **Step 7: Commit**

```bash
git add cli/src
git commit -m "feat: module resolution and gradle tooling API compile"
```

---

### Task 6: ADB wrapper

**Files:**
- Create: `cli/src/main/kotlin/dev/hotreload/cli/ProcessRunner.kt`
- Create: `cli/src/main/kotlin/dev/hotreload/cli/Adb.kt`
- Test: `cli/src/test/kotlin/dev/hotreload/cli/AdbTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  ```kotlin
  interface ProcessRunner { fun run(args: List<String>): ProcessResult }
  data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)
  class RealProcessRunner : ProcessRunner

  class Adb(private val adbPath: String, private val serial: String?, private val runner: ProcessRunner = RealProcessRunner()) {
      fun push(local: java.nio.file.Path, remotePath: String): ProcessResult
      fun runAsCopy(pkg: String, fromDeviceTmp: String, toRelPath: String): ProcessResult
      // cp /data/local/tmp/... -> code_cache/<toRelPath> inside app sandbox; mkdir -p first
      fun attachAgent(pkg: String, agentPathInAppSandbox: String): ProcessResult
      fun forward(localPort: Int, abstractSocket: String): ProcessResult
      fun isAppRunning(pkg: String): Boolean          // via `shell pidof <pkg>`
      fun appDataDir(pkg: String): String             // "/data/data/<pkg>" (run-as pwd)
  }
  ```

- [ ] **Step 1: Write failing tests (fake runner, assert exact argv)**

```kotlin
package dev.hotreload.cli

import org.junit.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdbTest {
    private class FakeRunner(private val result: ProcessResult = ProcessResult(0, "", "")) : ProcessRunner {
        val calls = mutableListOf<List<String>>()
        override fun run(args: List<String>): ProcessResult { calls += args; return result }
    }

    @Test
    fun `push builds adb push argv with serial`() {
        val fake = FakeRunner()
        Adb("/sdk/adb", "emulator-5554", fake).push(Paths.get("/tmp/a.dex"), "/data/local/tmp/hotreload/a.dex")
        assertEquals(
            listOf("/sdk/adb", "-s", "emulator-5554", "push", "/tmp/a.dex", "/data/local/tmp/hotreload/a.dex"),
            fake.calls.single(),
        )
    }

    @Test
    fun `serial omitted when null`() {
        val fake = FakeRunner()
        Adb("adb", null, fake).forward(46837, "hotreload-agent")
        assertEquals(listOf("adb", "forward", "tcp:46837", "localabstract:hotreload-agent"), fake.calls.single())
    }

    @Test
    fun `runAsCopy mkdirs then copies inside app sandbox`() {
        val fake = FakeRunner()
        Adb("adb", null, fake).runAsCopy("dev.hotreload.sample", "/data/local/tmp/hotreload/agent.so", "hotreload/agent.so")
        assertEquals(
            listOf(
                "adb", "shell", "run-as", "dev.hotreload.sample",
                "sh", "-c", "mkdir -p code_cache/hotreload && cp /data/local/tmp/hotreload/agent.so code_cache/hotreload/agent.so",
            ),
            fake.calls.single(),
        )
    }

    @Test
    fun `attachAgent uses am attach-agent`() {
        val fake = FakeRunner()
        Adb("adb", null, fake).attachAgent("dev.hotreload.sample", "/data/data/dev.hotreload.sample/code_cache/hotreload/agent.so")
        assertEquals(
            listOf("adb", "shell", "am", "attach-agent", "dev.hotreload.sample",
                "/data/data/dev.hotreload.sample/code_cache/hotreload/agent.so"),
            fake.calls.single(),
        )
    }

    @Test
    fun `isAppRunning true when pidof prints a pid`() {
        assertTrue(Adb("adb", null, FakeRunner(ProcessResult(0, "12345\n", ""))).isAppRunning("p"))
        assertFalse(Adb("adb", null, FakeRunner(ProcessResult(1, "", ""))).isAppRunning("p"))
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :cli:test --tests "dev.hotreload.cli.AdbTest"`
Expected: FAIL

- [ ] **Step 3: Implement**

`ProcessRunner.kt`:
```kotlin
package dev.hotreload.cli

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

interface ProcessRunner {
    fun run(args: List<String>): ProcessResult
}

class RealProcessRunner : ProcessRunner {
    override fun run(args: List<String>): ProcessResult {
        val proc = ProcessBuilder(args).start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        return ProcessResult(code, stdout, stderr)
    }
}
```

`Adb.kt`:
```kotlin
package dev.hotreload.cli

import java.nio.file.Path

class Adb(
    private val adbPath: String,
    private val serial: String?,
    private val runner: ProcessRunner = RealProcessRunner(),
) {
    private fun adb(vararg args: String): ProcessResult {
        val base = buildList {
            add(adbPath)
            serial?.let { add("-s"); add(it) }
            addAll(args)
        }
        return runner.run(base)
    }

    fun push(local: Path, remotePath: String): ProcessResult =
        adb("push", local.toString(), remotePath)

    fun runAsCopy(pkg: String, fromDeviceTmp: String, toRelPath: String): ProcessResult {
        val destDir = toRelPath.substringBeforeLast('/', "")
            .let { if (it.isEmpty()) "code_cache" else "code_cache/$it" }
        return adb(
            "shell", "run-as", pkg, "sh", "-c",
            "mkdir -p $destDir && cp $fromDeviceTmp code_cache/$toRelPath",
        )
    }

    fun attachAgent(pkg: String, agentPathInAppSandbox: String): ProcessResult =
        adb("shell", "am", "attach-agent", pkg, agentPathInAppSandbox)

    fun forward(localPort: Int, abstractSocket: String): ProcessResult =
        adb("forward", "tcp:$localPort", "localabstract:$abstractSocket")

    fun isAppRunning(pkg: String): Boolean {
        val result = adb("shell", "pidof", pkg)
        return result.exitCode == 0 && result.stdout.trim().isNotEmpty()
    }

    fun appDataDir(pkg: String): String = "/data/data/$pkg"
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :cli:test --tests "dev.hotreload.cli.AdbTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add cli/src
git commit -m "feat: adb wrapper with testable process runner"
```

---

### Task 7: Runtime library

**Files:**
- Create: `runtime/src/main/kotlin/dev/hotreload/runtime/ComposeInvalidator.kt`
- Create: `runtime/src/main/kotlin/dev/hotreload/runtime/ActivityTracker.kt`
- Create: `runtime/src/main/kotlin/dev/hotreload/runtime/HotReloadInitProvider.kt`

**Interfaces:**
- Consumes: manifest `<provider>` entry from Task 1.
- Produces (called by the C++ agent via JNI — signatures are load-bearing):
  - `dev.hotreload.runtime.ComposeInvalidator.reload()` — `static void`, JNI descriptor `()V`. Safe to call from any thread; hops to main thread internally.
  - Behavior: try reflective Compose `HotReloader` save/dispose+load (invalidate-all); on any failure `Activity.recreate()` on tracked foreground activity; if no activity, log and no-op.

- [ ] **Step 1: Implement ActivityTracker**

```kotlin
package dev.hotreload.runtime

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

internal object ActivityTracker : Application.ActivityLifecycleCallbacks {
    @Volatile private var current: WeakReference<Activity>? = null

    val foreground: Activity? get() = current?.get()

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (current?.get() === activity) current = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
```

- [ ] **Step 2: Implement HotReloadInitProvider**

```kotlin
package dev.hotreload.runtime

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

class HotReloadInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)
            ?.registerActivityLifecycleCallbacks(ActivityTracker)
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
```

- [ ] **Step 3: Implement ComposeInvalidator**

```kotlin
package dev.hotreload.runtime

import android.os.Handler
import android.os.Looper
import android.util.Log

object ComposeInvalidator {
    private const val TAG = "HotReload"
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called by the JVMTI agent via JNI after RedefineClasses succeeds. */
    @JvmStatic
    fun reload() {
        mainHandler.post {
            if (!invalidateViaHotReloader()) {
                val activity = ActivityTracker.foreground
                if (activity != null) {
                    Log.w(TAG, "HotReloader unavailable; recreating ${activity.javaClass.simpleName}")
                    activity.recreate()
                } else {
                    Log.e(TAG, "Reload signalled but no foreground activity to refresh")
                }
            }
        }
    }

    // Compose runtime internal API, same hook JetBrains desktop hot reload uses.
    // Shape (Compose 1.7.x): class androidx.compose.runtime.HotReloader with Companion
    // methods saveStateAndDispose(Any): Any and loadStateAndCompose(Any): Unit.
    private fun invalidateViaHotReloader(): Boolean = try {
        val cls = Class.forName("androidx.compose.runtime.HotReloader")
        val companion = cls.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val save = companion.javaClass.declaredMethods.single { it.name == "saveStateAndDispose" }
            .apply { isAccessible = true }
        val load = companion.javaClass.declaredMethods.single { it.name == "loadStateAndCompose" }
            .apply { isAccessible = true }
        val token = save.invoke(companion, Any())
        load.invoke(companion, token)
        Log.i(TAG, "Recomposed via HotReloader")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "HotReloader reflection failed: ${t.javaClass.simpleName}: ${t.message}")
        false
    }
}
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :runtime:assembleDebug`
Expected: BUILD SUCCESSFUL. (Behavior is device-verified in Task 11's E2E; the recreate fallback path additionally self-verifies any time HotReloader reflection breaks.)

- [ ] **Step 5: Commit**

```bash
git add runtime/src
git commit -m "feat: runtime lib with reflective Compose invalidation and recreate fallback"
```

---

### Task 8: Gradle plugin

**Files:**
- Create: `gradle-plugin/src/main/kotlin/dev/hotreload/gradle/HotReloadPlugin.kt`
- Test: `gradle-plugin/src/test/kotlin/dev/hotreload/gradle/HotReloadPluginTest.kt`
- Modify: `sample/app/build.gradle.kts` — uncomment `id("dev.hotreload")`

**Interfaces:**
- Consumes: `dev.hotreload:runtime:0.1.0-SNAPSHOT` coordinates (Task 1).
- Produces: plugin id `dev.hotreload` that adds `debugImplementation dev.hotreload:runtime` to Android application projects. No other behavior.

- [ ] **Step 1: Write failing TestKit test**

```kotlin
package dev.hotreload.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertTrue

class HotReloadPluginTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `adds runtime dependency to debugImplementation on android app projects`() {
        // Minimal build that applies our plugin alongside a fake android application plugin marker.
        // Full AGP in TestKit is slow; instead verify against plain 'java' and the withId hook by
        // asserting the plugin no-ops without the android plugin, plus unit-check the wiring below.
        tmp.newFile("settings.gradle.kts").writeText("rootProject.name = \"t\"")
        tmp.newFile("build.gradle.kts").writeText(
            """
            plugins { id("dev.hotreload") }
            tasks.register("ok")
            """.trimIndent()
        )
        val result = GradleRunner.create()
            .withProjectDir(tmp.root)
            .withPluginClasspath()
            .withArguments("ok")
            .build()
        assertTrue(result.output.contains("BUILD SUCCESSFUL"))  // plugin applies cleanly without AGP
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :gradle-plugin:test`
Expected: FAIL — plugin id not found

- [ ] **Step 3: Implement**

```kotlin
package dev.hotreload.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class HotReloadPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            project.dependencies.add("debugImplementation", "dev.hotreload:runtime:0.1.0-SNAPSHOT")
        }
    }
}
```

- [ ] **Step 4: Run TestKit test, verify pass**

Run: `./gradlew :gradle-plugin:test`
Expected: PASS

- [ ] **Step 5: Enable in sample and verify the real integration**

In `sample/app/build.gradle.kts` replace `// enable in Task 8: id("dev.hotreload")` with `id("dev.hotreload")`.

Run: `cd sample && ../gradlew :app:assembleDebug -x lint && ../gradlew :app:dependencies --configuration debugRuntimeClasspath | grep hotreload`
Expected: BUILD SUCCESSFUL and `dev.hotreload:runtime:0.1.0-SNAPSHOT` in output — this is the real AGP integration check that TestKit skipped.

- [ ] **Step 6: Commit**

```bash
git add gradle-plugin/src sample/app/build.gradle.kts
git commit -m "feat: gradle plugin injecting runtime lib into debug builds"
```

---

### Task 9: JVMTI agent (C++)

**Files:**
- Create: `agent/src/main/cpp/CMakeLists.txt`
- Create: `agent/src/main/cpp/include/jvmti.h` (vendored)
- Create: `agent/src/main/cpp/agent.cpp`
- Modify: `agent/build.gradle.kts` — add externalNativeBuild
- Create: `agent/LICENSE-jvmti-header.md`

**Interfaces:**
- Consumes: wire protocol from Task 2 (must match byte-for-byte); `ComposeInvalidator.reload()` JNI target from Task 7; LOAD_DEX payload format `"<descriptor>\n<dex path>"`.
- Produces: `libhotreload_agent.so` for `arm64-v8a` and `x86_64` at `agent/build/intermediates/merged_native_libs/debug/out/lib/<abi>/libhotreload_agent.so`, exporting `Agent_OnAttach`. Listens on abstract socket `hotreload-agent`.

- [ ] **Step 1: Vendor jvmti.h**

Download the JVMTI header from AOSP ART (mirrors OpenJDK's, works against ART's openjdkjvmti):
```bash
mkdir -p agent/src/main/cpp/include
curl -L -o agent/src/main/cpp/include/jvmti.h \
  "https://android.googlesource.com/platform/art/+/refs/tags/android-14.0.0_r1/openjdkjvmti/include/jvmti.h?format=TEXT" \
  | base64 -d > agent/src/main/cpp/include/jvmti.h
```
(If the base64 pipe form is awkward: fetch, then `base64 -d` the file in place. Verify the file starts with `/*` and contains `jvmtiEnv`.)

`agent/LICENSE-jvmti-header.md`: note that `include/jvmti.h` is from OpenJDK via AOSP ART, licensed GPLv2 with Classpath Exception, and is used only at build time for the agent binary.

- [ ] **Step 2: CMake + gradle native config**

`agent/src/main/cpp/CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.22)
project(hotreload_agent CXX)
add_library(hotreload_agent SHARED agent.cpp)
target_include_directories(hotreload_agent PRIVATE include)
target_compile_features(hotreload_agent PRIVATE cxx_std_17)
find_library(log-lib log)
target_link_libraries(hotreload_agent ${log-lib})
```

`agent/build.gradle.kts` becomes:
```kotlin
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "dev.hotreload.agent"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
```

- [ ] **Step 3: Implement agent.cpp**

```cpp
#include <jni.h>
#include "jvmti.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <pthread.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <fstream>
#include <string>
#include <vector>

#define LOG_TAG "HotReloadAgent"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr char kSocketName[] = "hotreload-agent";
constexpr uint8_t kCmdPing = 0x01;
constexpr uint8_t kCmdLoadDex = 0x02;
constexpr uint8_t kStatusOk = 0x00;
constexpr uint8_t kStatusFail = 0x02;

JavaVM* g_vm = nullptr;
jvmtiEnv* g_jvmti = nullptr;
bool g_started = false;

bool ReadFile(const std::string& path, std::vector<unsigned char>* out) {
  std::ifstream f(path, std::ios::binary | std::ios::ate);
  if (!f) return false;
  auto size = f.tellg();
  out->resize(static_cast<size_t>(size));
  f.seekg(0);
  f.read(reinterpret_cast<char*>(out->data()), size);
  return f.good();
}

// FindClass from an attached native thread only sees the system classloader.
// App classes must be located among already-loaded classes instead.
jclass FindLoadedClass(JNIEnv* env, const char* descriptor) {
  jint count = 0;
  jclass* classes = nullptr;
  if (g_jvmti->GetLoadedClasses(&count, &classes) != JVMTI_ERROR_NONE) return nullptr;
  jclass found = nullptr;
  for (jint i = 0; i < count; i++) {
    char* sig = nullptr;
    if (g_jvmti->GetClassSignature(classes[i], &sig, nullptr) == JVMTI_ERROR_NONE) {
      if (found == nullptr && strcmp(sig, descriptor) == 0) {
        found = static_cast<jclass>(env->NewGlobalRef(classes[i]));
      }
      g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig));
    }
    env->DeleteLocalRef(classes[i]);
  }
  g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
  return found;
}

void NotifyRuntime(JNIEnv* env) {
  jclass cls = FindLoadedClass(env, "Ldev/hotreload/runtime/ComposeInvalidator;");
  if (cls == nullptr) {
    LOGE("ComposeInvalidator not loaded; skipping recompose signal");
    return;
  }
  jmethodID reload = env->GetStaticMethodID(cls, "reload", "()V");
  if (reload != nullptr) {
    env->CallStaticVoidMethod(cls, reload);
  }
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  env->DeleteGlobalRef(cls);
}

// payload: "<descriptor>\n<dex path>". Returns reply detail; sets *ok.
std::string HandleLoadDex(JNIEnv* env, const std::string& payload, bool* ok) {
  *ok = false;
  size_t nl = payload.find('\n');
  if (nl == std::string::npos) return "malformed LOAD_DEX payload";
  std::string descriptor = payload.substr(0, nl);
  std::string dex_path = payload.substr(nl + 1);

  std::vector<unsigned char> dex;
  if (!ReadFile(dex_path, &dex)) return "cannot read dex: " + dex_path;

  jclass target = FindLoadedClass(env, descriptor.c_str());
  if (target == nullptr) return "class not loaded: " + descriptor + " (new classes are unsupported in v1 — rebuild)";

  jvmtiClassDefinition def;
  def.klass = target;
  def.class_byte_count = static_cast<jint>(dex.size());
  def.class_bytes = dex.data();
  jvmtiError err = g_jvmti->RedefineClasses(1, &def);
  env->DeleteGlobalRef(target);

  if (err != JVMTI_ERROR_NONE) {
    char* name = nullptr;
    g_jvmti->GetErrorName(err, &name);
    std::string msg = "RedefineClasses failed: " + std::string(name ? name : "?") +
                      " (structural changes are unsupported in v1 — rebuild)";
    if (name) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(name));
    return msg;
  }
  *ok = true;
  return descriptor + ": redefined";
}

bool ReadFully(int fd, void* buf, size_t len) {
  auto* p = static_cast<uint8_t*>(buf);
  while (len > 0) {
    ssize_t n = read(fd, p, len);
    if (n <= 0) return false;
    p += n; len -= static_cast<size_t>(n);
  }
  return true;
}

bool WriteFully(int fd, const void* buf, size_t len) {
  auto* p = static_cast<const uint8_t*>(buf);
  while (len > 0) {
    ssize_t n = write(fd, p, len);
    if (n <= 0) return false;
    p += n; len -= static_cast<size_t>(n);
  }
  return true;
}

void SendReply(int fd, uint8_t status, const std::string& detail) {
  uint32_t len = htonl(static_cast<uint32_t>(1 + detail.size()));
  WriteFully(fd, &len, 4);
  WriteFully(fd, &status, 1);
  WriteFully(fd, detail.data(), detail.size());
}

void ServeClient(int fd, JNIEnv* env) {
  for (;;) {
    uint32_t len_be = 0;
    if (!ReadFully(fd, &len_be, 4)) return;
    uint32_t len = ntohl(len_be);
    if (len < 1 || len > 64 * 1024 * 1024) return;
    std::vector<char> buf(len);
    if (!ReadFully(fd, buf.data(), len)) return;
    uint8_t cmd = static_cast<uint8_t>(buf[0]);
    std::string payload(buf.begin() + 1, buf.end());

    if (cmd == kCmdPing) {
      SendReply(fd, kStatusOk, "pong");
    } else if (cmd == kCmdLoadDex) {
      bool ok = false;
      std::string detail = HandleLoadDex(env, payload, &ok);
      if (ok) NotifyRuntime(env);
      SendReply(fd, ok ? kStatusOk : kStatusFail, detail);
      LOGI("LOAD_DEX: %s", detail.c_str());
    } else {
      SendReply(fd, kStatusFail, "unknown command");
    }
  }
}

void* ServerThread(void*) {
  JNIEnv* env = nullptr;
  JavaVMAttachArgs args = {JNI_VERSION_1_6, "HotReloadAgent", nullptr};
  if (g_vm->AttachCurrentThread(&env, &args) != JNI_OK) {
    LOGE("cannot attach server thread");
    return nullptr;
  }

  int server = socket(AF_UNIX, SOCK_STREAM, 0);
  sockaddr_un addr = {};
  addr.sun_family = AF_UNIX;
  addr.sun_path[0] = '\0';  // abstract namespace
  strcpy(addr.sun_path + 1, kSocketName);
  socklen_t addr_len = static_cast<socklen_t>(offsetof(sockaddr_un, sun_path) + 1 + strlen(kSocketName));
  if (bind(server, reinterpret_cast<sockaddr*>(&addr), addr_len) != 0 || listen(server, 1) != 0) {
    LOGE("bind/listen failed: %s", strerror(errno));
    g_vm->DetachCurrentThread();
    return nullptr;
  }
  LOGI("listening on @%s", kSocketName);

  for (;;) {
    int client = accept(server, nullptr, nullptr);
    if (client < 0) break;
    ServeClient(client, env);
    close(client);
  }
  g_vm->DetachCurrentThread();
  return nullptr;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* /*options*/, void* /*reserved*/) {
  if (g_started) return JNI_OK;  // am attach-agent may be issued again; server already up
  g_vm = vm;
  if (vm->GetEnv(reinterpret_cast<void**>(&g_jvmti), JVMTI_VERSION_1_2) != JNI_OK) {
    LOGE("no jvmti env — is the app debuggable?");
    return JNI_ERR;
  }
  jvmtiCapabilities caps = {};
  caps.can_redefine_classes = 1;
  if (g_jvmti->AddCapabilities(&caps) != JVMTI_ERROR_NONE) {
    LOGE("can_redefine_classes unavailable");
    return JNI_ERR;
  }
  pthread_t t;
  pthread_create(&t, nullptr, ServerThread, nullptr);
  g_started = true;
  LOGI("agent attached");
  return JNI_OK;
}
```

- [ ] **Step 4: Build both ABIs**

Run: `./gradlew :agent:assembleDebug`
Expected: BUILD SUCCESSFUL; verify with
`ls agent/build/intermediates/merged_native_libs/debug/out/lib/*/libhotreload_agent.so`
showing `arm64-v8a` and `x86_64`. Then `nm -D --defined-only agent/build/intermediates/merged_native_libs/debug/out/lib/x86_64/libhotreload_agent.so | grep Agent_OnAttach`
Expected: `Agent_OnAttach` exported.

- [ ] **Step 5: Commit**

```bash
git add agent/
git commit -m "feat: JVMTI agent with socket server, RedefineClasses, runtime notify"
```

---

### Task 10: Orchestrator + CLI entrypoint

**Files:**
- Create: `cli/src/main/kotlin/dev/hotreload/cli/AgentClient.kt`
- Create: `cli/src/main/kotlin/dev/hotreload/cli/ReloadOrchestrator.kt`
- Create: `cli/src/main/kotlin/dev/hotreload/cli/Main.kt`
- Test: `cli/src/test/kotlin/dev/hotreload/cli/AgentClientTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 2–6; agent socket semantics from Task 9.
- Produces CLI:
  - `hotreload bootstrap --project <dir> --package <pkg> [--serial S] [--agent-so <path>]` — cycle 0: baseline + push/attach agent + forward + ping.
  - `hotreload cycle --project <dir> --package <pkg> --file <changed.kt> [--serial S]` — one reload cycle (used by E2E).
  - `hotreload run --project <dir> --package <pkg> [--serial S]` — bootstrap + watch loop.
  - Exit codes: 0 reload ok, 1 compile error, 2 incompatible change (rebuild needed), 3 device/agent failure.
  - Default agent-so path: `<repoRoot>/agent/build/intermediates/merged_native_libs/debug/out/lib/<abi>/libhotreload_agent.so`, ABI picked via `adb shell getprop ro.product.cpu.abi`.
  - Forwarded local port: 46837.

- [ ] **Step 1: Write failing AgentClient test (loopback server fake)**

```kotlin
package dev.hotreload.cli

import org.junit.Test
import java.net.ServerSocket
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.test.assertEquals

class AgentClientTest {
    @Test
    fun `sends request and decodes reply over tcp`() {
        val server = ServerSocket(0)
        thread {
            server.accept().use { s ->
                val input = java.io.DataInputStream(s.getInputStream())
                val len = input.readInt()
                val body = ByteArray(len); input.readFully(body)
                assertEquals(Protocol.CMD_PING, body[0])
                val detail = "pong".toByteArray()
                s.getOutputStream().write(
                    ByteBuffer.allocate(4 + 1 + detail.size)
                        .putInt(1 + detail.size).put(Protocol.STATUS_OK).put(detail).array()
                )
                s.getOutputStream().flush()
            }
        }
        val reply = AgentClient("localhost", server.localPort).use { it.ping() }
        assertEquals(Protocol.STATUS_OK, reply.status)
        assertEquals("pong", reply.detail)
        server.close()
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :cli:test --tests "dev.hotreload.cli.AgentClientTest"`
Expected: FAIL

- [ ] **Step 3: Implement AgentClient**

```kotlin
package dev.hotreload.cli

import java.io.Closeable
import java.net.Socket

class AgentClient(host: String, port: Int) : Closeable {
    private val socket = Socket(host, port)

    fun ping(): Reply = request(Protocol.CMD_PING, ByteArray(0))

    fun loadDex(descriptor: String, deviceDexPath: String): Reply =
        request(Protocol.CMD_LOAD_DEX, "$descriptor\n$deviceDexPath".toByteArray())

    private fun request(cmd: Byte, payload: ByteArray): Reply {
        socket.getOutputStream().apply {
            write(Protocol.encodeRequest(cmd, payload))
            flush()
        }
        return Protocol.decodeReply(socket.getInputStream())
    }

    override fun close() = socket.close()
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :cli:test --tests "dev.hotreload.cli.AgentClientTest"`
Expected: PASS

- [ ] **Step 5: Implement ReloadOrchestrator**

```kotlin
package dev.hotreload.cli

import java.nio.file.Path

class ReloadConfig(
    val projectDir: Path,
    val pkg: String,
    val serial: String?,
    val adbPath: String,
    val agentSoDir: Path,   // dir containing <abi>/libhotreload_agent.so
    val localPort: Int = 46837,
)

sealed class CycleOutcome {
    data class Reloaded(val classes: List<String>, val millis: Long) : CycleOutcome()
    data class CompileError(val output: String) : CycleOutcome()
    data class Incompatible(val reason: String) : CycleOutcome()
    data class DeviceError(val reason: String) : CycleOutcome()
    object NoChanges : CycleOutcome()
}

class ReloadOrchestrator(private val config: ReloadConfig) {
    private val adb = Adb(config.adbPath, config.serial)
    private val resolver = ModuleResolver(config.projectDir)
    private val differ = ClassDiffer()
    private val store = BaselineStore(config.projectDir.resolve(".hotreload/baseline.txt"))
    private val compiler = GradleCompiler(config.projectDir)
    private val dexer = DexPackager()

    private fun allClassDirs() = resolver.allModules().flatMap(resolver::classDirsOf)

    fun bootstrap(): CycleOutcome {
        if (!adb.isAppRunning(config.pkg)) {
            return CycleOutcome.DeviceError("${config.pkg} is not running — launch the app first")
        }
        val abi = adb.let { Adb(config.adbPath, config.serial) }
            .run { RealProcessRunner().run(listOfNotNull(config.adbPath, config.serial?.let { "-s" }, config.serial, "shell", "getprop", "ro.product.cpu.abi")) }
            .stdout.trim()
        val so = config.agentSoDir.resolve(abi).resolve("libhotreload_agent.so")
        if (!java.nio.file.Files.exists(so)) {
            return CycleOutcome.DeviceError("agent .so for abi '$abi' not found at $so — run ./gradlew :agent:assembleDebug")
        }
        adb.push(so, "/data/local/tmp/hotreload/agent.so")
        adb.runAsCopy(config.pkg, "/data/local/tmp/hotreload/agent.so", "hotreload/agent.so")
        adb.attachAgent(config.pkg, "${adb.appDataDir(config.pkg)}/code_cache/hotreload/agent.so")
        adb.forward(config.localPort, "hotreload-agent")

        val ping = runCatching { AgentClient("localhost", config.localPort).use { it.ping() } }
        if (ping.getOrNull()?.status != Protocol.STATUS_OK) {
            return CycleOutcome.DeviceError("agent ping failed: ${ping.exceptionOrNull()?.message ?: ping.getOrNull()?.detail}")
        }
        store.save(differ.snapshot(allClassDirs()))
        return CycleOutcome.Reloaded(emptyList(), 0)  // bootstrap ok; nothing reloaded yet
    }

    fun cycle(changedFile: Path): CycleOutcome {
        val start = System.currentTimeMillis()
        val module = resolver.moduleOf(changedFile)
            ?: return CycleOutcome.CompileError("cannot map $changedFile to a gradle module")

        val compileResult = compiler.compile(module)
        if (!compileResult.success) return CycleOutcome.CompileError(compileResult.output)

        val current = differ.snapshot(allClassDirs())
        val diff = differ.diff(store.load(), current, allClassDirs())
        if (diff.added.isNotEmpty() || diff.removed.isNotEmpty()) {
            return CycleOutcome.Incompatible(
                "structural change (added: ${diff.added}, removed: ${diff.removed}) — full rebuild needed"
            )
        }
        if (diff.changed.isEmpty()) return CycleOutcome.NoChanges

        val dexDir = config.projectDir.resolve(".hotreload/dex")
        for (changed in diff.changed) {
            val dex = dexer.dexClass(changed, dexDir)
            val simpleName = dex.fileName.toString()
            adb.push(dex, "/data/local/tmp/hotreload/$simpleName")
            adb.runAsCopy(config.pkg, "/data/local/tmp/hotreload/$simpleName", "hotreload/$simpleName")
            val devicePath = "${adb.appDataDir(config.pkg)}/code_cache/hotreload/$simpleName"
            val reply = runCatching {
                AgentClient("localhost", config.localPort).use { it.loadDex(changed.descriptor, devicePath) }
            }.getOrElse { return CycleOutcome.DeviceError("agent connection failed: ${it.message}") }
            if (reply.status != Protocol.STATUS_OK) {
                return CycleOutcome.Incompatible(reply.detail)
            }
        }
        store.save(current)
        return CycleOutcome.Reloaded(diff.changed.map { it.binaryName }, System.currentTimeMillis() - start)
    }
}
```

- [ ] **Step 6: Implement Main.kt**

```kotlin
package dev.hotreload.cli

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val cmd = args[0]
    val opts = args.drop(1).chunked(2).mapNotNull { pair ->
        if (pair.size == 2 && pair[0].startsWith("--")) pair[0].removePrefix("--") to pair[1] else null
    }.toMap()

    val projectDir = Paths.get(opts["project"] ?: fail("--project required")).toAbsolutePath().normalize()
    val pkg = opts["package"] ?: fail("--package required")
    val config = ReloadConfig(
        projectDir = projectDir,
        pkg = pkg,
        serial = opts["serial"],
        adbPath = opts["adb"] ?: defaultAdb(),
        agentSoDir = Paths.get(
            opts["agent-so-dir"]
                ?: Paths.get("").toAbsolutePath()
                    .resolve("agent/build/intermediates/merged_native_libs/debug/out/lib").toString()
        ),
    )
    val orchestrator = ReloadOrchestrator(config)

    when (cmd) {
        "bootstrap" -> exitWith(orchestrator.bootstrap())
        "cycle" -> {
            val file = Paths.get(opts["file"] ?: fail("--file required"))
            exitWith(orchestrator.cycle(file))
        }
        "run" -> {
            val boot = orchestrator.bootstrap()
            if (boot !is CycleOutcome.Reloaded) exitWith(boot)
            println("hotreload ready — watching ${config.projectDir}")
            watchLoop(config.projectDir, orchestrator)
        }
        else -> usage()
    }
}

private fun watchLoop(projectDir: Path, orchestrator: ReloadOrchestrator): Nothing {
    val watcher = FileSystems.getDefault().newWatchService()
    Files.walk(projectDir).use { stream ->
        stream.filter { Files.isDirectory(it) && it.toString().contains("src") && !it.toString().contains("build") }
            .forEach { it.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE) }
    }
    while (true) {
        val key = watcher.take()
        val dir = key.watchable() as Path
        val changedKt = key.pollEvents().mapNotNull { (it.context() as? Path)?.let(dir::resolve) }
            .filter { it.toString().endsWith(".kt") }
        key.reset()
        if (changedKt.isEmpty()) continue
        Thread.sleep(100)  // debounce editor write bursts
        report(orchestrator.cycle(changedKt.first()))
    }
}

private fun report(outcome: CycleOutcome) = when (outcome) {
    is CycleOutcome.Reloaded -> println("✓ reloaded ${outcome.classes.size} class(es) in ${outcome.millis}ms: ${outcome.classes.joinToString()}")
    is CycleOutcome.NoChanges -> println("· no bytecode changes")
    is CycleOutcome.CompileError -> println("✗ compile error:\n${outcome.output}")
    is CycleOutcome.Incompatible -> println("✗ incompatible change: ${outcome.reason}\n  → run a full rebuild + reinstall, then 'hotreload bootstrap' again")
    is CycleOutcome.DeviceError -> println("✗ device/agent: ${outcome.reason}")
}

private fun exitWith(outcome: CycleOutcome): Nothing {
    report(outcome)
    exitProcess(
        when (outcome) {
            is CycleOutcome.Reloaded, CycleOutcome.NoChanges -> 0
            is CycleOutcome.CompileError -> 1
            is CycleOutcome.Incompatible -> 2
            is CycleOutcome.DeviceError -> 3
        }
    )
}

private fun defaultAdb(): String {
    val home = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: fail("set ANDROID_HOME or pass --adb")
    return "$home/platform-tools/adb"
}

private fun usage(): Nothing {
    println("usage: hotreload <bootstrap|cycle|run> --project <dir> --package <pkg> [--serial S] [--file f.kt] [--adb path] [--agent-so-dir dir]")
    exitProcess(64)
}

private fun fail(msg: String): Nothing {
    System.err.println("error: $msg")
    exitProcess(64)
}
```

- [ ] **Step 7: Compile + unit tests green**

Run: `./gradlew :cli:build`
Expected: BUILD SUCCESSFUL, all unit tests pass

- [ ] **Step 8: Commit**

```bash
git add cli/src
git commit -m "feat: reload orchestrator with bootstrap/cycle/run commands"
```

---

### Task 11: E2E on emulator + CI

**Files:**
- Create: `e2e/run-e2e.sh`
- Create: `.github/workflows/ci.yml`
- Create: `README.md`

**Interfaces:**
- Consumes: CLI exit codes from Task 10 (0 ok / 1 compile / 2 incompatible / 3 device); sample app package `dev.hotreload.sample`; counter button text `Count: N`; greeting text `Hello, World!`.
- Produces: one command (`e2e/run-e2e.sh`) that proves the golden path and the incompatible-change path on a booted emulator.

- [ ] **Step 1: Write e2e/run-e2e.sh**

```bash
#!/usr/bin/env bash
# E2E: golden reload path + incompatible-change path. Requires a booted emulator/device and ANDROID_HOME.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
ADB="${ANDROID_HOME}/platform-tools/adb"
PKG="dev.hotreload.sample"
GREETING="sample/feature/src/main/kotlin/dev/hotreload/sample/feature/Greeting.kt"
CLI="./gradlew -q :cli:run --args"

cleanup() { git checkout -- "$GREETING" 2>/dev/null || true; }
trap cleanup EXIT

fail() { echo "E2E FAIL: $1"; exit 1; }

ui_contains() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null
  "$ADB" shell cat /sdcard/ui.xml | grep -qF "$1"
}

echo "== build tool + agent + sample =="
./gradlew :agent:assembleDebug :cli:installDist
(cd sample && ../gradlew :app:assembleDebug -x lint)

echo "== install + launch =="
"$ADB" install -r sample/app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n "$PKG/.MainActivity"
sleep 3
ui_contains "Hello, World!" || fail "baseline UI not visible"

echo "== click counter twice (state probe) =="
BOUNDS=$("$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null && "$ADB" shell cat /sdcard/ui.xml \
  | grep -o 'text="Count: 0"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
  | grep -o '\[[0-9]*,[0-9]*\]' | head -1 | tr -d '[]')
X=$(echo "$BOUNDS" | cut -d, -f1); Y=$(echo "$BOUNDS" | cut -d, -f2)
"$ADB" shell input tap "$((X+20))" "$((Y+20))"
"$ADB" shell input tap "$((X+20))" "$((Y+20))"
sleep 1
ui_contains "Count: 2" || fail "counter did not reach 2"

echo "== bootstrap =="
HR="$ROOT/cli/build/install/cli/bin/cli"
"$HR" bootstrap --project "$ROOT/sample" --package "$PKG" --agent-so-dir "$ROOT/agent/build/intermediates/merged_native_libs/debug/out/lib" \
  || fail "bootstrap exited $?"

echo "== golden path: edit composable body, cycle, assert new text + preserved state =="
sed -i.bak 's/Hello, \$name!/Reloaded, \$name!/' "$GREETING" && rm -f "$GREETING.bak"
"$HR" cycle --project "$ROOT/sample" --package "$PKG" --file "$ROOT/$GREETING" \
  --agent-so-dir "$ROOT/agent/build/intermediates/merged_native_libs/debug/out/lib" \
  || fail "cycle exited $?"
sleep 2
ui_contains "Reloaded, World!" || fail "reloaded text not visible"
ui_contains "Count: 2" || fail "counter state lost after reload"

echo "== incompatible path: add a function, expect exit 2 and clean error =="
cat >> "$GREETING" <<'EOF'

fun extraTopLevel(): Int = 7
EOF
set +e
"$HR" cycle --project "$ROOT/sample" --package "$PKG" --file "$ROOT/$GREETING" \
  --agent-so-dir "$ROOT/agent/build/intermediates/merged_native_libs/debug/out/lib"
CODE=$?
set -e
[ "$CODE" -eq 2 ] || fail "expected exit 2 (incompatible), got $CODE"
ui_contains "Reloaded, World!" || fail "app corrupted by rejected change"

echo "E2E PASS"
```
Note: adding a top-level function changes the existing `GreetingKt` class (new method) → JVMTI rejects → exit 2. That's the intended incompatible probe.

Run: `chmod +x e2e/run-e2e.sh`

- [ ] **Step 2: Run E2E locally against a booted emulator**

Run: `e2e/run-e2e.sh`
Expected: `E2E PASS`. This is the first full-pipeline verification — expect iteration here (agent logs via `adb logcat -s HotReloadAgent HotReload` are the debugging tool). Fix issues in the owning module and re-run until green.

- [ ] **Step 3: CI workflow**

`.github/workflows/ci.yml`:
```yaml
name: ci
on:
  push: { branches: [main] }
  pull_request: {}
jobs:
  unit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew build -x lint
  e2e:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v4
      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' \
            | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm
      - uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          arch: x86_64
          script: e2e/run-e2e.sh
```

- [ ] **Step 4: README**

Write `README.md` with: what it is (one paragraph, link spec), status (v1: composable body reload), quickstart (apply plugin, build+install+launch, `hotreload run`), supported/unsupported change table, how it works (five-line pipeline), requirements (ANDROID_HOME, debuggable build, API 26+), license section (note the vendored JVMTI header's GPLv2+CE), contributing pointer to spec+plan docs.

- [ ] **Step 5: Commit + push, verify CI**

```bash
git add e2e .github README.md
git commit -m "feat: emulator E2E for golden and incompatible paths, CI workflow"
```
Push and confirm both CI jobs green.

---

## Addendum (2026-08-02): remember-state preservation

Task 11's on-device run proved `HotReloader.saveStateAndDispose`/`loadStateAndCompose` discards all `remember`/`rememberSaveable` state (whole-composition rebuild; only Activity/ViewModel state survives). User ruling: pursue true `remember` preservation via Live-Edit-style group invalidation. Spec's runtime section now defines a three-tier chain. Tasks 12–13 implement it.

### Task 12: Group-key invalidation (preserves remember state)

**Files:**
- Modify: `gradle-plugin/src/main/kotlin/dev/hotreload/gradle/HotReloadPlugin.kt` — enable Compose compiler key-meta output on debug compilations (app AND library modules)
- Modify: `gradle-plugin/build.gradle.kts` — `compileOnly` kotlin-gradle-plugin for task-type access
- Modify: `runtime/src/main/kotlin/dev/hotreload/runtime/ComposeInvalidator.kt` — new `reload(binaryNames: Array<String>)` JNI entry, key lookup, `invalidateGroupsWithKey` tier
- Modify: `agent/src/main/cpp/agent.cpp` — call `reload([Ljava/lang/String;)V` with redefined binary names
- Modify: `sample/feature/build.gradle.kts` + `sample/app/build.gradle.kts` — apply `dev.hotreload` plugin to feature module too (flag must reach every composable-bearing module)

**Interfaces:**
- Consumes: agent's `NotifyRuntime` call site (Task 9), `ComposeInvalidator` (Task 7), plugin (Task 8).
- Produces: JNI target becomes `dev/hotreload/runtime/ComposeInvalidator.reload([Ljava/lang/String;)V` taking binary class names (e.g. `"dev.hotreload.sample.feature.GreetingKt"`). Reply detail from agent unchanged. CLI unchanged.

**Empirical steps (this is R&D — probe, then implement):**

- [ ] **Step 1: Enable key-meta flag and inspect output.** In the gradle plugin, for every project with a Kotlin Android compilation, add to DEBUG compile tasks: `freeCompilerArgs += listOf("-P", "plugin:androidx.compose.compiler.plugins.kotlin:generateFunctionKeyMetaClasses=true")`. If the Kotlin 2.1 compose plugin rejects that option name, dump valid options (compile with a bogus `-P plugin:androidx.compose.compiler.plugins.kotlin:help=true` or check `ComposePluginRegistrar` option names via the compose-compiler artifact) and use the current name. Rebuild sample; inspect `sample/feature/build/tmp/kotlin-classes/debug/` for generated `*KeyMeta*` classes; `javap -p -v` one to learn: exact class naming pattern per source file, and the annotation shape (`FunctionKeyMeta(key=..., startOffset=..., endOffset=...)`). Record findings in the report.
- [ ] **Step 2: Runtime key lookup + invalidation.** `keysForClass(binaryName)`: derive the key-meta class name from the pattern learned in Step 1, `Class.forName` it via the redefined class's classloader, read its `FunctionKeyMeta` annotations, return keys. `invalidateGroupsWithKey(keys)`: reflectively resolve a `invalidateGroupsWithKey(Int)` method — probe `androidx.compose.runtime.HotReloader` (object + Companion) and `androidx.compose.runtime.Recomposer$Companion` — call per key on main thread; any hit counts as success. New chain in `reload(binaryNames)`: tier 1 keys+invalidate → tier 2 existing `invalidateViaHotReloader()` → tier 3 recreate. Log tier taken with tag `HotReload` (CLI already surfaces agent detail; include tier in logcat at minimum).
- [ ] **Step 3: Agent passes names.** In `NotifyRuntime`, build `jobjectArray` of `java/lang/String` binary names (descriptor `Lfoo/Bar;` → `foo.Bar`), call `GetStaticMethodID(cls, "reload", "([Ljava/lang/String;)V")`. Keep exception hygiene identical. Rebuild both ABIs, nm check.
- [ ] **Step 4: Verify on device.** Manual cycle against sample: edit `Greeting.kt` body, run `hotreload cycle`, logcat must show tier-1 invalidation (not saveStateAndDispose), UI updates. If `invalidateGroupsWithKey` proves unreachable in Compose 1.7.x (wrong home, obfuscated, absent), STOP and report BLOCKED with the probe evidence — do not silently ship tier 2 as primary.
- [ ] **Step 5: Commit.**

### Task 13: E2E proves remember survival

**Files:**
- Modify: `sample/app/src/main/kotlin/dev/hotreload/sample/MainActivity.kt` — counter back to `remember { mutableIntStateOf(0) }` inside the composable
- Modify: `e2e/run-e2e.sh` — golden path unchanged (`Count: 2` after reload now proves remember survival); assert logcat shows tier-1 path taken

- [ ] **Step 1: Revert probe to remember state.**
- [ ] **Step 2: Add tier assertion.** After the golden-path cycle, `adb logcat -d -s HotReload | grep -q "group-key"` (match the tier-1 log line from Task 12 Step 2) — fail the E2E if the reload fell back to a weaker tier.
- [ ] **Step 3: Run `e2e/run-e2e.sh` to PASS 3x consecutively.**
- [ ] **Step 4: Update README supported table — remember state preserved on primary path; document fallback tiers. Commit.**

## Self-Review Notes

- Spec coverage: bootstrap (Task 10), reload cycle (Tasks 3–6, 10), agent (9), runtime invalidation + fallback (7), gradle plugin (8), error taxonomy → exit codes (10), E2E golden + incompatible (11), CI (11). Multi-device `--serial` (10). Deferred items from spec stay deferred.
- Known simplifications carried intentionally: module discovery walks for build files instead of parsing settings scripts (`ponytail:` comment in ModuleResolver); watch loop reloads first changed file per batch; single client connection at a time in agent.
- The riskiest unproven assumption (ART accepts D8-produced single-class dex in `RedefineClasses`, HotReloader reflection shape) is deliberately smoke-tested at Task 11 Step 2 — budget iteration time there.
