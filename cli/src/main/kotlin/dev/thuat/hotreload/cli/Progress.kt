package dev.thuat.hotreload.cli

import java.io.PrintStream

// Verb per phase, keyed exactly like phaseMillis (see ReloadOrchestrator.PhaseListener /
// Main.kt's formatPhaseTimings) so a live "⟳ compiling…" line and the final
// "(compile 3.1s · …)" summary never use different names for the same phase.
private val phaseVerbs = mapOf(
    "keysnapshot" to "snapshotting",
    "compile" to "compiling",
    "diff" to "diffing",
    "dex" to "dexing",
    "push" to "pushing",
    "redefine" to "redefining",
)

// Pure: the text for one phase-started event, independent of how (or whether) it's displayed.
// `classCount` is only rendered for "dex" (e.g. "dexing 2 class(es)…"); ignored otherwise.
internal fun phaseMessage(phase: String, classCount: Int? = null): String {
    val verb = phaseVerbs[phase] ?: phase
    val suffix = if (phase == "dex" && classCount != null) " $classCount class(es)" else ""
    return "⟳ $verb$suffix…"
}

// Live "phase started" progress for a `cycle`, printed ahead of the existing (unchanged) final
// outcome line from Main.kt's report(). Two rendering modes, chosen once at startup:
//
// - Interactive (TTY): each phase overwrites the previous one in place (\r + clear-to-end-of-
//   line, no trailing newline) so a run doesn't spam one line per phase per save. `clear()`
//   erases the last progress line right before the final outcome line prints, so "✓ reloaded…"
//   lands on its own clean line.
// - Non-interactive (piped, redirected, CI, the e2e script): one plain line per phase, no
//   cursor-control bytes, appended like any other log line -- e2e/run-e2e.sh captures a cycle's
//   whole stdout and greps it for the final line, so nothing here may emit control characters
//   that would corrupt that capture or a `| cat`/`| grep` pipeline.
//
// Detected via System.console() != null by default (null whenever stdout -- or stdin -- isn't a
// real terminal, which also covers `$(...)` capture and piping): the one JDK-native signal for
// "is a human watching this scroll by right now", no extra dependency. --progress/--no-progress
// (Main.kt) override it explicitly, for forcing plain output in a real terminal (e.g. under
// `script`/`tee`) or forcing in-place updates somewhere console() is unreliable.
class ProgressReporter(private val interactive: Boolean, private val out: PrintStream = System.out) {
    private var lineOpen = false

    fun phase(phase: String, classCount: Int? = null) {
        val text = phaseMessage(phase, classCount)
        if (interactive) {
            out.print("\r$text[K")
            out.flush()
            lineOpen = true
        } else {
            out.println(text)
        }
    }

    fun clear() {
        if (interactive && lineOpen) {
            out.print("\r[K")
            out.flush()
            lineOpen = false
        }
    }
}
