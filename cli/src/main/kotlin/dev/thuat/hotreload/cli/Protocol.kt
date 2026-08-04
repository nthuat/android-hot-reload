package dev.thuat.hotreload.cli

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

data class Reply(val status: Byte, val detail: String)

// One LOAD_DEX record: a redefined class's descriptor, where its dex bytes sit on-device, and
// the Compose group keys the CLI extracted for it (empty when extraction found none — see
// KeyMetaExtractor.keysFor and Protocol.RECORD_SEP's doc for what an empty list means downstream).
data class LoadDexEntry(val descriptor: String, val devicePath: String, val keys: List<Int> = emptyList())

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
    // "<descriptor>\n<device dex path>\n<space-separated FunctionKeyMeta keys, may be empty>".
    // The third field carries the Compose group keys the CLI already extracted from the compiled
    // .class file on the host (see KeyMetaExtractor) — the on-device runtime can no longer
    // reliably find them itself on Compose 1.11+, where @FunctionKeyMeta is BINARY-retention and
    // applied directly to compiled methods instead of a reflectable holder class. An empty third
    // field (record ends in a bare trailing '\n') means "no keys known for this class"; the
    // runtime falls back to its own on-device lookup for that class (see ComposeInvalidator.reload
    // / agent.cpp NotifyRuntime). All classes in one message are redefined via a single
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

    // A PING reply's `detail` is "pong:<pkg>:<runtimeVersion>" where <pkg> is the package name
    // the agent read from its own /proc/self/cmdline (same string it uses to build its
    // per-package abstract socket name — see ReloadOrchestrator.agentSocketName / agent.cpp's
    // ReadOwnPackageName), and <runtimeVersion> is the on-device runtime library's own version
    // (see ComposeInvalidator.runtimeVersion / agent.cpp's ReadRuntimeVersion), or
    // UNKNOWN_RUNTIME_VERSION when the agent couldn't determine it — either the runtime predates
    // this handshake (an already-published runtime jar with no such method) or
    // ComposeInvalidator hasn't loaded yet. Must match agent.cpp's ServeClient PING branch
    // byte-for-byte.
    //
    // <pkg> can never itself contain ':' (Java/Android package identifiers are letters, digits,
    // '_', and '.' only), so splitting on the FIRST ':' after the prefix unambiguously separates
    // the two fields even if <runtimeVersion> contains ':' or any other character (see
    // ProtocolTest for a version string exercising that) — <runtimeVersion> is always the last
    // field, with no terminator, so nothing after its first character needs escaping.
    //
    // The CLI checks <pkg> against the package it expects (ReloadOrchestrator.verifyAgentIdentity)
    // before ever sending LOAD_DEX, so a stale/wrong `adb forward` mapping onto some other app's
    // agent is caught by protocol content, not just by re-issuing the forward. It checks
    // <runtimeVersion> against its own version (ReloadOrchestrator.checkRuntimeVersion) before
    // ever sending LOAD_DEX too — the actual fix for a newer CLI silently no-op'ing a reload
    // against an older runtime (see the fix report).
    //
    // An agent built before this fix replies with the old two-field "pong:<pkg>" shape (no second
    // ':'); pingRuntimeVersionOf returns null for that shape, which ReloadOrchestrator treats the
    // same as an explicit UNKNOWN_RUNTIME_VERSION — this can only happen with a stale *agent*
    // .so, which ships inside cli.zip and is always this exact CLI build, so in practice it's
    // dead code today, kept only so an old two-field reply doesn't crash the parser.
    const val PING_REPLY_PREFIX: String = "pong:"
    const val UNKNOWN_RUNTIME_VERSION: String = "unknown"

    fun pingPackageOf(detail: String): String? =
        detail.takeIf { it.startsWith(PING_REPLY_PREFIX) }
            ?.removePrefix(PING_REPLY_PREFIX)
            ?.substringBefore(':')

    fun pingRuntimeVersionOf(detail: String): String? =
        detail.takeIf { it.startsWith(PING_REPLY_PREFIX) }
            ?.removePrefix(PING_REPLY_PREFIX)
            ?.let { rest -> if (':' in rest) rest.substringAfter(':') else null }

    fun encodeLoadDexPayload(records: List<LoadDexEntry>): ByteArray =
        records.joinToString(RECORD_SEP.toString()) { r ->
            "${r.descriptor}\n${r.devicePath}\n${r.keys.joinToString(" ")}"
        }.toByteArray()

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
