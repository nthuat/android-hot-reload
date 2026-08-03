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
    const val STATUS_FAIL: Byte = 0x02   // real incompatibility: RedefineClasses rejected the bytecode (structural change, unsupported in v1). A not-yet-loaded class is NOT this — it's skipped instead (see the LOAD_DEX detail format below).

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

    // A successful (STATUS_OK) LOAD_DEX reply's `detail` follows this format, built by the
    // agent (agent.cpp HandleLoadDex/ServeClient) — must match byte-for-byte:
    //   "<result>[ | skipped <N>: <d1>, <d2>, ...][ | tierN]"
    // - <result> is "<redefined descriptors, comma-joined>: redefined" when at least one class
    //   was redefined, or "nothing redefined: all <N> class(es) not loaded" when none were.
    // - The optional " | skipped <N>: ..." segment lists descriptors of classes that were part
    //   of the batch but not currently loaded in the running app, so they were left untouched
    //   instead of failing the whole batch (see agent.cpp HandleLoadDex doc for why this is
    //   safe — every descriptor here was already in the baseline snapshot, never a brand-new
    //   class). Only present when at least one class was skipped.
    // - The optional trailing " | tierN" segment is appended only when at least one class was
    //   actually redefined (see ReloadOrchestrator.parseTier / agent.cpp NotifyRuntime).
    // Segments always appear in this order and are joined with " | ", so
    // `substringAfterLast(" | ")` reliably finds the tier regardless of whether a skipped
    // segment is present.

    // A PING reply's `detail` is "pong:<pkg>" where <pkg> is the package name the agent read
    // from its own /proc/self/cmdline (same string it uses to build its per-package abstract
    // socket name — see ReloadOrchestrator.agentSocketName / agent.cpp's ReadOwnPackageName).
    // Must match agent.cpp's ServeClient PING branch byte-for-byte. The CLI checks this against
    // the package it expects (ReloadOrchestrator.verifyAgentIdentity) before ever sending
    // LOAD_DEX, so a stale/wrong `adb forward` mapping onto some other app's agent is caught by
    // protocol content, not just by re-issuing the forward.
    const val PING_REPLY_PREFIX: String = "pong:"

    fun pingPackageOf(detail: String): String? =
        detail.takeIf { it.startsWith(PING_REPLY_PREFIX) }?.removePrefix(PING_REPLY_PREFIX)

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
