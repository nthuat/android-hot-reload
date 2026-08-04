package dev.thuat.hotreload.cli

import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class AgentClientTest {
    @Test
    fun `sends request and decodes reply over tcp`() {
        val server = ServerSocket(0)
        thread {
            server.accept().use { s ->
                val input = java.io.DataInputStream(s.getInputStream())
                val len = input.readInt()
                val body = ByteArray(len); input.readFully(body)
                assertEquals(Protocol.CMD_PING, body[0])
                val detail = "pong".toByteArray()
                s.getOutputStream().write(
                    ByteBuffer.allocate(4 + 1 + detail.size)
                        .putInt(1 + detail.size).put(Protocol.STATUS_OK).put(detail).array()
                )
                s.getOutputStream().flush()
            }
        }
        val reply = AgentClient("localhost", server.localPort).use { it.ping() }
        assertEquals(Protocol.STATUS_OK, reply.status)
        assertEquals("pong", reply.detail)
        server.close()
    }

    // F9: multiple classes from one edit travel as one LOAD_DEX message.
    @Test
    fun `loadDex sends all records as one RECORD_SEP-joined LOAD_DEX payload`() {
        val server = ServerSocket(0)
        var receivedCmd: Byte = -1
        var receivedPayload = ""
        thread {
            server.accept().use { s ->
                val input = java.io.DataInputStream(s.getInputStream())
                val len = input.readInt()
                val body = ByteArray(len); input.readFully(body)
                receivedCmd = body[0]
                receivedPayload = String(body, 1, body.size - 1, Charsets.UTF_8)
                val detail = "La/Foo;, Lb/Bar;: redefined | tier1".toByteArray()
                s.getOutputStream().write(
                    ByteBuffer.allocate(4 + 1 + detail.size)
                        .putInt(1 + detail.size).put(Protocol.STATUS_OK).put(detail).array()
                )
                s.getOutputStream().flush()
            }
        }
        val reply = AgentClient("localhost", server.localPort).use {
            it.loadDex(listOf(LoadDexEntry("La/Foo;", "/data/a.dex"), LoadDexEntry("Lb/Bar;", "/data/b.dex")))
        }
        assertEquals(Protocol.CMD_LOAD_DEX, receivedCmd)
        assertEquals("La/Foo;\n/data/a.dex\n${Protocol.RECORD_SEP}Lb/Bar;\n/data/b.dex\n", receivedPayload)
        assertEquals(Protocol.STATUS_OK, reply.status)
        server.close()
    }

    // Reproduces the reported hang one layer up from ProcessRunner: a dead/wedged agent that
    // accepts the TCP connection but never replies used to block Protocol.decodeReply's read
    // forever (no SO_TIMEOUT at all). A short injected readTimeoutMs keeps this test itself fast
    // rather than waiting out the real 15s production default.
    @Test
    fun `read times out against a server that accepts but never replies, instead of hanging forever`() {
        val server = ServerSocket(0)
        thread {
            runCatching { server.accept() }  // accept the connection, then just sit there
        }
        val elapsed = measureTimeMillis {
            try {
                AgentClient("localhost", server.localPort, readTimeoutMs = 200).use { it.ping() }
                fail("expected a SocketTimeoutException")
            } catch (e: SocketTimeoutException) {
                // expected — the bound this test verifies
            }
        }
        assertTrue(elapsed < 5_000, "read timeout took far longer than the injected 200ms bound: ${elapsed}ms")
        server.close()
    }
}
