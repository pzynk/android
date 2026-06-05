package sols.sync.network.discovery

import android.util.Log
import sols.sync.network.NetworkConfig
import sols.sync.network.model.BroadcastMessage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens for UDP discovery broadcasts emitted by the desktop and
 * surfaces every discovered peer through [DeviceListener].
 *
 * Mirrors `desktop/src-tauri/src/network/discovery.rs` (the receiving
 * side instead of the broadcasting one).
 */
class DiscoveryScanner(
    private val port: Int = NetworkConfig.DISCOVERY_PORT,
    private val listener: DeviceListener,
) {
    /** Callback fired on a worker thread whenever a peer is heard. */
    fun interface DeviceListener {
        fun onDeviceFound(device: BroadcastMessage)
    }

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    /** Start listening. Safe to call once; subsequent calls are no-ops. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ listen() }, "sync-discovery").also { it.isDaemon = true; it.start() }
    }

    /** Stop listening and release the underlying socket. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        socket?.close()
        socket = null
        worker = null
    }

    private fun listen() {
        try {
            DatagramSocket(port).use { sock ->
                sock.broadcast = true
                sock.soTimeout = 1000
                socket = sock
                Log.i(TAG, "Listening for discovery broadcasts on UDP $port")

                val buffer = ByteArray(2048)
                while (running.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        sock.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val msg = BroadcastMessage.fromJson(raw)
                    if (msg != null) {
                        listener.onDeviceFound(msg)
                    } else {
                        Log.w(TAG, "Ignoring malformed broadcast: $raw")
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "Discovery scanner error", e)
        } finally {
            socket = null
        }
    }

    private companion object {
        const val TAG = "DiscoveryScanner"
    }
}
