package sols.sync.network.tcp

import android.util.Log
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Thin wrapper around a [Socket] used to open the sync TCP connection
 * to a desktop peer discovered via [sols.sync.network.discovery.DiscoveryScanner].
 *
 * Mirrors the desktop side `network::tcp` (the client side here).
 */
class TcpClient : Closeable {
    private var socket: Socket? = null

    /**
     * Connect to `host:port`. Blocking — call from a background thread.
     * @return true if the connection was established.
     */
    fun connect(host: String, port: Int, timeoutMs: Int = 3000): Boolean {
        close()
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            socket = s
            Log.i(TAG, "Connected to $host:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $host:$port", e)
            false
        }
    }

    /** Send a UTF-8 encoded payload. Blocking. */
    fun send(payload: String): Boolean {
        val s = socket ?: return false
        return try {
            s.getOutputStream().apply {
                write(payload.toByteArray(Charsets.UTF_8))
                flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            false
        }
    }

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private companion object {
        const val TAG = "TcpClient"
    }
}
