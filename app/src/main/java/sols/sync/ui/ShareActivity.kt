package sols.sync.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import sols.sync.SyncApp
import sols.sync.system.SyncService
import sols.sync.system.TrustedPeersStore

class ShareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        if (intent == null || (Intent.ACTION_SEND != intent.action && Intent.ACTION_SEND_MULTIPLE != intent.action)) {
            finish()
            return
        }

        val uris = getSharedUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "No files shared", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val store = TrustedPeersStore(applicationContext)
        val peers = store.all()

        if (peers.isEmpty()) {
            Toast.makeText(this, "No paired devices found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val deviceStates = (applicationContext as SyncApp).deviceStates
        
        // Build items list
        val itemTexts = peers.map { peer ->
            val state = deviceStates[peer.deviceId] ?: "Disconnected"
            val displayState = if (state == "Connected") "Online" else "Offline"
            "${peer.name} ($displayState)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Send to device...")
            .setItems(itemTexts) { _, which ->
                val selectedPeer = peers[which]
                val state = deviceStates[selectedPeer.deviceId] ?: "Disconnected"
                if (state == "Connected") {
                    for (uri in uris) {
                        val sendIntent = Intent(this, SyncService::class.java).apply {
                            action = SyncService.ACTION_SEND_FILE
                            putExtra(SyncService.EXTRA_DEVICE_ID, selectedPeer.deviceId)
                            putExtra(SyncService.EXTRA_FILE_URI, uri.toString())
                        }
                        startService(sendIntent)
                    }
                    Toast.makeText(this, "Sending file(s) to ${selectedPeer.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "${selectedPeer.name} is offline", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .setOnDismissListener {
                if (!isFinishing) finish()
            }
            .show()
    }

    private fun getSharedUris(intent: Intent): List<android.net.Uri> {
        val uris = mutableListOf<android.net.Uri>()
        if (Intent.ACTION_SEND == intent.action) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) {
                uris.add(uri)
            }
        } else if (Intent.ACTION_SEND_MULTIPLE == intent.action) {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (list != null) {
                uris.addAll(list)
            }
        }
        return uris
    }
}
