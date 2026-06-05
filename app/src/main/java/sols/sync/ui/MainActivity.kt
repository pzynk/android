package sols.sync.ui

import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import sols.sync.R
import sols.sync.network.SyncConnector
import sols.sync.network.model.BroadcastMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry-point screen. Starts the [SyncConnector] when visible and
 * renders the list of discovered desktops + the current connection
 * state.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var devicesView: TextView

    private val discovered = ConcurrentHashMap<String, BroadcastMessage>()
    private var multicastLock: WifiManager.MulticastLock? = null

    private val connector = SyncConnector { event ->
        runOnUiThread { handleEvent(event) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        statusView = findViewById(R.id.status)
        devicesView = findViewById(R.id.devices)
    }

    override fun onStart() {
        super.onStart()
        acquireMulticastLock()
        connector.start()
    }

    override fun onStop() {
        connector.stop()
        releaseMulticastLock()
        super.onStop()
    }

    private fun handleEvent(event: SyncConnector.Event) {
        when (event) {
            is SyncConnector.Event.DeviceDiscovered -> {
                discovered[event.device.ip] = event.device
                statusView.text = getString(R.string.discovery_searching)
                refreshList()
            }
            is SyncConnector.Event.Connected -> {
                statusView.text = "Connected to ${event.device.name} (${event.device.ip}:${event.device.port})"
            }
            is SyncConnector.Event.ConnectFailed -> {
                statusView.text = "Failed to connect to ${event.device.name}: ${event.reason}"
            }
        }
    }

    private fun refreshList() {
        devicesView.text = discovered.values.joinToString("\n") { d ->
            "• ${d.name}  →  ${d.ip}:${d.port}"
        }
    }

    private fun acquireMulticastLock() {
        // Required on some Wi-Fi stacks to receive broadcast UDP frames
        // while the screen is off.
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("sync-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
    }
}
