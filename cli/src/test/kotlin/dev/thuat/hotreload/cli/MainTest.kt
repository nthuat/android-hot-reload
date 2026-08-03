package dev.thuat.hotreload.cli

import org.junit.Test
import java.nio.file.Paths
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
