package dev.thuat.hotreload.cli

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

data class Reply(val status: Byte, val detail: String)

object Protocol {
    const val CMD_PING: Byte = 0x01
    const val CMD_LOAD_DEX: Byte = 0x02
    const val STATUS_OK: Byte = 0x00
    const val STATUS_FAIL: Byte = 0x02

    fun encodeRequest(cmd: Byte, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + 1 + payload.size)
            .putInt(1 + payload.size)
            .put(cmd)
            .put(payload)
            .array()

    fun decodeReply(input: InputStream): Reply {
        val data = DataInputStream(input)
        val len = data.readInt()
        if (len < 1) throw EOFException("invalid reply length $len")
        val status = data.readByte()
        val detail = ByteArray(len - 1)
        data.readFully(detail)
        return Reply(status, String(detail, Charsets.UTF_8))
    }
}
