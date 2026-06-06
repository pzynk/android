package sols.sync.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import sols.sync.system.SyncService

class SendClipboardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                val intent = Intent(this, SyncService::class.java).apply {
                    action = SyncService.ACTION_SEND_CLIPBOARD
                    putExtra(SyncService.EXTRA_CLIPBOARD_TEXT, text)
                }
                startService(intent)
                Toast.makeText(this, "Clipboard sent to desktop", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
            finish()
            overridePendingTransition(0, 0)
        }
    }
}
