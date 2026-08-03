package dev.thuat.hotreload.cli

import java.io.Closeable
import java.net.Socket

class AgentClient(host: String, port: Int) : Closeable {
    private val socket = Socket(host, port)

    fun ping(): Reply = request(Protocol.CMD_PING, ByteArray(0))

    // records: (descriptor, deviceDexPath) pairs, all classes from one edit — see Protocol's
    // RECORD_SEP doc for why this is one message instead of one per class.
    fun loadDex(records: List<Pair<String, String>>): Reply =
        request(Protocol.CMD_LOAD_DEX, Protocol.encodeLoadDexPayload(records))

    private fun request(cmd: Byte, payload: ByteArray): Reply {
        socket.getOutputStream().apply {
            write(Protocol.encodeRequest(cmd, payload))
            flush()
        }
        return Protocol.decodeReply(socket.getInputStream())
    }

    override fun close() = socket.close()
}
