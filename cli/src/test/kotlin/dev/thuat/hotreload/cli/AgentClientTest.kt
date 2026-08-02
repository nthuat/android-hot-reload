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
}
