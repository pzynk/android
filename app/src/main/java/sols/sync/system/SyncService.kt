package sols.sync.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import sols.sync.R
import sols.sync.SyncApp
import sols.sync.network.SyncConnector
import sols.sync.ui.MainActivity
import sols.sync.ui.ReceivedFilesActivity

import android.content.ClipboardManager
import android.content.ClipData
import android.os.Handler
import android.os.Looper
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.media.VolumeProvider

class SyncService : Service() {

    data class SendFileTask(val deviceId: String, val uri: android.net.Uri)

    private lateinit var connector: SyncConnector
    private lateinit var notificationManager: NotificationManager
    private lateinit var clipboardManager: ClipboardManager
    private var isAutoSyncEnabled = false
    
    private var activeMediaDeviceId: String? = null
    private var volumeProvider: androidx.media.VolumeProviderCompat? = null

    @Volatile
    private var cancelUpload = false
    private val sendQueue = java.util.concurrent.LinkedBlockingQueue<SendFileTask>()
    private var sendThread: Thread? = null
    
    companion object {
        const val ACTION_STATE_CHANGED = "sols.sync.STATE_CHANGED"
        const val ACTION_MEDIA_STATE_CHANGED = "sols.sync.MEDIA_STATE_CHANGED"
        const val EXTRA_DEVICE_ID = "device_id"
        const val ACTION_SEND_CLIPBOARD = "sols.sync.ACTION_SEND_CLIPBOARD"
        const val ACTION_MEDIA_COMMAND = "sols.sync.ACTION_MEDIA_COMMAND"
        const val ACTION_SET_AUTO_SYNC = "sols.sync.ACTION_SET_AUTO_SYNC"
        const val EXTRA_CLIPBOARD_TEXT = "clipboard_text"
        const val EXTRA_AUTO_SYNC = "auto_sync"
        const val ACTION_SEND_FILE = "sols.sync.ACTION_SEND_FILE"
        const val EXTRA_FILE_URI = "file_uri"
        const val ACTION_UPDATE_VOLUME_SYNC = "sols.sync.ACTION_UPDATE_VOLUME_SYNC"
        const val ACTION_CANCEL_SEND = "sols.sync.ACTION_CANCEL_SEND"
        const val ACTION_CONNECT_DEVICE = "sols.sync.ACTION_CONNECT_DEVICE"
        const val ACTION_UNPAIR_DEVICE = "sols.sync.ACTION_UNPAIR_DEVICE"
        private const val CHANNEL_ID = "sync_service_channel"
        private const val MEDIA_CHANNEL_ID = "sync_media_channel"
        private const val UPLOAD_CHANNEL_ID = "sync_upload_channel"
        private const val NOTIFICATION_ID = 1
        private const val MEDIA_NOTIFICATION_ID = 2
        private const val UPLOAD_NOTIFICATION_ID = 3
 
        // Media command action extras
        private const val MEDIA_CMD_PREV = "sols.sync.MEDIA_CMD_PREV"
        private const val MEDIA_CMD_PLAY_PAUSE = "sols.sync.MEDIA_CMD_PLAY_PAUSE"
        private const val MEDIA_CMD_NEXT = "sols.sync.MEDIA_CMD_NEXT"
    }
 
    // MediaSessionCompat for system media controls (lock screen, notification, headsets)
    private var mediaSessionCompat: MediaSessionCompat? = null
    // MediaSessionCompat for desktop system master volume control
    private var systemVolumeSessionCompat: MediaSessionCompat? = null
 
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        createNotificationChannel()
        createMediaNotificationChannel()
        createUploadNotificationChannel()
 
        connector = SyncConnector(this) { event ->
            handleConnectorEvent(event)
        }
        
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        isAutoSyncEnabled = prefs.getBoolean("clipboard_auto_sync", false)
 
        // MediaSessionCompat for notification MediaStyle + lock screen + headset button support
        mediaSessionCompat = MediaSessionCompat(this, "SyncMediaSessionCompat").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { activeMediaDeviceId?.let { sendMediaCommand(it, "PlayPause") } }
                override fun onPause() { activeMediaDeviceId?.let { sendMediaCommand(it, "PlayPause") } }
                override fun onSkipToNext() { activeMediaDeviceId?.let { sendMediaCommand(it, "Next") } }
                override fun onSkipToPrevious() { activeMediaDeviceId?.let { sendMediaCommand(it, "Prev") } }
                override fun onSeekTo(pos: Long) {
                    activeMediaDeviceId?.let { sendMediaCommand(it, "SetPosition", pos.toDouble() / 1000.0) }
                }
            })
            isActive = true
        }

        // MediaSessionCompat for desktop system master volume control
        systemVolumeSessionCompat = MediaSessionCompat(this, "SyncSystemVolumeSessionCompat").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                // No playback controls, only volume provider is bound
            })
            isActive = false
        }

        clipboardManager.addPrimaryClipChangedListener {
            if (isAutoSyncEnabled) {
                if (clipboardManager.hasPrimaryClip()) {
                    val text = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
                    if (text != null) {
                        val normalizedText = text.replace("\r\n", "\n")
                        val normalizedLast = lastReceivedClipboard?.replace("\r\n", "\n")
                        if (normalizedText != normalizedLast) {
                            lastReceivedClipboard = text
                            connector.sendClipboard(text)
                        }
                    }
                }
            }
        }
    }
    
    private var lastReceivedClipboard: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND_CLIPBOARD -> {
                val text = intent.getStringExtra(EXTRA_CLIPBOARD_TEXT)
                if (text != null) {
                    lastReceivedClipboard = text
                    connector.sendClipboard(text)
                }
                return START_STICKY
            }
            ACTION_CONNECT_DEVICE -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                if (deviceId != null) {
                    val app = application as SyncApp
                    val device = app.discoveredCache[deviceId]
                    if (device != null) {
                        Thread {
                            connector.connect(device)
                        }.start()
                    }
                }
                return START_STICKY
            }
            ACTION_UNPAIR_DEVICE -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                if (deviceId != null) {
                    Thread {
                        connector.unpair(deviceId)
                    }.start()
                }
                return START_STICKY
            }
            ACTION_SET_AUTO_SYNC -> {
                isAutoSyncEnabled = intent.getBooleanExtra(EXTRA_AUTO_SYNC, false)
                getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("clipboard_auto_sync", isAutoSyncEnabled).apply()
                return START_STICKY
            }
            ACTION_MEDIA_COMMAND -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                val command = intent.getStringExtra("command")
                val value = if (intent.hasExtra("value")) intent.getDoubleExtra("value", 0.0) else null
                if (deviceId != null && command != null) {
                    sendMediaCommand(deviceId, command, value)
                }
                return START_STICKY
            }
            ACTION_UPDATE_VOLUME_SYNC -> {
                updateVolumeSyncSessionState()
                return START_STICKY
            }
            ACTION_CANCEL_SEND -> {
                cancelUpload = true
                sendThread?.interrupt()
                sendQueue.clear()
                dismissUploadNotification()
                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(this, "Transfer cancelled", android.widget.Toast.LENGTH_SHORT).show()
                }
                return START_STICKY
            }
            ACTION_SEND_FILE -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                val uriString = intent.getStringExtra(EXTRA_FILE_URI)
                if (deviceId != null && uriString != null) {
                    val uri = android.net.Uri.parse(uriString)
                    sendQueue.put(SendFileTask(deviceId, uri))
                    startSendLoopIfNeeded()
                }
                return START_STICKY
            }
        }
        val notification = createNotification("Searching for devices...")
        
        // Start Foreground Service depending on the Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= 34) // UPSIDE_DOWN_CAKE is API 34
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else
                0
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        connector.start()
        
        // Return START_STICKY to restart the service if the system kills it
        return START_STICKY
    }

    override fun onDestroy() {
        connector.stop()
        mediaSessionCompat?.release()
        systemVolumeSessionCompat?.release()
        volumeProvider = null
        dismissMediaNotification()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We are a started service, not a bound service
    }

    private fun handleConnectorEvent(event: SyncConnector.Event) {
        val app = application as SyncApp
        
        val statusText = when (event) {
            is SyncConnector.Event.DeviceDiscovered -> {
                app.discoveredCache[event.device.deviceId] = event.device
                app.deviceStates[event.device.deviceId] = if (event.paired) "Connecting" else "Discovered"
                if (event.paired) {
                    "Found paired desktop. Connecting..."
                } else {
                    "Found ${event.device.name}. Requesting pairing..."
                }
            }
            is SyncConnector.Event.Connected -> {
                app.deviceStates[event.device.deviceId] = "Connected"
                activeMediaDeviceId = event.device.deviceId
                systemVolumeSessionCompat?.isActive = isVolumeSyncEnabled()
                if (event.pairedBefore) {
                    "Connected to ${event.device.name}"
                } else {
                    "Paired and connected to ${event.device.name}"
                }
            }
            is SyncConnector.Event.PairingRequested -> {
                app.deviceStates[event.device.deviceId] = "Code: ${event.code}"
                "Pair request sent to ${event.device.name}. Code: ${event.code}"
            }
            is SyncConnector.Event.PairRejected -> {
                app.deviceStates[event.device.deviceId] = "Rejected"
                "Pairing rejected by ${event.device.name}: ${event.reason}"
            }
            is SyncConnector.Event.ConnectFailed -> {
                app.deviceStates[event.device.deviceId] = "Disconnected"
                systemVolumeSessionCompat?.isActive = false
                volumeProvider = null
                "Failed to connect to ${event.device.name}: ${event.reason}"
            }
            is SyncConnector.Event.ClipboardUpdate -> {
                if (isAutoSyncEnabled) {
                    Handler(Looper.getMainLooper()).post {
                        lastReceivedClipboard = event.text
                        val clip = ClipData.newPlainText("Desktop Clipboard", event.text)
                        clipboardManager.setPrimaryClip(clip)
                    }
                }
                return // Do not update notification
            }
            is SyncConnector.Event.FileReceived -> {
                val receiveEnabled = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                    .getBoolean("receive_files_enabled", true)
                if (receiveEnabled) {
                    saveReceivedFile(event.filename, event.base64Data, event.sha256)
                    "Received file: ${event.filename}"
                } else {
                    return // File receiving is disabled, ignore
                }
            }
            is SyncConnector.Event.MediaStateUpdate -> {
                app.mediaStates[event.device.deviceId] = event.state
                activeMediaDeviceId = event.device.deviceId
                updateMediaSessionCompat(event.state)
                if (event.state.title.isNotBlank()) {
                    showMediaNotification(event.state)
                } else {
                    dismissMediaNotification()
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(
                    Intent(ACTION_MEDIA_STATE_CHANGED).putExtra(EXTRA_DEVICE_ID, event.device.deviceId)
                )
                return // Do not update notification
            }
            is SyncConnector.Event.SystemVolumeUpdate -> {
                activeMediaDeviceId = event.device.deviceId
                updateSystemVolumeCompat(event.volume)
                return // Do not update status notification
            }
            is SyncConnector.Event.TerminalAccessUpdated -> {
                app.terminalInfos[event.device.deviceId] = SyncApp.TerminalInfo(
                    enabled = event.enabled,
                    port = event.port,
                    username = event.username,
                    password = event.password
                )
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_STATE_CHANGED))
                return // Do not update notification
            }
        }
        
        updateNotification(statusText)
        
        // Broadcast to MainActivity to refresh UI
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_STATE_CHANGED))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sync Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps connection alive to desktop"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createMediaNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MEDIA_CHANNEL_ID,
                "Media Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows media playback controls for connected desktop"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sendClipboardIntent = Intent(this, sols.sync.ui.SendClipboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val sendClipboardPendingIntent = PendingIntent.getActivity(
            this, 1, sendClipboardIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sync Active")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher) // Use app icon as placeholder
            .setContentIntent(pendingIntent)
            .addAction(0, "Send Clipboard", sendClipboardPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification(content: String) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun saveReceivedFile(filename: String, base64Data: String, expectedSha256: String) {
        Thread {
            try {
                val data = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                
                // Verify hash
                val computedSha256 = calculateSha256(data)
                if (!computedSha256.equals(expectedSha256, ignoreCase = true)) {
                    android.util.Log.w("SyncService", "SHA-256 mismatch for $filename: expected $expectedSha256, got $computedSha256")
                    Handler(Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(this, "File verification failed: $filename", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                
                var file = java.io.File(downloadsDir, filename)
                var count = 1
                val nameWithoutExt = file.nameWithoutExtension
                val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
                while (file.exists()) {
                    file = java.io.File(downloadsDir, "${nameWithoutExt}_${count}${ext}")
                    count++
                }
                
                file.writeBytes(data)
                ReceivedFilesActivity.addReceivedFile(this, file.absolutePath)
                showFileReceivedNotification(file)
            } catch (e: Exception) {
                android.util.Log.e("SyncService", "Failed to save file", e)
            }
        }.start()
    }
    
    private fun showFileReceivedNotification(file: java.io.File) {
        val channelId = "sync_file_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "File Transfers",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
        
        val intent = Intent(this, ReceivedFilesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, file.name.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("File Received")
            .setContentText("Saved to Downloads: ${file.name}")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        notificationManager.notify(file.name.hashCode(), notification)
    }

    /** Expose media control to UI */
    fun sendMediaCommand(deviceId: String, command: String, value: Double? = null) {
        connector.sendMediaCommand(deviceId, command, value)
    }



    /** Update the MediaSessionCompat used by the MediaStyle notification. */
    private fun updateMediaSessionCompat(state: sols.sync.network.protocol.ServerMessage.MediaState) {
        val session = mediaSessionCompat ?: return

        val playbackStateCompat = PlaybackStateCompat.Builder()
            .setState(
                if (state.isPlaying) PlaybackStateCompat.STATE_PLAYING
                else PlaybackStateCompat.STATE_PAUSED,
                state.positionUs / 1000,
                1.0f
            )
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .build()
        session.setPlaybackState(playbackStateCompat)

        val metadataCompat = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, state.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.lengthUs / 1000)
            .build()
        session.setMetadata(metadataCompat)

        session.isActive = true
    }

    private fun updateSystemVolumeCompat(volume: Double) {
        if (!isVolumeSyncEnabled()) {
            systemVolumeSessionCompat?.isActive = false
            volumeProvider = null
            return
        }
        val session = systemVolumeSessionCompat ?: return
        val deviceId = activeMediaDeviceId
        if (deviceId != null) {
            val targetVolume = (volume * 100).toInt()
            var provider = volumeProvider
            if (provider == null) {
                provider = object : androidx.media.VolumeProviderCompat(
                    androidx.media.VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE,
                    100,
                    targetVolume
                ) {
                    override fun onSetVolumeTo(vol: Int) {
                        sendMediaCommand(deviceId, "SetSystemVolume", vol / 100.0)
                        setCurrentVolume(vol)
                    }
                    override fun onAdjustVolume(direction: Int) {
                        when (direction) {
                            1 -> sendMediaCommand(deviceId, "SystemVolumeUp")
                            -1 -> sendMediaCommand(deviceId, "SystemVolumeDown")
                        }
                    }
                }
                volumeProvider = provider
                session.setPlaybackToRemote(provider)
            } else {
                if (provider.currentVolume != targetVolume) {
                    provider.setCurrentVolume(targetVolume)
                }
            }
        }
    }

    /**
     * Posts a [MediaStyle] notification showing the current track info and transport controls.
     * This is what surfaces the session on the lock screen, notification shade, and
     * Bluetooth/headset controls.
     */
    private fun showMediaNotification(state: sols.sync.network.protocol.ServerMessage.MediaState) {
        val session = mediaSessionCompat ?: return
        val deviceId = activeMediaDeviceId ?: return

        fun mediaPendingIntent(command: String, requestCode: Int): PendingIntent {
            val intent = Intent(this, SyncService::class.java).apply {
                action = ACTION_MEDIA_COMMAND
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra("command", command)
            }
            return PendingIntent.getService(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val openIntent = Intent(this, sols.sync.ui.MediaControlActivity::class.java).apply {
            putExtra("device_id", deviceId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (state.isPlaying)
            android.R.drawable.ic_media_pause
        else
            android.R.drawable.ic_media_play
        val playPauseLabel = if (state.isPlaying) "Pause" else "Play"

        val artistLine = listOf(state.artist, state.album)
            .filter { it.isNotBlank() }.joinToString(" • ")
            .ifBlank { state.player.ifBlank { "Playing on desktop" } }

        val notification = NotificationCompat.Builder(this, MEDIA_CHANNEL_ID)
            .setContentTitle(state.title)
            .setContentText(artistLine)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(state.isPlaying)
            .setSilent(true)
            // Previous
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                mediaPendingIntent("Prev", 10)
            )
            // Play / Pause
            .addAction(
                playPauseIcon,
                playPauseLabel,
                mediaPendingIntent("PlayPause", 11)
            )
            // Next
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                mediaPendingIntent("Next", 12)
            )
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    // Show all 3 actions in compact view (indices 0, 1, 2)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(false)
            )
            .build()

        notificationManager.notify(MEDIA_NOTIFICATION_ID, notification)
    }

    /** Removes the media notification if present. */
    private fun dismissMediaNotification() {
        notificationManager.cancel(MEDIA_NOTIFICATION_ID)
        mediaSessionCompat?.isActive = false
    }

    private fun isVolumeSyncEnabled(): Boolean {
        return getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
            .getBoolean("volume_sync_enabled", true)
    }

    private fun updateVolumeSyncSessionState() {
        val app = application as SyncApp
        val deviceId = activeMediaDeviceId
        val isConnected = deviceId != null && app.deviceStates[deviceId] == "Connected"
        val enabled = isVolumeSyncEnabled() && isConnected
        systemVolumeSessionCompat?.isActive = enabled
        if (!enabled) {
            volumeProvider = null
        }
    }

    private fun calculateSha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun calculateSha256OfUri(uri: android.net.Uri): Pair<String, Long> {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val inputStream = contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException()
        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalBytes: Long = 0
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            if (cancelUpload || Thread.currentThread().isInterrupted) {
                inputStream.close()
                throw InterruptedException("SHA-256 calculation cancelled")
            }
            digest.update(buffer, 0, bytesRead)
            totalBytes += bytesRead
        }
        inputStream.close()
        val hashBytes = digest.digest()
        val sha256 = hashBytes.joinToString("") { "%02x".format(it) }
        return Pair(sha256, totalBytes)
    }

    @Synchronized
    private fun startSendLoopIfNeeded() {
        if (sendThread == null || !sendThread!!.isAlive) {
            cancelUpload = false
            sendThread = Thread {
                try {
                    while (!sendQueue.isEmpty() && !cancelUpload) {
                        val task = sendQueue.poll() ?: break
                        sendFileToDeviceSynchronous(task.deviceId, task.uri)
                    }
                } catch (e: InterruptedException) {
                    // Thread interrupted, stop sending
                } finally {
                    dismissUploadNotification()
                    sendQueue.clear()
                    synchronized(this) {
                        sendThread = null
                    }
                }
            }
            sendThread!!.start()
        }
    }

    private fun sendFileToDeviceSynchronous(deviceId: String, uri: android.net.Uri) {
        val filename = getFileName(uri) ?: "file"
        try {
            showUploadProgressNotification(filename, 0, true)
            val (sha256, fileSize) = calculateSha256OfUri(uri)
            
            // Get memory info
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val availableMemory = maxMemory - usedMemory
            
            // Safety margin of 50MB to keep app operations running smoothly
            val safetyMargin = 50 * 1024 * 1024L
            // Base64 string + original bytes + JSON encoding overhead is estimated at ~2.5x file size
            val estimatedRequiredMemory = (fileSize * 2.5).toLong()
            
            val success: Boolean
            if (estimatedRequiredMemory < (availableMemory - safetyMargin)) {
                // Safe to read in-memory
                val inputStream = contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null) {
                    val base64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    success = if (cancelUpload || Thread.currentThread().isInterrupted) false else connector.sendFile(deviceId, filename, base64Data, sha256)
                } else {
                    throw java.io.IOException("Could not read file data")
                }
            } else {
                // Not enough free memory space (keeping 50MB margin), so stream it
                var lastPercent = -1
                success = connector.sendFileStreaming(
                    deviceId, filename, uri, contentResolver, sha256, fileSize,
                    onProgress = { sent ->
                        val pct = ((sent * 100) / fileSize).toInt()
                        if (pct != lastPercent) {
                            lastPercent = pct
                            showUploadProgressNotification(filename, pct, false)
                        }
                    },
                    cancelCheck = { cancelUpload || Thread.currentThread().isInterrupted }
                )
            }
            
            Handler(Looper.getMainLooper()).post {
                if (cancelUpload) {
                    android.widget.Toast.makeText(this, "Cancelled: $filename", android.widget.Toast.LENGTH_SHORT).show()
                } else if (success) {
                    android.widget.Toast.makeText(this, "Sent: $filename", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "Failed to send: $filename", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SyncService", "Failed to send file", e)
            Handler(Looper.getMainLooper()).post {
                if (cancelUpload || e is InterruptedException || e.cause is InterruptedException) {
                    android.widget.Toast.makeText(this, "Cancelled: $filename", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "Failed to send: $filename", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun createUploadNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPLOAD_CHANNEL_ID,
                "File Uploads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of sending files to desktop"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showUploadProgressNotification(filename: String, progressPercent: Int, isIndeterminate: Boolean) {
        val cancelIntent = Intent(this, SyncService::class.java).apply {
            action = ACTION_CANCEL_SEND
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val queueSize = sendQueue.size
        val contentText = if (queueSize > 0) {
            "Uploading... ($queueSize more in queue)"
        } else {
            "Uploading..."
        }

        val notification = NotificationCompat.Builder(this, UPLOAD_CHANNEL_ID)
            .setContentTitle(filename)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(100, progressPercent, isIndeterminate)
            .addAction(0, "Cancel", cancelPendingIntent)
            .setOnlyAlertOnce(true)
            .build()

        notificationManager.notify(UPLOAD_NOTIFICATION_ID, notification)
    }

    private fun dismissUploadNotification() {
        notificationManager.cancel(UPLOAD_NOTIFICATION_ID)
    }

    private fun getFileName(uri: android.net.Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result
    }
}
