package sols.sync.network

import android.content.Context
import android.util.Log
import sols.sync.network.discovery.DiscoveryScanner
import sols.sync.network.model.BroadcastMessage
import sols.sync.network.tcp.TcpClient
import sols.sync.system.DeviceInfo
import sols.sync.system.TrustedPeersStore
import sols.sync.network.protocol.ClientMessage
import sols.sync.network.protocol.ServerMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * High-level façade that wires UDP discovery to the TCP client.
 *
 * Usage:
 * ```
 * val connector = SyncConnector { event -> /* update UI */ }
 * connector.start()
 * // ...
 * connector.stop()
 * ```
 */
class SyncConnector(
    context: Context,
    private val listener: (Event) -> Unit,
) {
    sealed class Event {
        /** Emitted the first time a peer is seen. */
        data class DeviceDiscovered(val device: BroadcastMessage, val paired: Boolean) : Event()
        /** Emitted when a first-time pair request is waiting for desktop approval. */
        data class PairingRequested(val device: BroadcastMessage, val code: String) : Event()
        /** Emitted after a successful authenticated connection. */
        data class Connected(val device: BroadcastMessage, val pairedBefore: Boolean) : Event()
        /** Emitted when the desktop rejects pairing. */
        data class PairRejected(val device: BroadcastMessage, val reason: String) : Event()
        /** Emitted on connection failure. */
        data class ConnectFailed(val device: BroadcastMessage, val reason: String) : Event()
        /** Emitted when desktop sends a new clipboard content. */
        data class ClipboardUpdate(val device: BroadcastMessage, val text: String) : Event()
        /** Emitted when desktop sends a file. */
        data class FileReceived(val device: BroadcastMessage, val filename: String, val base64Data: String, val sha256: String) : Event()
        data class FileTransferStarted(val device: BroadcastMessage, val filename: String, val totalBytes: Long) : Event()
        data class FileTransferProgress(val device: BroadcastMessage, val filename: String, val bytesReceived: Long, val totalBytes: Long) : Event()
        data class FileTransferFinished(val device: BroadcastMessage, val filename: String, val success: Boolean) : Event()
        /** Emitted when the desktop sends media state. */
        data class MediaStateUpdate(val device: BroadcastMessage, val state: ServerMessage.MediaState) : Event()
        /** Emitted when the desktop sends system volume update. */
        data class SystemVolumeUpdate(val device: BroadcastMessage, val volume: Double, val muted: Boolean) : Event()
        /** Emitted when the desktop sends terminal server info. */
        data class TerminalAccessUpdated(
            val device: BroadcastMessage,
            val enabled: Boolean,
            val port: Int,
            val username: String,
            val password: String?
        ) : Event()
        object StartCameraStream : Event()
        object StopCameraStream : Event()
        object StartMicStream : Event()
        object StopMicStream : Event()
        data class AudioStreamInfo(val device: BroadcastMessage, val enabled: Boolean, val port: Int) : Event()
        data class UpdateCameraConfig(
            val isFront: Boolean?,
            val resolution: String?,
            val fps: Int?,
            val rotation: Int?,
            val useAdb: Boolean?
        ) : Event()
    }

    private val appContext = context.applicationContext
    private val trustedPeers = TrustedPeersStore(appContext)
    private val phoneDeviceId = DeviceInfo.getOrCreateDeviceId(appContext)
    private val phoneName = DeviceInfo.getDeviceName()
    private val discovered = ConcurrentHashMap.newKeySet<String>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val pairAttempted = ConcurrentHashMap.newKeySet<String>()
    private val activeConnections = ConcurrentHashMap<String, TcpClient>()
    private var ioPool: ExecutorService = newIoPool()

    private val scanner = DiscoveryScanner(NetworkConfig.DISCOVERY_PORT) { device ->
        if (device.deviceId.isBlank()) {
            Log.w(TAG, "Ignoring desktop broadcast without deviceId: $device")
            return@DiscoveryScanner
        }
        val paired = trustedPeers.get(device.deviceId) != null
        if (discovered.add(device.deviceId)) {
            Log.i(TAG, "Discovered $device")
            listener(Event.DeviceDiscovered(device, paired))
        }
        if (shouldConnect(device.deviceId, paired)) {
            ioPool.execute { connect(device) }
        }
    }

    fun start() {
        if (ioPool.isShutdown) {
            ioPool = newIoPool()
        }
        scanner.start()
    }

    fun stop() {
        scanner.stop()
        activeConnections.values.forEach { it.close() }
        activeConnections.clear()
        connecting.clear()
        discovered.clear()
        pairAttempted.clear()
        ioPool.shutdownNow()
    }

    /** Force a (re)connect to the given device. */
    fun connect(device: BroadcastMessage) {
        if (!connecting.add(device.deviceId)) return
        if (activeConnections[device.deviceId]?.isConnected == true) {
            connecting.remove(device.deviceId)
            return
        }

        val tcp = TcpClient()
        if (tcp.connect(device.ip, device.port)) {
            val session = PairingSession(tcp, trustedPeers, phoneDeviceId, phoneName)
            when (val result = session.run(device) { code ->
                listener(Event.PairingRequested(device, code))
            }) {
                is PairingSession.Result.Connected -> {
                    activeConnections[device.deviceId]?.close()
                    activeConnections[device.deviceId] = tcp
                    listener(Event.Connected(device, result.pairedBefore))
                    ioPool.execute {
                        monitorConnection(device, tcp)
                    }
                }
                is PairingSession.Result.PairRejected -> {
                    listener(Event.PairRejected(device, result.reason))
                    tcp.close()
                }
                is PairingSession.Result.Failed -> {
                    listener(Event.ConnectFailed(device, result.reason))
                    tcp.close()
                }
            }
        } else {
            listener(Event.ConnectFailed(device, "Could not open TCP socket"))
        }
        connecting.remove(device.deviceId)
    }

    fun unpair(deviceId: String) {
        val payload = ClientMessage.Unpair.toJson()
        val tcp = activeConnections[deviceId]
        if (tcp != null) {
            ioPool.execute {
                tcp.writeLine(payload)
                try { Thread.sleep(100) } catch (_: Exception) {}
                tcp.close()
            }
        }
        trustedPeers.remove(deviceId)
    }

    fun sendClipboard(text: String) {
        val payload = ClientMessage.ClipboardUpdate(text).toJson()
        activeConnections.values.forEach { tcp ->
            ioPool.execute {
                tcp.writeLine(payload)
            }
        }
    }

    fun sendClipboardImage(base64Data: String) {
        val payload = ClientMessage.ClipboardImage(base64Data).toJson()
        activeConnections.values.forEach { tcp ->
            ioPool.execute {
                tcp.writeLine(payload)
            }
        }
    }

    fun sendMediaCommand(deviceId: String, command: String, value: Double? = null) {
        val payload = ClientMessage.MediaCommand(command, value).toJson()
        activeConnections[deviceId]?.let { tcp ->
            ioPool.execute {
                tcp.writeLine(payload)
            }
        }
    }

    fun sendCameraStreamStarted(port: Int, useAdb: Boolean = false): Boolean {
        val payload = ClientMessage.CameraStreamStarted(port, useAdb).toJson()
        val active = activeConnections.values.firstOrNull() ?: return false
        return active.writeLine(payload)
    }

    fun sendCameraStreamStopped(): Boolean {
        val payload = ClientMessage.CameraStreamStopped.toJson()
        val active = activeConnections.values.firstOrNull() ?: return false
        return active.writeLine(payload)
    }

    fun sendCameraConfigState(isFront: Boolean, resolution: String, fps: Int, rotation: Int, useAdb: Boolean): Boolean {
        val payload = ClientMessage.CameraConfigState(isFront, resolution, fps, rotation, useAdb).toJson()
        val active = activeConnections.values.firstOrNull() ?: return false
        return active.writeLine(payload)
    }

    fun sendMicStreamStarted(port: Int, sampleRate: Int = 44100, channels: Int = 1, useAdb: Boolean = false): Boolean {
        val payload = ClientMessage.MicStreamStarted(port, sampleRate, channels, useAdb).toJson()
        val active = activeConnections.values.firstOrNull() ?: return false
        return active.writeLine(payload)
    }

    fun sendMicStreamStopped(): Boolean {
        val payload = ClientMessage.MicStreamStopped.toJson()
        val active = activeConnections.values.firstOrNull() ?: return false
        return active.writeLine(payload)
    }

    fun sendAudioStreamRequest(deviceId: String, start: Boolean) {
        val payload = ClientMessage.AudioStreamRequest(start).toJson()
        activeConnections[deviceId]?.let { tcp ->
            ioPool.execute {
                tcp.writeLine(payload)
            }
        }
    }

    fun sendFile(deviceId: String, filename: String, base64Data: String, sha256: String): Boolean {
        val filePayload = ClientMessage.IncomingFile(filename, base64Data, sha256).toJson()
        val startPayload = ClientMessage.FileTransferStart(filename, filePayload.length.toLong()).toJson()
        val tcp = activeConnections[deviceId] ?: return false
        val successStart = tcp.writeLine(startPayload)
        if (!successStart) return false
        return tcp.writeLine(filePayload)
    }

    fun sendFileStreaming(
        deviceId: String,
        filename: String,
        uri: android.net.Uri,
        contentResolver: android.content.ContentResolver,
        sha256: String,
        fileSize: Long,
        onProgress: ((sentBytes: Long) -> Unit)? = null,
        cancelCheck: (() -> Boolean)? = null
    ): Boolean {
        val tcp = activeConnections[deviceId] ?: return false
        val quoted = org.json.JSONObject.quote(filename)
        val escapedFilename = quoted.substring(1, quoted.length - 1)
        val base64Len = ((fileSize + 2) / 3) * 4
        val totalPayloadBytes = 31 + escapedFilename.toByteArray(Charsets.UTF_8).size + 16 + base64Len + 12 + 64 + 2
        
        val startPayload = ClientMessage.FileTransferStart(filename, totalPayloadBytes).toJson()
        val successStart = tcp.writeLine(startPayload)
        if (!successStart) return false
        return tcp.writeIncomingFileStreaming(filename, uri, contentResolver, sha256, fileSize, onProgress, cancelCheck)
    }

    private fun monitorConnection(device: BroadcastMessage, tcp: TcpClient) {
        try {
            // Send initial camera config state on connection
            try {
                val prefs = appContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                val isFront = prefs.getBoolean("camera_facing_front", false)
                val res = prefs.getString("camera_resolution", "1920x1080") ?: "1920x1080"
                val fps = prefs.getInt("camera_fps", 30)
                val rot = prefs.getInt("camera_rotation", 0)
                val useAdb = prefs.getString("camera_connection_mode", "wifi") == "adb"
                tcp.writeLine(ClientMessage.CameraConfigState(isFront, res, fps, rot, useAdb).toJson())
            } catch (_: Exception) {}

            while (tcp.isConnected) {
                val line = tcp.readLine() ?: break
                when (val msg = ServerMessage.parse(line)) {
                    is ServerMessage.ClipboardUpdate -> {
                        listener(Event.ClipboardUpdate(device, msg.text))
                    }
                    is ServerMessage.IncomingFile -> {
                        listener(Event.FileReceived(device, msg.filename, msg.base64Data, msg.sha256))
                    }
                    is ServerMessage.FileTransferStart -> {
                        val receiveEnabled = appContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                            .getBoolean("receive_files_enabled", true)
                        
                        if (!receiveEnabled) {
                            tcp.readIncomingFileStreaming(msg.totalBytes, {}, { _, _ -> })
                        } else {
                            listener(Event.FileTransferStarted(device, msg.filename, msg.totalBytes))
                            
                            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                            if (!downloadsDir.exists()) downloadsDir.mkdirs()
                            
                            var file = java.io.File(downloadsDir, msg.filename)
                            var count = 1
                            val nameWithoutExt = file.nameWithoutExtension
                            val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
                            while (file.exists()) {
                                file = java.io.File(downloadsDir, "${nameWithoutExt}_${count}${ext}")
                                count++
                            }
                            
                            val stat = android.os.StatFs(downloadsDir.absolutePath)
                            val bytesAvailable = stat.availableBlocksLong * stat.blockSizeLong
                            if (bytesAvailable < msg.totalBytes) {
                                tcp.readIncomingFileStreaming(msg.totalBytes, {}, { _, _ -> })
                                listener(Event.FileTransferFinished(device, file.name, false))
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(appContext, "Not enough disk space to receive ${msg.filename}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            } else {
                                var success = false
                                var outputStream: java.io.FileOutputStream? = null
                                val digest = java.security.MessageDigest.getInstance("SHA-256")
                                try {
                                    val out = java.io.FileOutputStream(file)
                                    outputStream = out
                                    var lastPercent = -1
                                    
                                    val receivedSha256 = tcp.readIncomingFileStreaming(
                                        msg.totalBytes,
                                        onProgress = { readBytes ->
                                             val pct = ((readBytes * 100) / msg.totalBytes).toInt()
                                             if (pct != lastPercent) {
                                                 lastPercent = pct
                                                 listener(Event.FileTransferProgress(device, file.name, readBytes, msg.totalBytes))
                                             }
                                        },
                                        onWriteBytes = { bytes, len ->
                                            out.write(bytes, 0, len)
                                            digest.update(bytes, 0, len)
                                        }
                                    )
                                    out.close()
                                    outputStream = null
                                    
                                    if (receivedSha256 != null) {
                                        val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                                        if (computedSha256.equals(receivedSha256, ignoreCase = true)) {
                                            success = true
                                            sols.sync.ui.ReceivedFilesActivity.addReceivedFile(appContext, file.absolutePath)
                                        } else {
                                            Log.w("SyncConnector", "SHA-256 mismatch for received file: expected $receivedSha256, got $computedSha256")
                                            file.delete()
                                        }
                                    } else {
                                        file.delete()
                                    }
                                } catch (e: Exception) {
                                    Log.e("SyncConnector", "Error receiving file streamingly", e)
                                    outputStream?.close()
                                    file.delete()
                                }
                                listener(Event.FileTransferFinished(device, file.name, success))
                            }
                        }
                    }
                    is ServerMessage.MediaState -> {
                        listener(Event.MediaStateUpdate(device, msg))
                    }
                    is ServerMessage.SystemVolumeUpdate -> {
                        listener(Event.SystemVolumeUpdate(device, msg.volume, msg.muted))
                    }
                    is ServerMessage.TerminalServerInfo -> {
                        listener(Event.TerminalAccessUpdated(device, msg.enabled, msg.port, msg.username, msg.password))
                    }
                    is ServerMessage.StartCameraStream -> {
                        listener(Event.StartCameraStream)
                    }
                    is ServerMessage.StopCameraStream -> {
                        listener(Event.StopCameraStream)
                    }
                    is ServerMessage.UpdateCameraConfig -> {
                        listener(Event.UpdateCameraConfig(msg.isFront, msg.resolution, msg.fps, msg.rotation, msg.useAdb))
                    }
                    is ServerMessage.RequestCameraConfig -> {
                        val prefs = appContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                        val isFront = prefs.getBoolean("camera_facing_front", false)
                        val res = prefs.getString("camera_resolution", "1920x1080") ?: "1920x1080"
                        val fps = prefs.getInt("camera_fps", 30)
                        val rot = prefs.getInt("camera_rotation", 0)
                        val useAdb = prefs.getString("camera_connection_mode", "wifi") == "adb"
                        sendCameraConfigState(isFront, res, fps, rot, useAdb)
                    }
                    is ServerMessage.StartMicStream -> {
                        listener(Event.StartMicStream)
                    }
                    is ServerMessage.StopMicStream -> {
                        listener(Event.StopMicStream)
                    }
                    is ServerMessage.AudioStreamInfo -> {
                        listener(Event.AudioStreamInfo(device, msg.enabled, msg.port))
                    }
                    is ServerMessage.Unpair -> {
                        Log.i(TAG, "Received Unpair from desktop")
                        trustedPeers.remove(device.deviceId)
                        tcp.close()
                        break
                    }
                    else -> {
                        Log.d(TAG, "Received message from desktop: $line")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection monitor error for ${device.deviceId}", e)
        } finally {
            tcp.close()
            activeConnections.remove(device.deviceId)
            listener(Event.ConnectFailed(device, "Connection lost"))
        }
    }

    private fun shouldConnect(deviceId: String, paired: Boolean): Boolean {
        if (connecting.contains(deviceId)) return false
        if (activeConnections[deviceId]?.isConnected == true) return false
        return paired
    }

    private fun newIoPool(): ExecutorService {
        return Executors.newCachedThreadPool { r ->
            Thread(r, "sync-connector-worker").apply { isDaemon = true }
        }
    }

    private companion object {
        const val TAG = "SyncConnector"
    }
}
