package sols.sync.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import sols.sync.R
import sols.sync.SyncApp
import sols.sync.network.protocol.ServerMessage
import sols.sync.system.SyncService

class MediaControlActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"

        fun start(context: Context, deviceId: String) {
            context.startActivity(
                Intent(context, MediaControlActivity::class.java)
                    .putExtra(EXTRA_DEVICE_ID, deviceId)
            )
        }
    }

    private lateinit var deviceId: String
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvLength: TextView
    private lateinit var chipPlayer: Chip
    private lateinit var sliderProgress: Slider
    private lateinit var sliderVolume: Slider
    private lateinit var fabPlayPause: FloatingActionButton

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getStringExtra(SyncService.EXTRA_DEVICE_ID)
            if (id == deviceId) {
                updateUiFromState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_media_control)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return finish()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.media_control_root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        tvTitle = findViewById(R.id.tv_title)
        tvArtist = findViewById(R.id.tv_artist)
        tvPosition = findViewById(R.id.tv_position)
        tvLength = findViewById(R.id.tv_length)
        chipPlayer = findViewById(R.id.chip_player)
        sliderProgress = findViewById(R.id.slider_progress)
        sliderVolume = findViewById(R.id.slider_volume)
        fabPlayPause = findViewById(R.id.fab_play_pause)

        findViewById<FloatingActionButton>(R.id.fab_prev).setOnClickListener {
            sendCommand("Prev")
        }
        findViewById<FloatingActionButton>(R.id.fab_next).setOnClickListener {
            sendCommand("Next")
        }
        fabPlayPause.setOnClickListener {
            sendCommand("PlayPause")
        }

        sliderVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                sendCommand("SetSystemVolume", value.toDouble())
            }
        }

        sliderProgress.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                sendCommand("SetPosition", value.toDouble())
            }
        }

        updateUiFromState()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            mediaReceiver,
            IntentFilter(SyncService.ACTION_MEDIA_STATE_CHANGED)
        )
        updateUiFromState()
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mediaReceiver)
        super.onStop()
    }

    private fun updateUiFromState() {
        val app = applicationContext as SyncApp
        val state = app.mediaStates[deviceId] ?: return

        tvTitle.text = state.title.takeIf { it.isNotBlank() } ?: "Unknown Title"
        val artistAndAlbum = listOf(state.artist, state.album).filter { it.isNotBlank() }.joinToString(" • ")
        tvArtist.text = artistAndAlbum.takeIf { it.isNotBlank() } ?: "Unknown Artist"

        chipPlayer.text = state.player.takeIf { it.isNotBlank() } ?: "Active"

        if (state.isPlaying) {
            fabPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            fabPlayPause.setImageResource(android.R.drawable.ic_media_play)
        }

        // Avoid updating volume slider if user is dragging it
        if (!sliderVolume.hasFocus()) {
            sliderVolume.value = state.volume.toFloat().coerceIn(0f, 1f)
        }

        if (!sliderProgress.hasFocus()) {
            val lenSecs = state.lengthUs / 1_000_000
            val posSecs = state.positionUs / 1_000_000

            tvLength.text = formatTime(lenSecs)
            tvPosition.text = formatTime(posSecs)

            if (lenSecs > 0) {
                sliderProgress.valueTo = lenSecs.toFloat()
                sliderProgress.value = posSecs.toFloat().coerceIn(0f, lenSecs.toFloat())
            } else {
                sliderProgress.valueTo = 100f
                sliderProgress.value = 0f
            }
        }
    }

    private fun formatTime(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%d:%02d", m, s)
    }

    private fun sendCommand(command: String, value: Double? = null) {
        val intent = Intent(this, SyncService::class.java)
        startService(intent) // ensures service is running
        
        // Let's find SyncService instance and call it directly for now (or we could use binding)
        // Since SyncService isn't bound, the easiest way to send commands is to broadcast it
        // from the UI back to SyncService, or add an ACTION to intent.
        
        val cmdIntent = Intent(this, SyncService::class.java).apply {
            action = "sols.sync.ACTION_MEDIA_COMMAND"
            putExtra("device_id", deviceId)
            putExtra("command", command)
            if (value != null) {
                putExtra("value", value)
            }
        }
        startService(cmdIntent)
    }
}
