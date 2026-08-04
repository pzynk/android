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
        private const val PERMISSION_REQUEST_CODE = 101

        fun start(context: Context, deviceId: String) {
            context.startActivity(
                Intent(context, ClipboardActivity::class.java)
                    .putExtra(EXTRA_DEVICE_ID, deviceId)
            )
        }
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var switchAutoSync: MaterialSwitch
    private lateinit var switchSyncScreenshots: MaterialSwitch
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
        switchSyncScreenshots = findViewById(R.id.switch_sync_screenshots)
        btnSendClipboard = findViewById(R.id.btn_send_clipboard)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val isAutoSync = prefs.getBoolean("clipboard_auto_sync", false)
        val isSyncScreenshots = prefs.getBoolean("clipboard_sync_screenshots", false)

        switchAutoSync.isChecked = isAutoSync
        switchSyncScreenshots.isChecked = isSyncScreenshots

        switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
            val intent = Intent(this, SyncService::class.java).apply {
                action = SyncService.ACTION_SET_AUTO_SYNC
                putExtra(SyncService.EXTRA_AUTO_SYNC, isChecked)
            }
            startService(intent)
        }

        switchSyncScreenshots.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (hasStoragePermission()) {
                    toggleScreenshotSync(true)
                } else {
                    requestStoragePermission()
                }
            } else {
                toggleScreenshotSync(false)
            }
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

    private fun hasStoragePermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_MEDIA_IMAGES
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        androidx.core.app.ActivityCompat.requestPermissions(
            this,
            arrayOf(permission),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                toggleScreenshotSync(true)
            } else {
                switchSyncScreenshots.isChecked = false
                Toast.makeText(this, "Storage permission is required for Screenshot Sync", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleScreenshotSync(enabled: Boolean) {
        val intent = Intent(this, SyncService::class.java).apply {
            action = SyncService.ACTION_SET_SCREENSHOT_SYNC
            putExtra(SyncService.EXTRA_SCREENSHOT_SYNC, enabled)
        }
        startService(intent)
    }
}