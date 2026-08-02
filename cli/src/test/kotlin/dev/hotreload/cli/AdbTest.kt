package dev.hotreload.cli

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.writeText
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
    fun `runAsCopy shape - mkdir then cp wrapped as one sh -c argv token`() {
        val fake = FakeRunner()
        Adb("adb", null, fake).runAsCopy("dev.hotreload.sample", "/data/local/tmp/hotreload/agent.so", "hotreload/agent.so")
        val argv = fake.calls.single()
        assertEquals(listOf("adb", "shell", "run-as", "dev.hotreload.sample", "sh", "-c"), argv.dropLast(1))
        assertEquals(1, argv.size - 6)  // exactly one trailing argv token holding the whole script
        val script = argv.last()
        assertTrue(script.startsWith("'") && script.endsWith("'"))
        assertTrue(script.contains("mkdir -p"))
        assertTrue(script.contains("cp "))
    }

    // `adb shell` joins its trailing argv with spaces and hands the whole line to one
    // remote shell; run-as then execs `sh -c <script>` untouched, so <script> is parsed
    // a *second*, independent time by an inner shell. Reproduce both parses locally
    // with a real /bin/sh (run-as is a transparent exec, so it's elided rather than
    // text-substituted) to verify paths with shell-special characters — notably the
    // `$` in Kotlin inner-class names like "GreetingKt$KeyMeta.dex" — survive both
    // passes as literal data instead of being expanded as a variable reference.
    //
    // A fake `cp` shadowing the real one via PATH records its argv without touching
    // the script text — text-substituting inside an already quote-balanced script
    // would corrupt that balance.
    private fun runAsCopyArgsSeenByRemoteCp(fromDeviceTmp: String, toRelPath: String): List<String> {
        val fake = FakeRunner()
        Adb("adb", null, fake).runAsCopy("pkg", fromDeviceTmp, toRelPath)
        val argv = fake.calls.single()
        check(argv.subList(0, 5) == listOf("adb", "shell", "run-as", "pkg", "sh")) { argv }
        val outerLine = "sh " + argv.drop(5).joinToString(" ")  // "sh -c '<script>'"

        val workDir = Files.createTempDirectory("runascopy-test")
        val record = workDir.resolve("cp-args.txt")
        val fakeCp = workDir.resolve("cp")
        fakeCp.writeText("#!/bin/sh\nprintf '%s\\n' \"\$1\" \"\$2\" > \"$record\"\n")
        fakeCp.toFile().setExecutable(true)

        val proc = ProcessBuilder("sh", "-c", outerLine)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
        proc.environment()["PATH"] = "$workDir:${proc.environment()["PATH"]}"
        val process = proc.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        check(Files.exists(record)) { "fake cp never ran; full output:\n$output" }
        return Files.readAllLines(record)
    }

    @Test
    fun `runAsCopy survives dollar signs in Kotlin inner-class dex filenames`() {
        val args = runAsCopyArgsSeenByRemoteCp(
            "/tmp/GreetingKt\$KeyMeta.dex",
            "hotreload/GreetingKt\$KeyMeta.dex",
        )
        assertEquals(listOf("/tmp/GreetingKt\$KeyMeta.dex", "code_cache/hotreload/GreetingKt\$KeyMeta.dex"), args.take(2))
    }

    @Test
    fun `runAsCopy survives single quotes embedded in paths`() {
        val args = runAsCopyArgsSeenByRemoteCp("/tmp/a'b.dex", "hotreload/a'b.dex")
        assertEquals(listOf("/tmp/a'b.dex", "code_cache/hotreload/a'b.dex"), args.take(2))
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

    @Test
    fun `getprop runs shell getprop and trims output`() {
        val fake = FakeRunner(ProcessResult(0, "arm64-v8a\n", ""))
        assertEquals("arm64-v8a", Adb("adb", "emulator-5554", fake).getprop("ro.product.cpu.abi"))
        assertEquals(
            listOf("adb", "-s", "emulator-5554", "shell", "getprop", "ro.product.cpu.abi"),
            fake.calls.single(),
        )
    }
}
