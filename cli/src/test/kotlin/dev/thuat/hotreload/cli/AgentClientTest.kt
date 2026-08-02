package dev.thuat.hotreload.cli

import org.junit.Test
import java.net.ServerSocket
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.test.assertEquals

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
            it.loadDex(listOf("La/Foo;" to "/data/a.dex", "Lb/Bar;" to "/data/b.dex"))
        }
        assertEquals(Protocol.CMD_LOAD_DEX, receivedCmd)
        assertEquals("La/Foo;\n/data/a.dex${Protocol.RECORD_SEP}Lb/Bar;\n/data/b.dex", receivedPayload)
        assertEquals(Protocol.STATUS_OK, reply.status)
        server.close()
    }
}
