package sols.sync.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.card.MaterialCardView
import sols.sync.R
import sols.sync.SyncApp
import sols.sync.network.model.BroadcastMessage
import sols.sync.system.SyncService
import sols.sync.system.TrustedPeersStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry-point screen. Starts the [SyncService] when visible and
 * renders the list of discovered desktops + the current connection
 * state from the app's global state.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var pairedContainer: LinearLayout
    private lateinit var pairedEmpty: TextView
    private lateinit var discoveredContainer: LinearLayout
    private lateinit var discoveredEmpty: TextView
    private lateinit var batteryOptBanner: MaterialCardView

    private val discovered: ConcurrentHashMap<String, BroadcastMessage>
        get() = (applicationContext as SyncApp).discoveredCache
    private val deviceStates: ConcurrentHashMap<String, String>
        get() = (applicationContext as SyncApp).deviceStates

    private lateinit var trustedPeersStore: TrustedPeersStore

    // Receiver to update UI when SyncService broadcasts state changes
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshList()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Start service regardless of notification permission, but notifications won't show if denied
        startSyncService()
    }

    // Launched when a paired device card is tapped; refreshes the list on return.
    private val deviceDetailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            refreshList()
        }
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
        pairedContainer = findViewById(R.id.paired_devices_container)
        pairedEmpty = findViewById(R.id.paired_devices_empty)
        discoveredContainer = findViewById(R.id.discovered_devices_container)
        discoveredEmpty = findViewById(R.id.discovered_devices_empty)

        trustedPeersStore = TrustedPeersStore(applicationContext)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.menu_main)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_received_files) {
                ReceivedFilesActivity.start(this)
                true
            } else {
                false
            }
        }

        batteryOptBanner = findViewById(R.id.battery_opt_banner)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_disable_battery_opt)
            .setOnClickListener { openBatteryOptimizationSettings() }

        checkPermissionsAndStartService()
        refreshList()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            stateReceiver,
            IntentFilter(SyncService.ACTION_STATE_CHANGED)
        )
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        checkBatteryOptimization()
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        super.onStop()
    }

    private fun checkPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                startSyncService()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startSyncService()
        }
    }

    private fun startSyncService() {
        val serviceIntent = Intent(this, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun refreshList() {
        val pairedPeers = trustedPeersStore.all()
        val pairedIds = pairedPeers.map { it.deviceId }.toSet()
        
        // Update paired devices
        pairedContainer.removeAllViews()
        if (pairedPeers.isEmpty()) {
            pairedEmpty.visibility = View.VISIBLE
        } else {
            pairedEmpty.visibility = View.GONE
            val inflater = LayoutInflater.from(this)
            pairedPeers.forEach { peer ->
                val itemView = inflater.inflate(R.layout.item_device, pairedContainer, false)
                itemView.findViewById<TextView>(R.id.device_name).text = peer.name
                
                // Get connection status - defaults to Disconnected if not Connected/Connecting
                var state = deviceStates[peer.deviceId] ?: "Disconnected"
                if (state != "Connected" && state != "Connecting") {
                    state = "Disconnected"
                }
                
                val disc = discovered[peer.deviceId]
                itemView.findViewById<TextView>(R.id.device_address).text = if (disc != null) "${disc.ip}:${disc.port}" else "Offline"
                itemView.findViewById<TextView>(R.id.device_id).text = "ID: ${peer.deviceId}"
                
                val statusBadge = itemView.findViewById<TextView>(R.id.device_status_badge)
                statusBadge.text = state
                if (state == "Connected") {
                    statusBadge.setBackgroundResource(R.drawable.bg_badge_connected)
                    statusBadge.setTextColor(android.graphics.Color.WHITE)
                    statusView.text = "Connected to ${peer.name}"
                } else if (state == "Connecting") {
                    statusBadge.setBackgroundResource(R.drawable.bg_status_badge)
                    val typedValue = android.util.TypedValue()
                    theme.resolveAttribute(com.google.android.material.R.attr.colorOnSecondaryContainer, typedValue, true)
                    statusBadge.setTextColor(typedValue.data)
                } else {
                    statusBadge.setBackgroundResource(R.drawable.bg_status_badge)
                    val typedValue = android.util.TypedValue()
                    theme.resolveAttribute(com.google.android.material.R.attr.colorOutline, typedValue, true)
                    statusBadge.setTextColor(typedValue.data)
                }
                
                // Set OS-specific icon
                val os = disc?.os ?: peer.os
                val iconView = itemView.findViewById<ImageView>(R.id.device_icon)
                val osIcon = getOsIconResource(os)
                iconView.setImageResource(osIcon)
                if (osIcon != R.drawable.ic_desktop) {
                    iconView.imageTintList = null
                }

                // Tap → open device detail screen
                val peerId = peer.deviceId
                itemView.setOnClickListener {
                    deviceDetailLauncher.launch(
                        android.content.Intent(this, DeviceDetailActivity::class.java)
                            .putExtra("device_id", peerId)
                    )
                }

                pairedContainer.addView(itemView)
            }
        }

        // Update discovered devices (excluding paired devices)
        val discoveredPeers = discovered.values.filter { it.deviceId !in pairedIds }
        
        discoveredContainer.removeAllViews()
        if (discoveredPeers.isEmpty()) {
            discoveredEmpty.visibility = View.VISIBLE
        } else {
            discoveredEmpty.visibility = View.GONE
            val inflater = LayoutInflater.from(this)
            discoveredPeers.forEach { d ->
                val itemView = inflater.inflate(R.layout.item_device, discoveredContainer, false)
                itemView.findViewById<TextView>(R.id.device_name).text = d.name
                itemView.findViewById<TextView>(R.id.device_address).text = "${d.ip}:${d.port}"
                itemView.findViewById<TextView>(R.id.device_id).text = "ID: ${d.deviceId}"
                
                val statusBadge = itemView.findViewById<TextView>(R.id.device_status_badge)
                val state = deviceStates[d.deviceId] ?: "Discovered"
                statusBadge.text = state
                if (state == "Connected") {
                    statusBadge.setBackgroundResource(R.drawable.bg_badge_connected)
                    statusBadge.setTextColor(android.graphics.Color.WHITE)
                } else if (state == "Connecting") {
                    statusBadge.setBackgroundResource(R.drawable.bg_status_badge)
                    val typedValue = android.util.TypedValue()
                    theme.resolveAttribute(com.google.android.material.R.attr.colorOnSecondaryContainer, typedValue, true)
                    statusBadge.setTextColor(typedValue.data)
                } else {
                    statusBadge.setBackgroundResource(R.drawable.bg_status_badge)
                    val typedValue = android.util.TypedValue()
                    theme.resolveAttribute(com.google.android.material.R.attr.colorOutline, typedValue, true)
                    statusBadge.setTextColor(typedValue.data)
                }
                
                // Set OS-specific icon
                val iconView = itemView.findViewById<ImageView>(R.id.device_icon)
                val osIcon = getOsIconResource(d.os)
                iconView.setImageResource(osIcon)
                if (osIcon != R.drawable.ic_desktop) {
                    iconView.imageTintList = null
                }
                
                itemView.setOnClickListener {
                    val intent = Intent(this, SyncService::class.java).apply {
                        action = SyncService.ACTION_CONNECT_DEVICE
                        putExtra(SyncService.EXTRA_DEVICE_ID, d.deviceId)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
                
                discoveredContainer.addView(itemView)
            }
        }
    }

    private fun getOsIconResource(os: String): Int {
        return when (os.lowercase()) {
            "windows" -> R.drawable.ic_windows
            "macos", "darwin" -> R.drawable.ic_mac
            "linux", "ubuntu" -> R.drawable.ic_ubuntu
            else -> R.drawable.ic_desktop
        }
    }

    /**
     * Shows the battery optimization banner if the system is restricting background
     * activity for this app, hides it otherwise.
     */
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
            batteryOptBanner.visibility = if (isIgnoring) View.GONE else View.VISIBLE
        }
        // Below API 23 battery optimization APIs don't exist, keep banner hidden.
    }

    /**
     * Opens the system settings screen that lets the user exempt this app from
     * battery optimization. Falls back to the general battery settings page if
     * the direct intent isn't supported.
     */
    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                // Device doesn't support this settings screen at all
            }
        }
    }
}
