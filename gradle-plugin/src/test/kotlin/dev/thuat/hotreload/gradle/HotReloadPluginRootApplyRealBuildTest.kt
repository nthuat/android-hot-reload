package dev.thuat.hotreload.gradle

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertTrue

/**
 * A genuinely real-build test: a multi-project fixture with an actual `com.android.application`
 * module, an actual `com.android.library` module, and a plain module with no Android plugin at
 * all, with `dev.thuat.hotreload` applied *only at the root* — proving the coordinator's
 * `subprojects { }` wiring and the debug-dependency injection against real AGP (which owns the
 * `debugImplementation` configuration our plugin adds to) rather than the faked
 * `com.android.application`/`com.android.library` plugin IDs used by
 * [HotReloadPluginCoordinatorTest]'s fast unit tests.
 *
 * Deliberately does *not* apply a real Kotlin/Compose plugin here (so no `compileOnly` Compose
 * type resolution is needed): TestKit's `withPluginClasspath()` isolates the plugin-under-test
 * into its own classloader, separate from the one used for externally-resolved plugins like
 * `org.jetbrains.kotlin.plugin.compose` — a real consumer doesn't have this split (both plugins
 * resolve into the same project's normal plugin classpath), but under TestKit our plugin's code
 * can't see that plugin's `ComposeCompilerGradlePluginExtension` type, so exercising the key-meta
 * flag needs either this fake-plugin-id unit-test trick or a real `mavenLocal`/JitPack consumer
 * (see the manual verification in the project's report) — not TestKit. The dependency-injection
 * path this test *does* cover has no such dependency on Compose types.
 */
class HotReloadPluginRootApplyRealBuildTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sdkDir = File(System.getProperty("user.home"), "Library/Android/sdk").absolutePath

    @Test
    fun `root-only apply wires the real debugImplementation configuration on app, skips library and plain`() {
        tmp.newFile("local.properties").writeText("sdk.dir=$sdkDir\n")
        tmp.newFile("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories { google(); mavenCentral(); gradlePluginPortal() }
            }
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
            }
            rootProject.name = "root-apply-fixture"
            include(":app", ":feature", ":plain")
            """.trimIndent()
        )
        tmp.newFile("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application") version "8.7.3" apply false
                id("com.android.library") version "8.7.3" apply false
                id("dev.thuat.hotreload")
            }
            """.trimIndent()
        )

        tmp.newFolder("app", "src", "main")
        tmp.newFile("app/src/main/AndroidManifest.xml").writeText(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" />"""
        )
        tmp.newFile("app/build.gradle.kts").writeText(
            """
            plugins { id("com.android.application") }
            android {
                namespace = "com.example.app"
                compileSdk = 35
                defaultConfig { applicationId = "com.example.app"; minSdk = 26; targetSdk = 35 }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
            tasks.register("printHotReloadFacts") {
                doLast {
                    val deps = configurations.getByName("debugImplementation").dependencies
                        .map { "${'$'}{it.group}:${'$'}{it.name}:${'$'}{it.version}" }
                    println("APP_DEPS=" + deps.joinToString(","))
                }
            }
            """.trimIndent()
        )

        tmp.newFolder("feature", "src", "main")
        tmp.newFile("feature/src/main/AndroidManifest.xml").writeText(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" />"""
        )
        tmp.newFile("feature/build.gradle.kts").writeText(
            """
            plugins { id("com.android.library") }
            android {
                namespace = "com.example.feature"
                compileSdk = 35
                defaultConfig { minSdk = 26 }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
            tasks.register("printHotReloadFacts") {
                doLast {
                    // Library modules get their own "debugImplementation" configuration from AGP
                    // too (it's not application-specific) — what proves our plugin didn't inject
                    // anything into it is an empty dependency set, not the configuration's absence.
                    val deps = configurations.getByName("debugImplementation").dependencies
                        .map { "${'$'}{it.group}:${'$'}{it.name}:${'$'}{it.version}" }
                    println("FEATURE_DEPS=" + deps.joinToString(","))
                }
            }
            """.trimIndent()
        )

        tmp.newFolder("plain")
        tmp.newFile("plain/build.gradle.kts").writeText(
            """
            tasks.register("printHotReloadFacts") {
                doLast {
                    println("PLAIN_HAS_DEBUGIMPL=" + configurations.names.contains("debugImplementation"))
                    println("PLAIN_HAS_HOTRELOAD_EXTENSION=" + (extensions.findByName("hotreload") != null))
                }
            }
            """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(tmp.root)
            .withPluginClasspath()
            .withArguments(":app:printHotReloadFacts", ":feature:printHotReloadFacts", ":plain:printHotReloadFacts")
            .build()

        assertTrue(result.output.contains("APP_DEPS=dev.thuat:hotreload-runtime:0.1.2"), result.output)
        assertTrue(result.output.contains("FEATURE_DEPS="), result.output)
        assertTrue(!result.output.contains("FEATURE_DEPS=dev.thuat"), result.output)
        assertTrue(result.output.contains("PLAIN_HAS_DEBUGIMPL=false"), result.output)
        assertTrue(result.output.contains("PLAIN_HAS_HOTRELOAD_EXTENSION=false"), result.output)
    }
}
