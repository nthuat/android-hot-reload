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
    const val STATUS_FAIL: Byte = 0x02   // real incompatibility: RedefineClasses rejected the bytecode, or a new class (unsupported in v1)

    // Environmental/agent-side error — malformed payload, unreadable dex file — distinct from
    // STATUS_FAIL so the orchestrator doesn't tell the user to "rebuild" for e.g. a disk hiccup.
    // See agent.cpp's HandleLoadDex for which failure paths return which status.
    const val STATUS_ERROR: Byte = 0x03

    // LOAD_DEX payload: one or more records separated by RECORD_SEP (ASCII Record Separator —
    // doesn't collide with class descriptors or filesystem paths). Each record is
    // "<descriptor>\n<device dex path>". All classes in one message are redefined via a single
    // JVMTI RedefineClasses(n, defs) call on the agent side so a multi-class edit applies
    // atomically — either every class swaps or none do, never a mid-batch mix of old/new code
    // (see agent.cpp HandleLoadDex and docs/superpowers/specs' agent section).
    const val RECORD_SEP: Char = '\u001E'

    fun encodeLoadDexPayload(records: List<Pair<String, String>>): ByteArray =
        records.joinToString(RECORD_SEP.toString()) { (descriptor, devicePath) -> "$descriptor\n$devicePath" }
            .toByteArray()

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
