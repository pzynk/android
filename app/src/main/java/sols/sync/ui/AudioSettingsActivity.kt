package sols.sync.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.enableEdgeToEdge
import com.google.android.material.appbar.MaterialToolbar
import sols.sync.R
import sols.sync.system.SyncService

class AudioSettingsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"

        fun start(context: Context, deviceId: String) {
            context.startActivity(
                Intent(context, AudioSettingsActivity::class.java)
                    .putExtra(EXTRA_DEVICE_ID, deviceId)
            )
        }
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var rbLow: com.google.android.material.radiobutton.MaterialRadioButton
    private lateinit var rbNormal: com.google.android.material.radiobutton.MaterialRadioButton
    private lateinit var rbHigh: com.google.android.material.radiobutton.MaterialRadioButton
    private lateinit var deviceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_audio_settings)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return finish()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.audio_settings_root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        toolbar = findViewById(R.id.toolbar)
        rbLow = findViewById(R.id.rb_latency_low)
        rbNormal = findViewById(R.id.rb_latency_normal)
        rbHigh = findViewById(R.id.rb_latency_high)

        val rowLow = findViewById<android.view.View>(R.id.row_latency_low)
        val rowNormal = findViewById<android.view.View>(R.id.row_latency_normal)
        val rowHigh = findViewById<android.view.View>(R.id.row_latency_high)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val currentProfile = prefs.getInt("audio_latency_$deviceId", 2)
        updateRadioStates(currentProfile)

        val onSelectProfile: (Int) -> Unit = { profile ->
            updateRadioStates(profile)
            prefs.edit().putInt("audio_latency_$deviceId", profile).apply()

            // Instantly restart local AudioTrack stream with new latency profile
            val intent = Intent(this, SyncService::class.java).apply {
                action = SyncService.ACTION_RESTART_AUDIO_STREAM
                putExtra(SyncService.EXTRA_DEVICE_ID, deviceId)
            }
            startService(intent)
        }

        rowLow.setOnClickListener { onSelectProfile(1) }
        rbLow.setOnClickListener { onSelectProfile(1) }

        rowNormal.setOnClickListener { onSelectProfile(2) }
        rbNormal.setOnClickListener { onSelectProfile(2) }

        rowHigh.setOnClickListener { onSelectProfile(4) }
        rbHigh.setOnClickListener { onSelectProfile(4) }

        val switchNoise = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_mic_noise_suppressor)
        val rbSample44 = findViewById<com.google.android.material.radiobutton.MaterialRadioButton>(R.id.rb_sample_44)
        val rbSample48 = findViewById<com.google.android.material.radiobutton.MaterialRadioButton>(R.id.rb_sample_48)

        if (switchNoise != null) {
            switchNoise.isChecked = prefs.getBoolean("mic_noise_suppressor", true)
            switchNoise.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("mic_noise_suppressor", isChecked).apply()
            }
        }

        if (rbSample44 != null && rbSample48 != null) {
            val currentRate = prefs.getInt("mic_sample_rate", 44100)
            rbSample44.isChecked = (currentRate == 44100)
            rbSample48.isChecked = (currentRate == 48000)

            rbSample44.setOnClickListener {
                rbSample44.isChecked = true
                rbSample48.isChecked = false
                prefs.edit().putInt("mic_sample_rate", 44100).apply()
            }
            rbSample48.setOnClickListener {
                rbSample44.isChecked = false
                rbSample48.isChecked = true
                prefs.edit().putInt("mic_sample_rate", 48000).apply()
            }
        }
    }

    private fun updateRadioStates(selectedProfile: Int) {
        rbLow.isChecked = (selectedProfile == 1)
        rbNormal.isChecked = (selectedProfile == 2 || selectedProfile != 1 && selectedProfile != 4)
        rbHigh.isChecked = (selectedProfile == 4)
    }
}
