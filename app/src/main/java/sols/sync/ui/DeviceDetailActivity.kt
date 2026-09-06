package sols.sync.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import androidx.activity.enableEdgeToEdge
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import sols.sync.R
import sols.sync.SyncApp
import sols.sync.network.model.BroadcastMessage
import sols.sync.system.TrustedPeersStore
import sols.sync.system.SyncService

/**
 * Shows the full details of a paired device and allows the user to unpair.
 *
 * Launch via [DeviceDetailActivity.start].
 */
class DeviceDetailActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"

        fun start(
            context: Context,
            deviceId: String,
        ) {
            context.startActivity(
                Intent(context, DeviceDetailActivity::class.java)
                    .putExtra(EXTRA_DEVICE_ID, deviceId)
            )
        }
    }

    private lateinit var trustedPeersStore: TrustedPeersStore
    private lateinit var deviceId: String

    // Views
    private lateinit var toolbar: MaterialToolbar
    private lateinit var iconView: ImageView
    private lateinit var nameView: TextView
    private lateinit var statusBadge: TextView
    private lateinit var addressView: TextView
    private lateinit var osView: TextView
    private lateinit var idView: TextView
    private lateinit var btnUnpair: MaterialButton

    private val pickFileLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
        uri?.let { handleSelectedFile(it) }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUi()
        }
    }

    private val audioErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val errorDeviceId = intent?.getStringExtra(SyncService.EXTRA_DEVICE_ID)
            val errorMessage = intent?.getStringExtra(SyncService.EXTRA_ERROR_MESSAGE) ?: "Audio streaming failed"
            if (errorDeviceId == null || errorDeviceId == deviceId) {
                val switchAudioStream = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_audio_stream)
                switchAudioStream?.isChecked = false
                MaterialAlertDialogBuilder(this@DeviceDetailActivity)
                    .setTitle("Listen Through Mobile Error")
                    .setMessage(errorMessage)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_device_detail)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.device_detail_root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val id = intent.getStringExtra(EXTRA_DEVICE_ID) ?: run {
            finish()
            return
        }
        deviceId = id

        trustedPeersStore = TrustedPeersStore(applicationContext)

        // Bind views
        toolbar = findViewById(R.id.toolbar)
        iconView = findViewById(R.id.detail_device_icon)
        nameView = findViewById(R.id.detail_device_name)
        statusBadge = findViewById(R.id.detail_status_badge)
        addressView = findViewById(R.id.detail_device_address)
        osView = findViewById(R.id.detail_device_os)
        idView = findViewById(R.id.detail_device_id)
        btnUnpair = findViewById(R.id.btn_unpair)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        toolbar.inflateMenu(R.menu.menu_device_detail)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clipboard) {
                ClipboardActivity.start(this, deviceId)
                true
            } else {
                false
            }
        }

        updateUi()

        findViewById<android.view.View>(R.id.btn_quick_action_clipboard).setOnClickListener {
            ClipboardActivity.start(this, deviceId)
        }

        // Receive Files toggle
        val switchReceiveFiles = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_receive_files)
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        switchReceiveFiles.isChecked = prefs.getBoolean("receive_files_enabled", true)
        switchReceiveFiles.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("receive_files_enabled", isChecked).apply()
        }

        // Volume Sync toggle
        val switchVolumeSync = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_volume_sync)
        switchVolumeSync.isChecked = prefs.getBoolean("volume_sync_enabled", true)
        switchVolumeSync.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("volume_sync_enabled", isChecked).apply()
            val intent = Intent(this, SyncService::class.java).apply {
                action = "sols.sync.ACTION_UPDATE_VOLUME_SYNC"
            }
            startService(intent)
        }

        // Received Files list
        findViewById<android.view.View>(R.id.btn_quick_action_received_files).setOnClickListener {
            ReceivedFilesActivity.start(this)
        }

        // Media Control
        findViewById<android.view.View>(R.id.btn_quick_action_media).setOnClickListener {
            MediaControlActivity.start(this, deviceId)
        }

        // Terminal Access
        findViewById<android.view.View>(R.id.btn_quick_action_terminal).setOnClickListener {
            val app = applicationContext as SyncApp
            val state = app.deviceStates[deviceId] ?: "Disconnected"
            val info = app.terminalInfos[deviceId]
            if (state == "Connected" && info?.enabled == true) {
                TerminalActivity.start(this, deviceId)
            } else if (state != "Connected") {
                android.widget.Toast.makeText(this, "Device is not connected", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(this, "Terminal access is disabled on the desktop", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Send File
        findViewById<android.view.View>(R.id.btn_quick_action_send_file).setOnClickListener {
            val deviceStates = (applicationContext as SyncApp).deviceStates
            val state = deviceStates[deviceId] ?: "Disconnected"
            if (state == "Connected") {
                pickFileLauncher.launch("*/*")
            } else {
                android.widget.Toast.makeText(this, "Device is not connected", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Camera Stream
        findViewById<android.view.View>(R.id.btn_quick_action_camera).setOnClickListener {
            val app = applicationContext as SyncApp
            val state = app.deviceStates[deviceId] ?: "Disconnected"
            if (state == "Connected") {
                CameraStreamActivity.start(this, deviceId)
            } else {
                android.widget.Toast.makeText(this, "Device is not connected", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Mic Stream
        findViewById<android.view.View>(R.id.btn_quick_action_mic)?.setOnClickListener {
            val app = applicationContext as SyncApp
            val state = app.deviceStates[deviceId] ?: "Disconnected"
            if (state == "Connected") {
                MicStreamActivity.start(this, deviceId)
            } else {
                android.widget.Toast.makeText(this, "Device is not connected", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Listen Through Mobile toggle
        val switchAudioStream = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_audio_stream)
        switchAudioStream.isChecked = prefs.getBoolean("audio_stream_enabled_$deviceId", false)
        switchAudioStream.setOnCheckedChangeListener { _, isChecked ->
            val app = applicationContext as SyncApp
            val state = app.deviceStates[deviceId] ?: "Disconnected"
            if (isChecked && state != "Connected") {
                switchAudioStream.isChecked = false
                prefs.edit().putBoolean("audio_stream_enabled_$deviceId", false).apply()
                MaterialAlertDialogBuilder(this)
                    .setTitle("Device Offline")
                    .setMessage("Cannot start audio streaming because this device is not connected.")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean("audio_stream_enabled_$deviceId", isChecked).apply()
            val intent = Intent(this, SyncService::class.java).apply {
                action = if (isChecked) SyncService.ACTION_START_AUDIO_STREAM else SyncService.ACTION_STOP_AUDIO_STREAM
                putExtra(SyncService.EXTRA_DEVICE_ID, deviceId)
            }
            startService(intent)
        }
        
        // Open audio settings when tapping the row
        findViewById<android.view.View>(R.id.btn_quick_action_audio_stream).setOnClickListener {
            AudioSettingsActivity.start(this, deviceId)
        }

        btnUnpair.setOnClickListener { confirmUnpair(deviceId) }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            stateReceiver,
            IntentFilter(SyncService.ACTION_STATE_CHANGED)
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            audioErrorReceiver,
            IntentFilter(SyncService.ACTION_AUDIO_STREAM_ERROR)
        )
        updateUi()
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(audioErrorReceiver)
        super.onStop()
    }

    private fun updateUi() {
        val peer = trustedPeersStore.get(deviceId)
        if (peer == null) {
            finish()
            return
        }

        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val switchAudioStream = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_audio_stream)
        val audioEnabled = prefs.getBoolean("audio_stream_enabled_$deviceId", false)
        if (switchAudioStream != null && switchAudioStream.isChecked != audioEnabled) {
            switchAudioStream.isChecked = audioEnabled
        }

        val discoveredCache = (applicationContext as? SyncApp)?.discoveredCache
        val liveDevice: BroadcastMessage? = discoveredCache?.get(deviceId)
        val app = applicationContext as SyncApp
        val deviceStates = app.deviceStates

        val resolvedOs = if (peer.os.isBlank() || peer.os.lowercase() == "unknown") {
            val detectedOs = liveDevice?.os?.takeIf { it.isNotBlank() && it.lowercase() != "unknown" }
            if (detectedOs != null) {
                trustedPeersStore.put(peer.copy(os = detectedOs))
                detectedOs
            } else {
                peer.os
            }
        } else {
            liveDevice?.os?.takeIf { it.isNotBlank() && it.lowercase() != "unknown" } ?: peer.os
        }

        populateUi(peer.name, peer.deviceId, resolvedOs, liveDevice, deviceStates)

        val btnTerminal = findViewById<android.view.View>(R.id.btn_quick_action_terminal)
        val tvTerminalStatus = findViewById<TextView>(R.id.tv_terminal_status)
        val info = app.terminalInfos[deviceId]
        val state = deviceStates[deviceId] ?: "Disconnected"

        if (state == "Connected" && info?.enabled == true) {
            btnTerminal.alpha = 1.0f
            tvTerminalStatus.text = "Connected • Tap to open terminal"
        } else {
            btnTerminal.alpha = 0.5f
            if (state != "Connected") {
                tvTerminalStatus.text = "Offline"
            } else {
                tvTerminalStatus.text = "Disabled on desktop"
            }
        }

        val btnCamera = findViewById<android.view.View>(R.id.btn_quick_action_camera)
        val tvCameraStatus = findViewById<TextView>(R.id.tv_camera_status)
        if (state == "Connected") {
            btnCamera.alpha = 1.0f
            if (app.isCameraStreaming) {
                tvCameraStatus.text = "Streaming on port ${app.cameraStreamingPort} • Tap to configure / stop"
            } else {
                tvCameraStatus.text = "Tap to configure or start camera stream"
            }
        } else {
            btnCamera.alpha = 0.5f
            tvCameraStatus.text = "Offline"
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun populateUi(
        name: String,
        deviceId: String,
        os: String,
        live: BroadcastMessage?,
        deviceStates: Map<String, String>?,
    ) {
        nameView.text = name
        idView.text = deviceId

        // OS icon
        val iconRes = getOsIconResource(os)
        iconView.setImageResource(iconRes)
        if (iconRes == R.drawable.ic_desktop) {
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnPrimaryContainer, typedValue, true)
            ImageViewCompat.setImageTintList(iconView, android.content.res.ColorStateList.valueOf(typedValue.data))
        } else {
            ImageViewCompat.setImageTintList(iconView, null)
            iconView.imageTintList = null
            iconView.colorFilter = null
            iconView.clearColorFilter()
        }
        osView.text = os.replaceFirstChar { it.uppercase() }.ifBlank { "Unknown" }

        // Address
        addressView.text = if (live != null) "${live.ip}:${live.port}" else getString(R.string.offline)

        // Connection status badge
        var state = deviceStates?.get(deviceId) ?: "Disconnected"
        if (state != "Connected" && state != "Connecting") {
            state = "Disconnected"
        }
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
    }

    private fun confirmUnpair(deviceId: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.unpair_confirm_title)
            .setMessage(R.string.unpair_confirm_message)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val intent = Intent(this, SyncService::class.java).apply {
                    action = SyncService.ACTION_UNPAIR_DEVICE
                    putExtra(SyncService.EXTRA_DEVICE_ID, deviceId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                setResult(RESULT_OK)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun getOsIconResource(os: String?): Int {
        if (os.isNullOrBlank()) return R.drawable.ic_desktop
        return when (os.trim().lowercase()) {
            "windows", "win32", "win64" -> R.drawable.ic_windows
            "macos", "darwin", "mac" -> R.drawable.ic_mac
            "linux", "ubuntu" -> R.drawable.ic_ubuntu
            else -> R.drawable.ic_desktop
        }
    }

    private fun handleSelectedFile(uri: android.net.Uri) {
        val intent = Intent(this, SyncService::class.java).apply {
            action = SyncService.ACTION_SEND_FILE
            putExtra(SyncService.EXTRA_DEVICE_ID, deviceId)
            putExtra(SyncService.EXTRA_FILE_URI, uri.toString())
        }
        startService(intent)
    }


}
