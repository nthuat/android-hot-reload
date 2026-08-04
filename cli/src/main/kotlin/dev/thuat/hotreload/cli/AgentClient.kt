package dev.thuat.hotreload.cli

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket

// Connecting to a wedged device's forwarded socket (or one whose agent died mid-request) used to
// block forever — no connect timeout, no SO_TIMEOUT, so a dead agent hung the CLI exactly like an
// unbounded adb call did (see ProcessRunner.kt). localhost connect is normally instant; 5s is
// generous slack for a busy adb server.
const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
// A redefinition round-trip is normally ~100-900ms; the runtime's tier-1 path waits up to 2s
// internally for the tier string (see ComposeInvalidator.reload). 15s comfortably clears that
// without falsely reporting a slow-but-working device as dead.
const val DEFAULT_READ_TIMEOUT_MS = 15_000

class AgentClient(
    host: String,
    port: Int,
    connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : Closeable {
    private val socket = Socket().apply {
        connect(InetSocketAddress(host, port), connectTimeoutMs)
        soTimeout = readTimeoutMs
    }

    fun ping(): Reply = request(Protocol.CMD_PING, ByteArray(0))

    // records: one LoadDexEntry per changed class, all from one edit — see Protocol's RECORD_SEP
    // doc for why this is one message instead of one per class, and LoadDexEntry's doc for what
    // its keys field carries.
    fun loadDex(records: List<LoadDexEntry>): Reply =
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
