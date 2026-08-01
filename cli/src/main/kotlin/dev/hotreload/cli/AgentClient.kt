package dev.hotreload.cli

import java.io.Closeable
import java.net.Socket

class AgentClient(host: String, port: Int) : Closeable {
    private val socket = Socket(host, port)

    fun ping(): Reply = request(Protocol.CMD_PING, ByteArray(0))

    fun loadDex(descriptor: String, deviceDexPath: String): Reply =
        request(Protocol.CMD_LOAD_DEX, "$descriptor\n$deviceDexPath".toByteArray())

    private fun request(cmd: Byte, payload: ByteArray): Reply {
        socket.getOutputStream().apply {
            write(Protocol.encodeRequest(cmd, payload))
            flush()
        }
        return Protocol.decodeReply(socket.getInputStream())
    }

    override fun close() = socket.close()
}
