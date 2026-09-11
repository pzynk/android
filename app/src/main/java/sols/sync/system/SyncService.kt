package sols.sync.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
    private var lastKnownDesktopVolume: Double? = null

    private var cameraServer: CameraStreamServer? = null
    private var micServer: MicStreamServer? = null
    private var audioTrack: android.media.AudioTrack? = null
    private var audioStreamThread: Thread? = null
    @Volatile private var isAudioStreaming = false
    private var lastAudioIp: String? = null
    private var lastAudioPort: Int? = null
    private var lastAudioDeviceId: String? = null
    private var screenshotObserver: ScreenshotObserver? = null
    private var isScreenshotSyncEnabled = false

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
        const val ACTION_SET_SCREENSHOT_SYNC = "sols.sync.ACTION_SET_SCREENSHOT_SYNC"
        const val EXTRA_CLIPBOARD_TEXT = "clipboard_text"
        const val EXTRA_AUTO_SYNC = "auto_sync"
        const val EXTRA_SCREENSHOT_SYNC = "screenshot_sync"
        const val ACTION_SEND_FILE = "sols.sync.ACTION_SEND_FILE"
        const val EXTRA_FILE_URI = "file_uri"
        const val ACTION_UPDATE_VOLUME_SYNC = "sols.sync.ACTION_UPDATE_VOLUME_SYNC"
        const val ACTION_CANCEL_SEND = "sols.sync.ACTION_CANCEL_SEND"
        const val ACTION_CONNECT_DEVICE = "sols.sync.ACTION_CONNECT_DEVICE"
        const val ACTION_UNPAIR_DEVICE = "sols.sync.ACTION_UNPAIR_DEVICE"
        const val ACTION_START_CAMERA_STREAM = "sols.sync.ACTION_START_CAMERA_STREAM"
        const val ACTION_STOP_CAMERA_STREAM = "sols.sync.ACTION_STOP_CAMERA_STREAM"
        const val ACTION_START_MIC_STREAM = "sols.sync.ACTION_START_MIC_STREAM"
        const val ACTION_STOP_MIC_STREAM = "sols.sync.ACTION_STOP_MIC_STREAM"
        const val ACTION_START_AUDIO_STREAM = "sols.sync.ACTION_START_AUDIO_STREAM"
        const val ACTION_STOP_AUDIO_STREAM = "sols.sync.ACTION_STOP_AUDIO_STREAM"
        const val ACTION_RESTART_AUDIO_STREAM = "sols.sync.ACTION_RESTART_AUDIO_STREAM"
        const val ACTION_AUDIO_STREAM_ERROR = "sols.sync.ACTION_AUDIO_STREAM_ERROR"
        const val EXTRA_ERROR_MESSAGE = "error_message"
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
 
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> connector.pauseDiscovery()
                Intent.ACTION_SCREEN_ON -> connector.resumeDiscovery()
            }
        }
    }

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
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, filter)
        
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        isAutoSyncEnabled = prefs.getBoolean("clipboard_auto_sync", false)
        isScreenshotSyncEnabled = prefs.getBoolean("clipboard_sync_screenshots", false)
        if (isScreenshotSyncEnabled) {
            startScreenshotObserver()
        }
 
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
            ACTION_START_CAMERA_STREAM -> {
                startCameraStream()
                return START_STICKY
            }
            ACTION_STOP_CAMERA_STREAM -> {
                stopCameraStream()
                return START_STICKY
            }
            ACTION_START_MIC_STREAM -> {
                startMicStream()
                return START_STICKY
            }
            ACTION_STOP_MIC_STREAM -> {
                stopMicStream()
                return START_STICKY
            }
            ACTION_START_AUDIO_STREAM -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                if (deviceId != null) {
                    connector.sendAudioStreamRequest(deviceId, true)
                }
                return START_STICKY
            }
            ACTION_STOP_AUDIO_STREAM -> {
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
                if (deviceId != null) {
                    connector.sendAudioStreamRequest(deviceId, false)
                    stopAudioStream()
                }
                return START_STICKY
            }
            ACTION_RESTART_AUDIO_STREAM -> {
                val ip = lastAudioIp
                val port = lastAudioPort
                val deviceId = lastAudioDeviceId
                if (isAudioStreaming && ip != null && port != null && deviceId != null) {
                    startAudioStream(ip, port, deviceId)
                }
                return START_STICKY
            }
            ACTION_SET_AUTO_SYNC -> {
                isAutoSyncEnabled = intent.getBooleanExtra(EXTRA_AUTO_SYNC, false)
                getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("clipboard_auto_sync", isAutoSyncEnabled).apply()
                return START_STICKY
            }
            ACTION_SET_SCREENSHOT_SYNC -> {
                isScreenshotSyncEnabled = intent.getBooleanExtra(EXTRA_SCREENSHOT_SYNC, false)
                getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("clipboard_sync_screenshots", isScreenshotSyncEnabled).apply()
                if (isScreenshotSyncEnabled) {
                    startScreenshotObserver()
                } else {
                    stopScreenshotObserver()
                }
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
        stopScreenshotObserver()
        stopCameraStream()
        stopAudioStream()
        connector.stop()
        mediaSessionCompat?.release()
        systemVolumeSessionCompat?.release()
        volumeProvider = null
        dismissMediaNotification()
        try { unregisterReceiver(screenStateReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startScreenshotObserver() {
        if (screenshotObserver == null) {
            screenshotObserver = ScreenshotObserver(this) { base64Data ->
                if (isScreenshotSyncEnabled) {
                    connector.sendClipboardImage(base64Data)
                }
            }
            try {
                contentResolver.registerContentObserver(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    screenshotObserver!!
                )
            } catch (e: Exception) {
                android.util.Log.e("SyncService", "Failed to register screenshot observer", e)
            }
        }
    }

    private fun stopScreenshotObserver() {
        screenshotObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
            screenshotObserver = null
        }
    }

    private fun startCameraStream() {
        if (cameraServer != null) return
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val isFront = prefs.getBoolean("camera_facing_front", false)
        val resolutionStr = prefs.getString("camera_resolution", "640x480") ?: "640x480"
        val parts = resolutionStr.split("x")
        val width = parts.getOrNull(0)?.toIntOrNull() ?: 640
        val height = parts.getOrNull(1)?.toIntOrNull() ?: 480
        val fps = prefs.getInt("camera_fps", 30)
        val rotation = prefs.getInt("camera_rotation", 0)
        val useAdb = prefs.getString("camera_connection_mode", "wifi") == "adb"

        val server = CameraStreamServer(
            context = this,
            isFrontCamera = isFront,
            width = width,
            height = height,
            targetFps = fps,
            rotation = rotation,
            onStarted = { port ->
                val app = application as SyncApp
                app.isCameraStreaming = true
                app.cameraStreamingPort = port
                connector.sendCameraStreamStarted(port, useAdb)
                val statusMsg = if (useAdb) {
                    "Camera streaming active via ADB on port $port"
                } else {
                    "Camera streaming active on port $port"
                }
                updateNotification(statusMsg)
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_STATE_CHANGED))
            },
            onStopped = {
                stopCameraStream()
            }
        )
        cameraServer = server
        server.start()
    }


    private fun stopCameraStream() {
        val app = application as? SyncApp
        val server = cameraServer
        if (server == null && app?.isCameraStreaming != true) return

        cameraServer = null
        if (app != null) {
            app.isCameraStreaming = false
            app.cameraStreamingPort = 0
        }

        try {
            server?.stop()
        } catch (e: Exception) {
            android.util.Log.e("SyncService", "Error stopping camera server", e)
        }

        connector.sendCameraStreamStopped()
        updateNotification("Connected to desktop")
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_STATE_CHANGED))
    }

    private fun startMicStream() {
        if (micServer != null) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(this, sols.sync.ui.MicStreamActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("auto_request_permission", true)
            }
            startActivity(intent)
            connector.sendMicStreamStopped()
            return
        }
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val sampleRate = prefs.getInt("mic_sample_rate", 44100)
        val channels = prefs.getInt("mic_channels", 1)
        val noiseSuppressor = prefs.getBoolean("mic_noise_suppressor", true)
        val useAdb = prefs.getString("mic_connection_mode", "wifi") == "adb"

        val server = MicStreamServer(
            sampleRate = sampleRate,
            channels = channels,
            enableNoiseSuppressor = noiseSuppressor,
            onStarted = { port ->
                val app = application as SyncApp
                app.isMicStreaming = true
                app.micStreamingPort = port
                connector.sendMicStreamStarted(port, sampleRate, channels, useAdb)
                val statusMsg = if (useAdb) {
                    "Microphone streaming active via ADB on port $port"
                } else {
                    "Microphone streaming active on port $port"
                }
                updateNotification(statusMsg)
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_STATE_CHANGED))
            },
            onStopped = {
                stopMicStream()
            }
        )
        micServer = server
        server.start()
    }

    private fun stopMicStream() {
        val app = application as? SyncApp
        val server = micServer
        if (server == null && app?.isMicStreaming != true) return

        micServer = null
        if (app != null) {
            app.isMicStreaming = false
            app.micStreamingPort = 0
        }

        try {
            server?.stop()
        } catch (e: Exception) {
            android.util.Log.e("SyncService", "Error stopping mic server", e)
        }

        connector.sendMicStreamStopped()
        updateNotification("Connected to desktop")
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_STATE_CHANGED))
    }

    private fun notifyAudioStreamFailure(deviceId: String, errorMessage: String) {
        val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        val wasEnabled = prefs.getBoolean("audio_stream_enabled_$deviceId", false)
        prefs.edit().putBoolean("audio_stream_enabled_$deviceId", false).apply()
        if (!wasEnabled) {
            return
        }
        
        Handler(Looper.getMainLooper()).post {
            val intent = Intent(ACTION_AUDIO_STREAM_ERROR).apply {
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    private fun startAudioStream(ip: String, port: Int, deviceId: String) {
        stopAudioStream()
        isAudioStreaming = true
        lastAudioIp = ip
        lastAudioPort = port
        lastAudioDeviceId = deviceId
        
        audioStreamThread = Thread {
            var urlConnection: java.net.HttpURLConnection? = null
            var inputStream: java.io.InputStream? = null
            try {
                android.util.Log.i("SyncService", "Starting audio stream from http://$ip:$port/")
                val url = java.net.URL("http://$ip:$port/")
                urlConnection = url.openConnection() as java.net.HttpURLConnection
                urlConnection.connectTimeout = 5000
                urlConnection.readTimeout = 5000
                
                val responseCode = urlConnection.responseCode
                if (responseCode != 200) {
                    android.util.Log.e("SyncService", "Audio stream failed with HTTP response code: $responseCode")
                    notifyAudioStreamFailure(deviceId, "Desktop failed to start audio stream (HTTP $responseCode). Check desktop audio device.")
                    return@Thread
                }
                
                inputStream = urlConnection.inputStream
                android.util.Log.i("SyncService", "Connected to audio stream, reading WAV header...")
                
                // Read and parse the 44-byte WAV header
                val header = ByteArray(44)
                var bytesRead = 0
                while (bytesRead < 44 && isAudioStreaming) {
                    val read = inputStream.read(header, bytesRead, 44 - bytesRead)
                    if (read == -1) {
                        android.util.Log.e("SyncService", "End of stream reached while reading WAV header")
                        notifyAudioStreamFailure(deviceId, "Audio stream ended unexpectedly by desktop.")
                        return@Thread
                    }
                    bytesRead += read
                }
                
                if (!isAudioStreaming) return@Thread
                android.util.Log.i("SyncService", "WAV header read. Starting AudioTrack...")

                val channels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
                val sampleRate = (header[24].toInt() and 0xFF) or
                        ((header[25].toInt() and 0xFF) shl 8) or
                        ((header[26].toInt() and 0xFF) shl 16) or
                        ((header[27].toInt() and 0xFF) shl 24)
                val validSampleRate = if (sampleRate in 8000..192000) sampleRate else 44100
                val channelMask = if (channels == 1) android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO

                android.util.Log.i("SyncService", "Audio stream format: rate=$validSampleRate, channels=$channels")

                val minBufferSize = android.media.AudioTrack.getMinBufferSize(
                    validSampleRate,
                    channelMask,
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                )

                val trackBuilder = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_GAME)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(validSampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    trackBuilder.setPerformanceMode(android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                    
                val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                val latencyProfile = prefs.getInt("audio_latency_$deviceId", 2)
                val bufferMultiplier = when (latencyProfile) {
                    1 -> 1
                    4 -> 4
                    else -> 2
                }
                
                val track = trackBuilder
                    .setBufferSizeInBytes(minBufferSize * bufferMultiplier)
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build()

                audioTrack = track
                track.play()
                
                // Only update notification once we actually start playing audio
                Handler(Looper.getMainLooper()).post {
                    updateNotification("Streaming audio from desktop")
                }

                val buffer = ByteArray(512)
                var totalBytesRead = 0L
                android.util.Log.i("SyncService", "Audio streaming playback loop started")
                while (isAudioStreaming && track.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                    val read = inputStream.read(buffer)
                    if (read == -1 || !isAudioStreaming) {
                        android.util.Log.i("SyncService", "Audio stream ended by server. Total bytes read: $totalBytesRead")
                        if (totalBytesRead == 0L && isAudioStreaming) {
                            notifyAudioStreamFailure(deviceId, "Audio stream closed by desktop before data was received.")
                        }
                        break
                    }
                    totalBytesRead += read
                    if (isAudioStreaming && track.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                        val written = track.write(buffer, 0, read)
                        if (written < 0) {
                            android.util.Log.w("SyncService", "AudioTrack write returned error code $written")
                            break
                        }
                    }
                }

                android.util.Log.i("SyncService", "Audio stream loop finished")

            } catch (e: Exception) {
                if (isAudioStreaming) {
                    android.util.Log.e("SyncService", "Audio stream encountered an error", e)
                    notifyAudioStreamFailure(deviceId, "Audio stream connection error: ${e.localizedMessage ?: "Connection failed"}")
                }
            } finally {
                val trackToRelease = audioTrack
                audioTrack = null
                try {
                    if (trackToRelease?.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                        trackToRelease.stop()
                    }
                    trackToRelease?.release()
                } catch (_: Exception) {}
                try { inputStream?.close() } catch (_: Exception) {}
                try { urlConnection?.disconnect() } catch (_: Exception) {}
                Handler(Looper.getMainLooper()).post { 
                    if (isAudioStreaming) {
                        stopAudioStream() 
                    }
                }
            }
        }
        audioStreamThread?.start()
    }

    private fun stopAudioStream() {
        if (!isAudioStreaming) return
        android.util.Log.i("SyncService", "Stopping audio stream")
        isAudioStreaming = false
        val trackToRelease = audioTrack
        audioTrack = null
        audioStreamThread?.interrupt()
        audioStreamThread = null
        try {
            if (trackToRelease?.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                trackToRelease.stop()
            }
            trackToRelease?.release()
        } catch (_: Exception) {}
        
        Handler(Looper.getMainLooper()).post {
            updateNotification("Listening for devices...")
        }
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
                // Bind VolumeProviderCompat immediately so hardware volume keys work
                // right away, before the desktop sends its first SystemVolumeUpdate.
                updateSystemVolumeCompat(lastKnownDesktopVolume ?: 0.5)
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
                stopCameraStream()
                stopMicStream()
                app.deviceStates[event.device.deviceId] = "Disconnected"
                systemVolumeSessionCompat?.isActive = false
                volumeProvider = null
                lastKnownDesktopVolume = null
                "Failed to connect to ${event.device.name}: ${event.reason}"
            }
            is SyncConnector.Event.StartCameraStream -> {
                startCameraStream()
                return
            }
            is SyncConnector.Event.StopCameraStream -> {
                stopCameraStream()
                return
            }
            is SyncConnector.Event.UpdateCameraConfig -> {
                val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                event.isFront?.let { editor.putBoolean("camera_facing_front", it) }
                event.resolution?.let { editor.putString("camera_resolution", it) }
                event.fps?.let { editor.putInt("camera_fps", it) }
                event.rotation?.let { editor.putInt("camera_rotation", it) }
                event.useAdb?.let { editor.putString("camera_connection_mode", if (it) "adb" else "wifi") }
                editor.apply()

                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(android.content.Intent(ACTION_STATE_CHANGED))

                val currentIsFront = prefs.getBoolean("camera_facing_front", false)
                val currentRes = prefs.getString("camera_resolution", "1920x1080") ?: "1920x1080"
                val currentFps = prefs.getInt("camera_fps", 30)
                val currentRot = prefs.getInt("camera_rotation", 0)
                val currentAdb = prefs.getString("camera_connection_mode", "wifi") == "adb"
                connector.sendCameraConfigState(currentIsFront, currentRes, currentFps, currentRot, currentAdb)

                if (cameraServer != null) {
                    stopCameraStream()
                    startCameraStream()
                }
                return
            }
            is SyncConnector.Event.StartMicStream -> {
                startMicStream()
                return
            }
            is SyncConnector.Event.StopMicStream -> {
                stopMicStream()
                return
            }
            is SyncConnector.Event.AudioStreamInfo -> {
                val prefs = getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("audio_stream_enabled_${event.device.deviceId}", event.enabled).apply()
                
                if (event.enabled) {
                    startAudioStream(event.device.ip, event.port, event.device.deviceId)
                } else {
                    stopAudioStream()
                }
                return
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
            is SyncConnector.Event.FileTransferStarted -> {
                showDownloadProgressNotification(event.filename, 0, true)
                "Downloading: ${event.filename}"
            }
            is SyncConnector.Event.FileTransferProgress -> {
                val pct = ((event.bytesReceived * 100) / event.totalBytes).toInt()
                showDownloadProgressNotification(event.filename, pct, false)
                return // Do not update main status notification during active transfer
            }
            is SyncConnector.Event.FileTransferFinished -> {
                dismissDownloadNotification()
                if (event.success) {
                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    showFileReceivedNotification(java.io.File(downloadsDir, event.filename))
                    "Received file: ${event.filename}"
                } else {
                    "Failed to receive file: ${event.filename}"
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
                lastKnownDesktopVolume = event.volume
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
        val notification = createNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val app = application as? SyncApp
            val isCameraActive = app?.isCameraStreaming == true
            val isMicActive = app?.isMicStreaming == true
            
            var type = 0
            if (Build.VERSION.SDK_INT >= 34) { // Android 14+
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            
            // Only add types if we actually have the permission, otherwise startForeground crashes on Android 14+
            if (isCameraActive && ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            
            if (isMicActive && ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            
            try {
                if (type != 0) {
                    startForeground(NOTIFICATION_ID, notification, type)
                } else {
                    // Fallback for Android 14+ still requires at least one type if declared in manifest
                    if (Build.VERSION.SDK_INT >= 34) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SyncService", "Failed to startForeground with type $type", e)
                // Final fallback - try starting with just CONNECTED_DEVICE if 14+, or no type if older
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                    } catch (e2: Exception) {
                        android.util.Log.e("SyncService", "Critical failure starting foreground service", e2)
                    }
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun saveReceivedFile(filename: String, base64Data: String, expectedSha256: String) {
        // Prevent OOM for extremely large files received via non-streaming method
        if (base64Data.length > 50 * 1024 * 1024) { // 50MB Base64 limit (~37MB file)
            android.util.Log.e("SyncService", "File too large for memory-based reception: $filename")
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, "File too large to receive (use streaming)", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }

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

    private fun showDownloadProgressNotification(filename: String, progressPercent: Int, isIndeterminate: Boolean) {
        val notification = NotificationCompat.Builder(this, UPLOAD_CHANNEL_ID)
            .setContentTitle("Downloading: $filename")
            .setContentText("Receiving file...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(100, progressPercent, isIndeterminate)
            .setOnlyAlertOnce(true)
            .build()
        notificationManager.notify(UPLOAD_NOTIFICATION_ID, notification)
    }

    private fun dismissDownloadNotification() {
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
