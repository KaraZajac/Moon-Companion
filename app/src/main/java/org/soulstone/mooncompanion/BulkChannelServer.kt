package org.soulstone.mooncompanion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * L2CAP CoC server for the Flipper-first bulk transfer side-channel.
 *
 * Owns a single BluetoothServerSocket listening on an insecure L2CAP
 * channel. Android picks the PSM at listen() time; we expose it so the
 * OpenBulkChannelResponse handler can hand it back to the Flipper over
 * GATT.
 *
 * A single pending-session slot is enough during Phase 3 bring-up —
 * the Flipper is about to dial within a second or two, and overlapping
 * transfers aren't a thing yet. Each handshake consumes (and clears)
 * the slot.
 *
 * Threading: listen/accept runs on a dedicated thread because
 * BluetoothServerSocket.accept() blocks. Data streaming to accepted
 * clients runs on further per-connection threads. Start/stop are
 * idempotent and safe to call from the service's main thread.
 */
class BulkChannelServer(private val adapter: BluetoothAdapter) {

    data class PendingSession(
        val sessionId: ByteArray,
        val kind: BulkKindLocal,
        val name: String,
        val totalBytes: Int,
    )

    /** Mirrors proto BulkKind without dragging the generated class through here. */
    enum class BulkKindLocal { UNSPECIFIED, DOWNLOAD_FAP, UPLOAD_FILE, FIRMWARE_UPDATE, ECHO_TEST }

    @Volatile var psm: Int = 0
        private set

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val secureRandom = SecureRandom()

    @Volatile private var pending: PendingSession? = null

    /** Start listening. Returns true if the PSM is now assigned. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running.get()) return true
        val socket = try {
            adapter.listenUsingInsecureL2capChannel()
        } catch (e: IOException) {
            DebugLog.e(TAG, "listenUsingInsecureL2capChannel failed: ${e.message}", e)
            return false
        } catch (e: SecurityException) {
            DebugLog.e(TAG, "BT permission denied for L2CAP listen: ${e.message}")
            return false
        }
        serverSocket = socket
        psm = socket.psm
        running.set(true)
        DebugLog.i(TAG, "L2CAP server listening on PSM $psm")

        acceptThread = thread(name = "moon-bulk-accept", isDaemon = true) {
            runAcceptLoop(socket)
        }
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            DebugLog.w(TAG, "serverSocket.close: ${e.message}")
        }
        serverSocket = null
        psm = 0
        pending = null
        DebugLog.i(TAG, "L2CAP server stopped")
    }

    /**
     * Remember an upcoming session so the Flipper's CoC connect can be
     * matched to its prior OpenBulkChannelRequest. Returns the fresh
     * session id that should also be sent back in the RPC response.
     */
    fun beginSession(kind: BulkKindLocal, name: String): PendingSession {
        val id = ByteArray(8).also { secureRandom.nextBytes(it) }
        val totalBytes = when (kind) {
            BulkKindLocal.ECHO_TEST -> ECHO_TEST_BYTES
            else -> 0
        }
        val session = PendingSession(id, kind, name, totalBytes)
        pending = session
        DebugLog.i(
            TAG,
            "Bulk session armed: kind=$kind name=$name bytes=$totalBytes id=${id.toHex()}"
        )
        return session
    }

    fun cancelSession(sessionId: ByteArray) {
        val p = pending
        if (p != null && p.sessionId.contentEquals(sessionId)) {
            pending = null
            DebugLog.i(TAG, "Bulk session cancelled id=${sessionId.toHex()}")
        }
    }

    private fun runAcceptLoop(socket: BluetoothServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (running.get()) DebugLog.w(TAG, "accept: ${e.message}")
                break
            }
            val session = pending
            pending = null

            if (session == null) {
                DebugLog.w(TAG, "CoC connection with no pending session — closing")
                safeClose(client)
                continue
            }

            DebugLog.i(
                TAG,
                "CoC accepted from ${clientDescription(client)} for kind=${session.kind}"
            )
            thread(name = "moon-bulk-stream", isDaemon = true) {
                try {
                    streamForSession(client, session)
                } catch (e: Exception) {
                    DebugLog.e(TAG, "stream threw: ${e.message}", e)
                } finally {
                    safeClose(client)
                }
            }
        }
        DebugLog.d(TAG, "accept loop exited")
    }

    private fun streamForSession(client: BluetoothSocket, session: PendingSession) {
        when (session.kind) {
            BulkKindLocal.ECHO_TEST -> streamEcho(client)
            else -> {
                DebugLog.w(TAG, "No producer wired for ${session.kind} yet — closing")
            }
        }
    }

    /**
     * Bring-up fixture: stream a deterministic 10 KB payload (byte[i] = i & 0xFF)
     * so the Flipper can verify both direction and content. Close on completion
     * so the Flipper's CoC disconnect callback signals "done, good."
     */
    private fun streamEcho(client: BluetoothSocket) {
        val out = client.outputStream
        val buf = ByteArray(CHUNK_SIZE)
        var sent = 0
        while (sent < ECHO_TEST_BYTES) {
            val remaining = ECHO_TEST_BYTES - sent
            val chunk = if (remaining >= CHUNK_SIZE) CHUNK_SIZE else remaining
            for (i in 0 until chunk) {
                buf[i] = ((sent + i) and 0xFF).toByte()
            }
            try {
                out.write(buf, 0, chunk)
                out.flush()
            } catch (e: IOException) {
                DebugLog.w(TAG, "echo write failed at byte $sent: ${e.message}")
                return
            }
            sent += chunk
        }
        DebugLog.i(TAG, "Echo test streamed $sent bytes")
    }

    @SuppressLint("MissingPermission")
    private fun clientDescription(client: BluetoothSocket): String = try {
        client.remoteDevice.address
    } catch (_: SecurityException) {
        "?"
    }

    private fun safeClose(client: BluetoothSocket) {
        try { client.close() } catch (_: IOException) {}
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "MoonBulk"
        const val ECHO_TEST_BYTES = 10 * 1024
        private const val CHUNK_SIZE = 240
    }
}
