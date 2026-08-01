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
