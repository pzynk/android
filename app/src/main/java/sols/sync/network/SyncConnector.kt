package sols.sync.network

import android.util.Log
import sols.sync.network.discovery.DiscoveryScanner
import sols.sync.network.model.BroadcastMessage
import sols.sync.network.tcp.TcpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * High-level façade that wires UDP discovery to the TCP client.
 *
 * Usage:
 * ```
 * val connector = SyncConnector { event -> /* update UI */ }
 * connector.start()
 * // ...
 * connector.stop()
 * ```
 */
class SyncConnector(
    private val listener: (Event) -> Unit,
) {
    sealed class Event {
        /** Emitted the first time a peer is seen. */
        data class DeviceDiscovered(val device: BroadcastMessage) : Event()
        /** Emitted after a successful TCP connect. */
        data class Connected(val device: BroadcastMessage) : Event()
        /** Emitted on connection failure. */
        data class ConnectFailed(val device: BroadcastMessage, val reason: String) : Event()
    }

    private val known = ConcurrentHashMap.newKeySet<String>()
    private val ioPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sync-connector").apply { isDaemon = true }
    }
    private val tcp = TcpClient()

    private val scanner = DiscoveryScanner(NetworkConfig.DISCOVERY_PORT) { device ->
        if (known.add(device.ip)) {
            Log.i(TAG, "Discovered $device")
            listener(Event.DeviceDiscovered(device))
            ioPool.execute { connect(device) }
        }
    }

    fun start() = scanner.start()

    fun stop() {
        scanner.stop()
        tcp.close()
        ioPool.shutdownNow()
    }

    /** Force a (re)connect to the given device. */
    fun connect(device: BroadcastMessage) {
        if (tcp.connect(device.ip, device.port)) {
            listener(Event.Connected(device))
        } else {
            listener(Event.ConnectFailed(device, "Could not open TCP socket"))
        }
    }

    private companion object {
        const val TAG = "SyncConnector"
    }
}
