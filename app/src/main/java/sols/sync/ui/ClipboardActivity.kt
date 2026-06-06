package sols.sync.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.enableEdgeToEdge
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import sols.sync.R
import sols.sync.system.SyncService

class ClipboardActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DEVICE_ID = "device_id"

        fun start(context: Context, deviceId: String) {
            context.startActivity(
                Intent(context, ClipboardActivity::class.java)
                    .putExtra(EXTRA_DEVICE_ID, deviceId)
            )
        }
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var switchAutoSync: MaterialSwitch
    private lateinit var btnSendClipboard: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_clipboard)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.clipboard_root)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        toolbar = findViewById(R.id.toolbar)
        switchAutoSync = findViewById(R.id.switch_auto_sync)
        btnSendClipboard = findViewById(R.id.btn_send_clipboard)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val isAutoSync = prefs.getBoolean("clipboard_auto_sync", false)
        switchAutoSync.isChecked = isAutoSync

        switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
            val intent = Intent(this, SyncService::class.java).apply {
                action = SyncService.ACTION_SET_AUTO_SYNC
                putExtra(SyncService.EXTRA_AUTO_SYNC, isChecked)
            }
            startService(intent)
        }

        btnSendClipboard.setOnClickListener {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboardManager.hasPrimaryClip()) {
                val text = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
                if (!text.isNullOrBlank()) {
                    val intent = Intent(this, SyncService::class.java).apply {
                        action = SyncService.ACTION_SEND_CLIPBOARD
                        putExtra(SyncService.EXTRA_CLIPBOARD_TEXT, text)
                    }
                    startService(intent)
                    Toast.makeText(this, "Clipboard sent!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }
    }
}