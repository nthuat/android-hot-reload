package dev.thuat.hotreload.cli

import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.test.assertEquals

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
}
