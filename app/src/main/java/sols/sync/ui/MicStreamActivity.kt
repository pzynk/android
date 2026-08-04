package sols.sync.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import sols.sync.R
import sols.sync.SyncApp
import sols.sync.system.SyncService

class MicStreamActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"

        fun start(context: Context, deviceId: String) {
            context.startActivity(
                Intent(context, MicStreamActivity::class.java)
                    .putExtra(EXTRA_DEVICE_ID, deviceId)
            )
        }
    }

    private lateinit var deviceId: String
    private lateinit var toolbar: MaterialToolbar
    private lateinit var ivMicIcon: ImageView
    private lateinit var tvStreamAddress: TextView
    private lateinit var tvStreamStatus: TextView
    private lateinit var btnToggleStream: MaterialButton
    private lateinit var cardPermission: MaterialCardView
    private lateinit var btnGrantPermission: MaterialButton
    private lateinit var switchNoiseSuppressor: MaterialSwitch
    private lateinit var rowSampleRate: View
    private lateinit var tvSampleRateValue: TextView

    private val requestMicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        updateUi()
        if (isGranted) {
            startMicStreamService()
        } else {
            Toast.makeText(this, "Microphone permission is required to stream audio", Toast.LENGTH_SHORT).show()
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_mic_stream)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: ""

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mic_stream_root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        toolbar = findViewById(R.id.toolbar)
        ivMicIcon = findViewById(R.id.iv_mic_icon)
        tvStreamAddress = findViewById(R.id.tv_stream_address)
        tvStreamStatus = findViewById(R.id.tv_stream_status)
        btnToggleStream = findViewById(R.id.btn_toggle_stream)
        cardPermission = findViewById(R.id.card_permission)
        btnGrantPermission = findViewById(R.id.btn_grant_permission)
        switchNoiseSuppressor = findViewById(R.id.switch_noise_suppressor)
        rowSampleRate = findViewById(R.id.row_sample_rate)
        tvSampleRateValue = findViewById(R.id.tv_sample_rate_value)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        switchNoiseSuppressor.isChecked = prefs.getBoolean("mic_noise_suppressor", true)
        switchNoiseSuppressor.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("mic_noise_suppressor", isChecked).apply()
        }

        val currentRate = prefs.getInt("mic_sample_rate", 44100)
        tvSampleRateValue.text = if (currentRate == 48000) "48.0 kHz (Studio)" else "44.1 kHz (Speech)"

        rowSampleRate.setOnClickListener {
            val options = listOf(
                BottomSheetSelect.OptionItem("44.1 kHz (Speech / Calls)", "Standard audio quality"),
                BottomSheetSelect.OptionItem("48.0 kHz (Studio Quality)", "High quality audio")
            )
            val selected = if (prefs.getInt("mic_sample_rate", 44100) == 48000) 1 else 0

            val dialog = BottomSheetSelect.newInstance(
                "Sample Rate",
                options,
                selected,
                object : BottomSheetSelect.OnOptionSelectedListener {
                    override fun onOptionSelected(index: Int) {
                        val rate = if (index == 1) 48000 else 44100
                        prefs.edit().putInt("mic_sample_rate", rate).apply()
                        tvSampleRateValue.text = if (rate == 48000) "48.0 kHz (Studio)" else "44.1 kHz (Speech)"
                    }
                }
            )
            dialog.show(supportFragmentManager, "sample_rate_select")
        }

        btnGrantPermission.setOnClickListener {
            requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        btnToggleStream.setOnClickListener {
            val app = application as SyncApp
            if (app.isMicStreaming) {
                stopMicStreamService()
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startMicStreamService()
                } else {
                    requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        if (intent.getBooleanExtra("auto_request_permission", false)) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            stateReceiver, IntentFilter(SyncService.ACTION_STATE_CHANGED)
        )
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
    }

    private fun startMicStreamService() {
        val intent = Intent(this, SyncService::class.java).apply {
            action = SyncService.ACTION_START_MIC_STREAM
        }
        startService(intent)
    }

    private fun stopMicStreamService() {
        val intent = Intent(this, SyncService::class.java).apply {
            action = SyncService.ACTION_STOP_MIC_STREAM
        }
        startService(intent)
    }

    private fun updateUi() {
        val app = application as SyncApp
        val isStreaming = app.isMicStreaming
        val port = app.micStreamingPort
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        cardPermission.visibility = if (hasPermission) View.GONE else View.VISIBLE

        if (isStreaming) {
            val ip = getWifiIpAddress() ?: "127.0.0.1"
            tvStreamAddress.text = "$ip:$port"
            tvStreamStatus.text = "ACTIVE STREAMING"
            tvStreamStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            btnToggleStream.text = "Stop Microphone Stream"
            ivMicIcon.setImageResource(R.drawable.ic_mic)
        } else {
            tvStreamAddress.text = "Not active"
            tvStreamStatus.text = "INACTIVE"
            tvStreamStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            btnToggleStream.text = "Start Microphone Stream"
            ivMicIcon.setImageResource(R.drawable.ic_mic_off)
        }
    }

    @Suppress("DEPRECATION")
    private fun getWifiIpAddress(): String? {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt == 0) null
            else String.format("%d.%d.%d.%d", ipInt and 0xff, ipInt shr 8 and 0xff, ipInt shr 16 and 0xff, ipInt shr 24 and 0xff)
        } catch (_: Exception) {
            null
        }
    }
}
