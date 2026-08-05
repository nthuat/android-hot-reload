package dev.thuat.hotreload.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Pure phase-message text -- see Progress.kt's phaseVerbs, keyed exactly like phaseMillis
// (ReloadOrchestrator.PhaseListener / Main.kt's formatPhaseTimings) so live progress and the
// final per-phase summary never drift on naming.
class PhaseMessageTest {
    @Test
    fun `each phase name known to phaseMillis has a matching verb`() {
        assertEquals("⟳ snapshotting…", phaseMessage("keysnapshot"))
        assertEquals("⟳ compiling…", phaseMessage("compile"))
        assertEquals("⟳ diffing…", phaseMessage("diff"))
        assertEquals("⟳ pushing…", phaseMessage("push"))
        assertEquals("⟳ redefining…", phaseMessage("redefine"))
    }

    @Test
    fun `dex phase includes the class count`() {
        assertEquals("⟳ dexing 2 class(es)…", phaseMessage("dex", 2))
    }

    @Test
    fun `dex phase without a count falls back to the bare verb`() {
        assertEquals("⟳ dexing…", phaseMessage("dex", null))
    }

    @Test
    fun `class count is ignored for every phase other than dex`() {
        assertEquals("⟳ compiling…", phaseMessage("compile", 5))
    }

    @Test
    fun `an unrecognized phase name falls back to itself so nothing is silently dropped`() {
        assertEquals("⟳ mystery…", phaseMessage("mystery"))
    }
}

// ProgressReporter's two rendering modes: in-place (TTY) vs plain appended lines (non-TTY, e.g.
// e2e/run-e2e.sh's `$(...)` capture or `| cat`). Exercised against a fake sink (ByteArrayOutputStream)
// rather than real stdout so the assertions can inspect exact bytes, including the absence of
// cursor-control characters in non-interactive mode.
class ProgressReporterTest {
    private fun sink(): Pair<ByteArrayOutputStream, PrintStream> {
        val buf = ByteArrayOutputStream()
        return buf to PrintStream(buf, true, "UTF-8")
    }

    @Test
    fun `non-interactive mode emits one plain line per phase transition`() {
        val (buf, out) = sink()
        val reporter = ProgressReporter(interactive = false, out = out)

        reporter.phase("compile")
        reporter.phase("dex", 2)
        reporter.phase("push")

        val text = buf.toString("UTF-8")
        assertEquals("⟳ compiling…\n⟳ dexing 2 class(es)…\n⟳ pushing…\n", text)
    }

    @Test
    fun `non-interactive mode never emits cursor control characters`() {
        val (buf, out) = sink()
        val reporter = ProgressReporter(interactive = false, out = out)

        reporter.phase("compile")
        reporter.phase("dex", 2)
        reporter.clear()  // no-op off-TTY

        val text = buf.toString("UTF-8")
        assertFalse(text.contains('\r'), "non-interactive output must not contain carriage returns: $text")
        assertFalse(text.contains(''), "non-interactive output must not contain ANSI escapes: $text")
    }

    @Test
    fun `interactive mode overwrites the previous phase in place with a carriage return`() {
        val (buf, out) = sink()
        val reporter = ProgressReporter(interactive = true, out = out)

        reporter.phase("compile")
        reporter.phase("push")

        val text = buf.toString("UTF-8")
        assertEquals("\r⟳ compiling…[K\r⟳ pushing…[K", text)
    }

    @Test
    fun `interactive mode clear erases the last in-place line before the final outcome prints`() {
        val (buf, out) = sink()
        val reporter = ProgressReporter(interactive = true, out = out)

        reporter.phase("push")
        reporter.clear()

        val text = buf.toString("UTF-8")
        assertEquals("\r⟳ pushing…[K\r[K", text)
    }

    @Test
    fun `interactive mode clear before any phase is a no-op`() {
        val (buf, out) = sink()
        val reporter = ProgressReporter(interactive = true, out = out)

        reporter.clear()

        assertEquals("", buf.toString("UTF-8"))
    }
}

// Pins the exact final success line -- asserted verbatim by e2e/run-e2e.sh and shown in the
// README -- so a future change to progress rendering (or anything else in Main.kt) cannot
// silently alter it. See reloadedLine's doc: it's the same expression report() always printed,
// just pulled out for direct testing.
class ReloadedLineTest {
    @Test
    fun `pins the final reload line format, including the phase-timing suffix`() {
        val outcome = CycleOutcome.Reloaded(
            classes = listOf("com.example.FooKt"),
            millis = 4092,
            tier = "tier1",
            phaseMillis = linkedMapOf(
                "keysnapshot" to 20L,
                "compile" to 3100L,
                "diff" to 40L,
                "dex" to 500L,
                "push" to 300L,
                "redefine" to 100L,
            ),
        )

        assertEquals(
            "✓ reloaded 1 class(es) in 4092ms [tier1: remember state preserved]: com.example.FooKt " +
                "(keysnapshot 0.0s · compile 3.1s · diff 0.0s · dex 0.5s · push 0.3s · redefine 0.1s)",
            reloadedLine(outcome),
        )
    }

    @Test
    fun `omits the phase-timing suffix entirely when phaseMillis is empty, as for bootstrap`() {
        val outcome = CycleOutcome.Reloaded(classes = emptyList(), millis = 0)
        assertEquals("✓ reloaded 0 class(es) in 0ms: ", reloadedLine(outcome))
    }

    @Test
    fun `omits the tier suffix when tier is null`() {
        val outcome = CycleOutcome.Reloaded(classes = listOf("com.example.FooKt"), millis = 10)
        assertTrue(reloadedLine(outcome).startsWith("✓ reloaded 1 class(es) in 10ms: com.example.FooKt"))
    }
}
