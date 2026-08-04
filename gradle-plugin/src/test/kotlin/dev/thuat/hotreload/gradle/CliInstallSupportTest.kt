package dev.thuat.hotreload.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure tag/URL/version-extraction logic behind `hotReloadInstallCli` — no network, no Gradle. */
class CliInstallSupportTest {
    @Test
    fun `release tag adds the v prefix the Maven version doesn't have`() {
        assertEquals("v0.1.2", CliInstallSupport.releaseTag("0.1.2"))
    }

    @Test
    fun `download url points at the tagged release's cli zip asset`() {
        assertEquals(
            "https://github.com/nthuat/android-hot-reload/releases/download/v0.1.2/cli.zip",
            CliInstallSupport.downloadUrl("0.1.2"),
        )
    }

    @Test
    fun `version is extracted from the trailing segment of a group colon artifact colon version coordinate`() {
        assertEquals("0.1.2", CliInstallSupport.versionFromCoordinate("dev.thuat:hotreload-runtime:0.1.2"))
    }

    @Test
    fun `version extraction is agnostic to group and artifact id, e g JitPack coordinates`() {
        assertEquals("1.2.3", CliInstallSupport.versionFromCoordinate("com.github.nthuat.android-hot-reload:hotreload-runtime:1.2.3"))
    }
}
