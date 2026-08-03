package dev.thuat.hotreload.cli

import org.junit.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// F5: the old filter substring-matched the whole absolute path ("src" / "build" anywhere), so a
// project checked out under a path containing "build" registered zero watch dirs (`run` hangs
// forever with no explanation) and a path containing "src" registered .git/.idea as watchable.
class MainTest {
    // Deliberately contains "build" as a path segment, to prove the fix isn't substring-based.
    private val projectDir = Paths.get("/home/dev/build-tools/myapp")

    @Test
    fun `src dir is watchable even when the project path contains build as a substring`() {
        assertTrue(isWatchableDir(projectDir.resolve("app/src/main/kotlin"), projectDir))
    }

    @Test
    fun `a build output dir is excluded`() {
        assertFalse(isWatchableDir(projectDir.resolve("app/build/tmp/kotlin-classes"), projectDir))
    }

    @Test
    fun `a dir that is neither src nor build is excluded`() {
        assertFalse(isWatchableDir(projectDir.resolve("app/res"), projectDir))
    }

    @Test
    fun `hidden vcs and tool dirs are excluded even under a src-containing project path`() {
        val srcLikeProject = Paths.get("/home/dev/src/myapp")
        assertFalse(isWatchableDir(srcLikeProject.resolve(".git"), srcLikeProject))
        assertFalse(isWatchableDir(srcLikeProject.resolve(".idea"), srcLikeProject))
        assertFalse(isWatchableDir(srcLikeProject.resolve(".hotreload"), srcLikeProject))
        assertFalse(isWatchableDir(srcLikeProject.resolve(".gradle"), srcLikeProject))
    }

    @Test
    fun `the project root itself is not watchable`() {
        assertFalse(isWatchableDir(projectDir, projectDir))
    }

    @Test
    fun `a nested src dir several levels deep is watchable`() {
        assertTrue(isWatchableDir(projectDir.resolve("feature/src/main/kotlin/pkg"), projectDir))
    }
}

// The bug this guards against: the old default resolved relative to the process's CWD, which
// only happened to work when the CLI was launched from the tool checkout itself — the documented
// workflow launches it from the *consumer* project dir instead, so that default silently pointed
// at a directory inside the consumer's tree that never contains the agent .so.
class ResolveAgentSoDirTest {
    private val cwd = Paths.get("/home/dev/some-consumer-project")

    @Test
    fun `hotreload home property present resolves to home slash agent`() {
        val result = resolveAgentSoDir(explicit = null, homeEnv = "/opt/hotreload-cli", cwd = cwd)
        assertEquals(Paths.get("/opt/hotreload-cli/agent"), result)
    }

    @Test
    fun `hotreload home property absent falls back to cwd-relative dev-checkout path`() {
        val result = resolveAgentSoDir(explicit = null, homeEnv = null, cwd = cwd)
        assertEquals(
            cwd.resolve("agent/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib"),
            result,
        )
    }

    @Test
    fun `explicit agent-so-dir wins even when hotreload home property is set`() {
        val result = resolveAgentSoDir(explicit = "/custom/dir", homeEnv = "/opt/hotreload-cli", cwd = cwd)
        assertEquals(Paths.get("/custom/dir"), result)
    }
}
