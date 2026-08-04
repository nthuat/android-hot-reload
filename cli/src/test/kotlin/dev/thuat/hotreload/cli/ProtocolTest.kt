package dev.thuat.hotreload.cli

import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    // F8: STATUS_ERROR is distinct from STATUS_FAIL so environmental/agent-side failures
    // (malformed payload, unreadable dex) don't get mapped to "incompatible change — rebuild".
    @Test
    fun `STATUS_ERROR is distinct from STATUS_FAIL and STATUS_OK`() {
        assertEquals(0x03.toByte(), Protocol.STATUS_ERROR)
        assertTrue(Protocol.STATUS_ERROR != Protocol.STATUS_FAIL)
        assertTrue(Protocol.STATUS_ERROR != Protocol.STATUS_OK)
    }

    // F9: multi-class edits are one LOAD_DEX message carrying N "<descriptor>\n<path>\n<keys>"
    // records joined by RECORD_SEP, so the agent can RedefineClasses them in one atomic call.
    @Test
    fun `encodeLoadDexPayload joins records with RECORD_SEP`() {
        val payload = Protocol.encodeLoadDexPayload(
            listOf(LoadDexEntry("La/Foo;", "/data/a.dex"), LoadDexEntry("Lb/Bar;", "/data/b.dex"))
        )
        val text = String(payload, Charsets.UTF_8)
        assertEquals("La/Foo;\n/data/a.dex\n${Protocol.RECORD_SEP}Lb/Bar;\n/data/b.dex\n", text)
    }

    @Test
    fun `encodeLoadDexPayload with a single record has no separator`() {
        val payload = Protocol.encodeLoadDexPayload(listOf(LoadDexEntry("La/Foo;", "/data/a.dex")))
        assertEquals("La/Foo;\n/data/a.dex\n", String(payload, Charsets.UTF_8))
    }

    // Keys extracted by KeyMetaExtractor ride along as a third, space-separated field so the
    // on-device runtime can invalidate group keys directly instead of hunting for a holder class
    // (see ComposeInvalidator.reload / agent.cpp NotifyRuntime).
    @Test
    fun `encodeLoadDexPayload appends space-separated keys as a third field`() {
        val payload = Protocol.encodeLoadDexPayload(listOf(LoadDexEntry("La/Foo;", "/data/a.dex", listOf(123, -456))))
        assertEquals("La/Foo;\n/data/a.dex\n123 -456", String(payload, Charsets.UTF_8))
    }

    // PING reply detail is "pong:<pkg>" (see Protocol.pingPackageOf's doc) — must match
    // agent.cpp's ServeClient kCmdPing branch byte-for-byte.
    @Test
    fun `pingPackageOf extracts the package name from a pong colon reply`() {
        assertEquals("dev.thuat.hotreload.sample", Protocol.pingPackageOf("pong:dev.thuat.hotreload.sample"))
    }

    @Test
    fun `pingPackageOf returns null for a reply that does not start with the pong colon prefix`() {
        assertEquals(null, Protocol.pingPackageOf("pong"))
        assertEquals(null, Protocol.pingPackageOf(""))
        assertEquals(null, Protocol.pingPackageOf("garbage"))
    }

    // PING reply detail extended to "pong:<pkg>:<runtimeVersion>" (see Protocol.PING_REPLY_PREFIX's
    // doc) — must match agent.cpp's ServeClient kCmdPing branch (SendReply("pong:" + g_pkg_name +
    // ":" + ReadRuntimeVersion(env))) byte-for-byte. pingPackageOf must still extract only <pkg>.
    @Test
    fun `pingPackageOf still extracts just the package from the extended pong reply`() {
        assertEquals(
            "dev.thuat.hotreload.sample",
            Protocol.pingPackageOf("pong:dev.thuat.hotreload.sample:0.1.6"),
        )
    }

    @Test
    fun `pingRuntimeVersionOf extracts the runtime version from an extended pong reply`() {
        assertEquals(
            "0.1.6",
            Protocol.pingRuntimeVersionOf("pong:dev.thuat.hotreload.sample:0.1.6"),
        )
    }

    // An agent built before this fix replies with the old two-field "pong:<pkg>" shape (no
    // version field at all) — ReloadOrchestrator must treat this the same as an explicit
    // UNKNOWN_RUNTIME_VERSION, not crash on a missing field.
    @Test
    fun `pingRuntimeVersionOf returns null for the old two-field pong reply with no version`() {
        assertEquals(null, Protocol.pingRuntimeVersionOf("pong:dev.thuat.hotreload.sample"))
    }

    @Test
    fun `pingRuntimeVersionOf returns null for a reply that does not start with the pong colon prefix`() {
        assertEquals(null, Protocol.pingRuntimeVersionOf("garbage"))
        assertEquals(null, Protocol.pingRuntimeVersionOf(""))
    }

    @Test
    fun `pingRuntimeVersionOf recognizes the explicit unknown literal`() {
        assertEquals(
            Protocol.UNKNOWN_RUNTIME_VERSION,
            Protocol.pingRuntimeVersionOf("pong:dev.thuat.hotreload.sample:${Protocol.UNKNOWN_RUNTIME_VERSION}"),
        )
    }

    // <pkg> can never contain ':' (Java/Android package identifiers are letters/digits/'_'/'.'
    // only), so splitting on the FIRST ':' after the prefix must keep working even when
    // <runtimeVersion> itself contains ':' or other unexpected characters — it's always the last
    // field, with no terminator, so nothing after it needs escaping.
    @Test
    fun `pingRuntimeVersionOf handles a runtime version containing colons and other unusual characters`() {
        val detail = "pong:dev.thuat.hotreload.sample:0.1.6-SNAPSHOT+build:42 (dirty) ☕"
        assertEquals("dev.thuat.hotreload.sample", Protocol.pingPackageOf(detail))
        assertEquals("0.1.6-SNAPSHOT+build:42 (dirty) ☕", Protocol.pingRuntimeVersionOf(detail))
    }

    @Test
    fun `pingRuntimeVersionOf handles an empty runtime version field`() {
        assertEquals("", Protocol.pingRuntimeVersionOf("pong:dev.thuat.hotreload.sample:"))
    }
}
