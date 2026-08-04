package dev.thuat.hotreload.gradle

import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Priority chain behind the SDK dir baked into the wrapper script -- pure, no Gradle/AGP. */
class AndroidSdkResolutionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String) = tmp.newFolder(name)

    @Test
    fun `agp sdk dir wins when it exists`() {
        val agp = dir("agp-sdk")
        val local = File(tmp.root, "local.properties").apply { writeText("sdk.dir=${dir("local-sdk")}") }
        val resolved = AndroidSdkResolution.resolve(agp, local, mapOf("ANDROID_HOME" to dir("env-sdk").path))
        assertEquals(agp, resolved)
    }

    @Test
    fun `falls back to local properties sdk dir when agp doesn't resolve one`() {
        val localSdk = dir("local-sdk")
        val local = File(tmp.root, "local.properties").apply { writeText("sdk.dir=${localSdk.path}") }
        val resolved = AndroidSdkResolution.resolve(null, local, mapOf("ANDROID_HOME" to dir("env-sdk").path))
        assertEquals(localSdk, resolved)
    }

    @Test
    fun `falls back to ANDROID_HOME when neither agp nor local properties resolve`() {
        val envSdk = dir("env-sdk")
        val resolved = AndroidSdkResolution.resolve(null, File(tmp.root, "missing.properties"), mapOf("ANDROID_HOME" to envSdk.path))
        assertEquals(envSdk, resolved)
    }

    @Test
    fun `falls back to ANDROID_SDK_ROOT when ANDROID_HOME is unset`() {
        val envSdk = dir("env-sdk")
        val resolved = AndroidSdkResolution.resolve(null, File(tmp.root, "missing.properties"), mapOf("ANDROID_SDK_ROOT" to envSdk.path))
        assertEquals(envSdk, resolved)
    }

    @Test
    fun `returns null when nothing resolves to a real directory`() {
        val resolved = AndroidSdkResolution.resolve(
            agpSdkDir = File("/does/not/exist"),
            localPropertiesFile = File(tmp.root, "missing.properties"),
            env = mapOf("ANDROID_HOME" to "/also/does/not/exist"),
        )
        assertNull(resolved)
    }
}
