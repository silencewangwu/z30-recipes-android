package com.z30lab.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.TimeoutCancellationException

/**
 * PTP/IP command/event socket. All socket state is confined to this class;
 * every timeout retires the socket, so late packets cannot be consumed by a
 * subsequent transaction (mirrors the iOS PacketSocket queue confinement).
 */
class PacketSocket(
    private val host: String,
    private val port: Int,
    @Suppress("unused") private val requireWifi: Boolean,
    private val scope: CoroutineScope
) {
    private val sendMutex = Mutex()
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var failure: Throwable? = null
    private val incoming = Channel<PTPPacket>(Channel.BUFFERED)
    private var readJob: Job? = null
    private val framer = PacketFramer()
    private val buffer = ByteArray(64 * 1024)

    val isFailed: Boolean get() = failure != null

    private fun ensureOpen() {
        val f = failure
        if (f != null) throw f
    }

    suspend fun open(timeoutMs: Long = 12_000) {
        ensureOpen()
        try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) {
                    val s = Socket()
                    socket = s
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(host, port), timeoutMs.toInt())
                    input = BufferedInputStream(s.getInputStream(), 64 * 1024)
                    output = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                }
            }
            readJob = scope.launch { readLoop() }
        } catch (e: Exception) {
            fail(e)
            throw e
        }
    }

    private suspend fun readLoop() {
        try {
            while (currentCoroutineContext().isActive) {
                val n = withContext(Dispatchers.IO) { input!!.read(buffer) }
                if (n < 0) {
                    fail(CameraError.Closed())
                    return
                }
                if (n == 0) continue
                val packets = framer.append(buffer.copyOf(n))
                for (p in packets) {
                    if (!incoming.trySend(p).isSuccess) {
                        fail(CameraError.Invalid("相机待处理数据过多"))
                        return
                    }
                }
            }
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** null timeout means wait indefinitely (used by the event channel). */
    suspend fun read(timeoutMs: Long? = 12_000): PTPPacket {
        ensureOpen()
        return try {
            if (timeoutMs == null) incoming.receive()
            else withTimeout(timeoutMs) { incoming.receive() }
        } catch (e: TimeoutCancellationException) {
            fail(CameraError.Timeout())
            throw CameraError.Timeout()
        } catch (e: Exception) {
            ensureOpen()
            throw e
        }
    }

    suspend fun write(packet: PTPPacket) {
        ensureOpen()
        try {
            withTimeout(12_000) {
                sendMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    ensureOpen()
                    withContext(Dispatchers.IO) {
                        output!!.write(packet.encoded)
                        output!!.flush()
                    }
                }
            }
        } catch (e: Exception) {
            fail(e)
            throw e
        }
    }

    fun close() {
        fail(CameraError.Closed())
    }

    private fun fail(error: Throwable) {
        synchronized(this) {
            if (failure != null) return
            failure = error
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // ignore
        }
        readJob?.cancel()
        incoming.close(error)
    }
}
