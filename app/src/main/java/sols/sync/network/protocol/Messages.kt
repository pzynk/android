package sols.sync.network.protocol

import org.json.JSONObject

sealed class ClientMessage {
    data class Hello(
        val deviceId: String,
        val name: String,
        val token: String?,
    ) : ClientMessage()

    data class PairRequest(
        val deviceId: String,
        val name: String,
        val nonce: String,
    ) : ClientMessage()

    data class ClipboardUpdate(val text: String) : ClientMessage()

    data class MediaCommand(val command: String, val value: Double? = null) : ClientMessage()

    data class IncomingFile(
        val filename: String,
        val base64Data: String,
        val sha256: String,
    ) : ClientMessage()

    data class FileTransferStart(
        val filename: String,
        val totalBytes: Long,
    ) : ClientMessage()

    object Unpair : ClientMessage()
    data class CameraStreamStarted(val port: Int, val useAdb: Boolean = false) : ClientMessage()
    object CameraStreamStopped : ClientMessage()
    data class MicStreamStarted(val port: Int, val sampleRate: Int = 44100, val channels: Int = 1, val useAdb: Boolean = false) : ClientMessage()
    object MicStreamStopped : ClientMessage()
    data class AudioStreamRequest(val start: Boolean) : ClientMessage()
    data class ClipboardImage(val base64Data: String) : ClientMessage()
    data class CameraConfigState(
        val isFront: Boolean,
        val resolution: String,
        val fps: Int,
        val rotation: Int,
        val useAdb: Boolean
    ) : ClientMessage()

    fun toJson(): String {
        val json = JSONObject()
        when (this) {
            is Hello -> {
                json.put("type", "Hello")
                json.put("device_id", deviceId)
                json.put("name", name)
                if (token != null) json.put("token", token) else json.put("token", JSONObject.NULL)
            }
            is PairRequest -> {
                json.put("type", "PairRequest")
                json.put("device_id", deviceId)
                json.put("name", name)
                json.put("nonce", nonce)
            }
            is ClipboardUpdate -> {
                json.put("type", "ClipboardUpdate")
                json.put("text", text)
            }
            is ClipboardImage -> {
                json.put("type", "ClipboardImage")
                json.put("base64_data", base64Data)
            }
            is MediaCommand -> {
                json.put("type", "MediaCommand")
                json.put("command", command)
                if (value != null) json.put("value", value)
            }
            is IncomingFile -> {
                json.put("type", "IncomingFile")
                json.put("filename", filename)
                json.put("base64_data", base64Data)
                json.put("sha256", sha256)
            }
            is FileTransferStart -> {
                json.put("type", "FileTransferStart")
                json.put("filename", filename)
                json.put("total_bytes", totalBytes)
            }
            is Unpair -> {
                json.put("type", "Unpair")
            }
            is CameraStreamStarted -> {
                json.put("type", "CameraStreamStarted")
                json.put("port", port)
                json.put("use_adb", useAdb)
            }
            is CameraStreamStopped -> {
                json.put("type", "CameraStreamStopped")
            }
            is MicStreamStarted -> {
                json.put("type", "MicStreamStarted")
                json.put("port", port)
                json.put("sample_rate", sampleRate)
                json.put("channels", channels)
                json.put("use_adb", useAdb)
            }
            is MicStreamStopped -> {
                json.put("type", "MicStreamStopped")
            }
            is AudioStreamRequest -> {
                json.put("type", "AudioStreamRequest")
                json.put("start", start)
            }
            is CameraConfigState -> {
                json.put("type", "CameraConfigState")
                json.put("is_front", isFront)
                json.put("resolution", resolution)
                json.put("fps", fps)
                json.put("rotation", rotation)
                json.put("use_adb", useAdb)
            }
        }
        return json.toString()
    }

}

sealed class ServerMessage {
    data class HelloOk(val deviceId: String, val name: String) : ServerMessage()
    object PairRequired : ServerMessage()
    data class PairAccepted(val deviceId: String, val name: String, val token: String) : ServerMessage()
    data class PairRejected(val reason: String) : ServerMessage()
    data class ClipboardUpdate(val text: String) : ServerMessage()
    data class IncomingFile(val filename: String, val base64Data: String, val sha256: String) : ServerMessage()
    data class FileTransferStart(val filename: String, val totalBytes: Long) : ServerMessage()
    data class SystemVolumeUpdate(val volume: Double, val muted: Boolean) : ServerMessage()
    data class TerminalServerInfo(
        val enabled: Boolean,
        val port: Int,
        val username: String,
        val password: String?
    ) : ServerMessage()
    
    data class MediaState(
        val title: String,
        val artist: String,
        val album: String,
        val isPlaying: Boolean,
        val volume: Double,
        val positionUs: Long,
        val lengthUs: Long,
        val player: String
    ) : ServerMessage()

    object Unpair : ServerMessage()
    object StartCameraStream : ServerMessage()
    object StopCameraStream : ServerMessage()
    object StartMicStream : ServerMessage()
    object StopMicStream : ServerMessage()
    data class AudioStreamInfo(val enabled: Boolean, val port: Int) : ServerMessage()
    data class UpdateCameraConfig(
        val isFront: Boolean?,
        val resolution: String?,
        val fps: Int?,
        val rotation: Int?,
        val useAdb: Boolean?
    ) : ServerMessage()
    object RequestCameraConfig : ServerMessage()

    companion object {
        fun parse(line: String): ServerMessage? {
            return try {
                val json = JSONObject(line)
                when (json.getString("type")) {
                    "HelloOk" -> HelloOk(
                        deviceId = json.getString("device_id"),
                        name = json.getString("name"),
                    )
                    "PairRequired" -> PairRequired
                    "PairAccepted" -> PairAccepted(
                        deviceId = json.getString("device_id"),
                        name = json.getString("name"),
                        token = json.getString("token"),
                    )
                    "PairRejected" -> PairRejected(json.optString("reason", "Rejected"))
                    "ClipboardUpdate" -> ClipboardUpdate(json.getString("text"))
                    "IncomingFile" -> IncomingFile(
                        filename = json.getString("filename"),
                        base64Data = json.getString("base64_data"),
                        sha256 = json.getString("sha256")
                    )
                    "FileTransferStart" -> FileTransferStart(
                        filename = json.getString("filename"),
                        totalBytes = json.getLong("total_bytes")
                    )
                    "SystemVolumeUpdate" -> SystemVolumeUpdate(
                        volume = json.optDouble("volume", 1.0),
                        muted = json.optBoolean("muted", false)
                    )
                    "TerminalServerInfo" -> TerminalServerInfo(
                        enabled = json.getBoolean("enabled"),
                        port = json.getInt("port"),
                        username = json.getString("username"),
                        password = if (json.isNull("password")) null else json.getString("password")
                    )
                    "MediaState" -> MediaState(
                        title = json.optString("title", ""),
                        artist = json.optString("artist", ""),
                        album = json.optString("album", ""),
                        isPlaying = json.optBoolean("is_playing", false),
                        volume = json.optDouble("volume", 1.0),
                        positionUs = json.optLong("position_us", 0),
                        lengthUs = json.optLong("length_us", 0),
                        player = json.optString("player", "")
                    )
                    "Unpair" -> Unpair
                    "StartCameraStream" -> StartCameraStream
                    "StopCameraStream" -> StopCameraStream
                    "StartMicStream" -> StartMicStream
                    "StopMicStream" -> StopMicStream
                    "AudioStreamInfo" -> AudioStreamInfo(
                        enabled = json.getBoolean("enabled"),
                        port = json.getInt("port")
                    )
                    "UpdateCameraConfig" -> UpdateCameraConfig(
                        isFront = if (json.has("is_front") && !json.isNull("is_front")) json.getBoolean("is_front") else null,
                        resolution = if (json.has("resolution") && !json.isNull("resolution")) json.getString("resolution") else null,
                        fps = if (json.has("fps") && !json.isNull("fps")) json.getInt("fps") else null,
                        rotation = if (json.has("rotation") && !json.isNull("rotation")) json.getInt("rotation") else null,
                        useAdb = if (json.has("use_adb") && !json.isNull("use_adb")) json.getBoolean("use_adb") else null
                    )
                    "RequestCameraConfig" -> RequestCameraConfig
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
