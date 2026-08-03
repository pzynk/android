package sols.sync.network

import sols.sync.network.model.BroadcastMessage
import sols.sync.network.protocol.ClientMessage
import sols.sync.network.protocol.ServerMessage
import sols.sync.network.tcp.TcpClient
import sols.sync.system.TrustedPeer
import sols.sync.system.TrustedPeersStore
import java.security.MessageDigest
import java.security.SecureRandom

class PairingSession(
    private val tcp: TcpClient,
    private val trustedPeers: TrustedPeersStore,
    private val phoneDeviceId: String,
    private val phoneName: String,
) {
    sealed class Result {
        data class Connected(val pairedBefore: Boolean) : Result()
        data class PairRejected(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun run(device: BroadcastMessage, onPairingCode: (String) -> Unit): Result {
        val existing = trustedPeers.get(device.deviceId)
        val hello = ClientMessage.Hello(
            deviceId = phoneDeviceId,
            name = phoneName,
            token = existing?.token,
        )
        if (!tcp.writeLine(hello.toJson())) return Result.Failed("Could not send hello")

        return when (val first = readServerMessage()) {
            is ServerMessage.HelloOk -> Result.Connected(pairedBefore = true)
            is ServerMessage.PairRequired -> {
                // If we sent a stored token but the desktop no longer recognises it,
                // the desktop's trusted store was cleared — auto-unpair on our side too.
                if (existing != null) {
                    trustedPeers.remove(device.deviceId)
                    return Result.Failed("Desktop no longer trusts this device. Unpaired automatically.")
                }
                pair(device, onPairingCode)
            }
            is ServerMessage.PairRejected -> Result.PairRejected(first.reason)
            is ServerMessage.PairAccepted -> {
                savePeer(first, device.os)
                Result.Connected(pairedBefore = false)
            }
            is ServerMessage.ClipboardUpdate -> Result.Failed("Unexpected ClipboardUpdate")
            is ServerMessage.IncomingFile -> Result.Failed("Unexpected IncomingFile")
            is ServerMessage.FileTransferStart -> Result.Failed("Unexpected FileTransferStart")
            is ServerMessage.MediaState -> Result.Failed("Unexpected MediaState")
            is ServerMessage.SystemVolumeUpdate -> Result.Failed("Unexpected SystemVolumeUpdate")
            is ServerMessage.TerminalServerInfo -> Result.Failed("Unexpected TerminalServerInfo")
            is ServerMessage.StartCameraStream -> Result.Failed("Unexpected StartCameraStream")
            is ServerMessage.StopCameraStream -> Result.Failed("Unexpected StopCameraStream")
            is ServerMessage.AudioStreamInfo -> Result.Failed("Unexpected AudioStreamInfo")
            is ServerMessage.Unpair -> Result.Failed("Unexpected Unpair")
            null -> Result.Failed("Desktop closed the connection")
        }
    }
 
    private fun pair(device: BroadcastMessage, onPairingCode: (String) -> Unit): Result {
        val nonce = newNonce()
        val code = verificationCode(phoneDeviceId, device.deviceId, nonce)
        onPairingCode(code)
        val request = ClientMessage.PairRequest(
            deviceId = phoneDeviceId,
            name = phoneName,
            nonce = nonce,
        )
        if (!tcp.writeLine(request.toJson())) return Result.Failed("Could not send pair request")
 
        return when (val response = readServerMessage()) {
            is ServerMessage.PairAccepted -> {
                savePeer(response, device.os)
                Result.Connected(pairedBefore = false)
            }
            is ServerMessage.PairRejected -> Result.PairRejected(response.reason)
            is ServerMessage.HelloOk -> Result.Connected(pairedBefore = false)
            is ServerMessage.PairRequired -> Result.Failed("Desktop requested pairing again")
            is ServerMessage.ClipboardUpdate -> Result.Failed("Unexpected ClipboardUpdate")
            is ServerMessage.IncomingFile -> Result.Failed("Unexpected IncomingFile")
            is ServerMessage.FileTransferStart -> Result.Failed("Unexpected FileTransferStart")
            is ServerMessage.MediaState -> Result.Failed("Unexpected MediaState")
            is ServerMessage.SystemVolumeUpdate -> Result.Failed("Unexpected SystemVolumeUpdate")
            is ServerMessage.TerminalServerInfo -> Result.Failed("Unexpected TerminalServerInfo")
            is ServerMessage.StartCameraStream -> Result.Failed("Unexpected StartCameraStream")
            is ServerMessage.StopCameraStream -> Result.Failed("Unexpected StopCameraStream")
            is ServerMessage.AudioStreamInfo -> Result.Failed("Unexpected AudioStreamInfo")
            is ServerMessage.Unpair -> Result.Failed("Unexpected Unpair")
            null -> Result.Failed("Desktop closed before pairing completed")
        }
    }

    private fun readServerMessage(): ServerMessage? {
        val line = tcp.readLine() ?: return null
        return ServerMessage.parse(line)
    }

    private fun savePeer(message: ServerMessage.PairAccepted, os: String) {
        trustedPeers.put(
            TrustedPeer(
                deviceId = message.deviceId,
                name = message.name,
                token = message.token,
                os = os,
            )
        )
    }

    private fun newNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun verificationCode(phoneId: String, desktopId: String, nonce: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((phoneId + desktopId + nonce).toByteArray(Charsets.UTF_8))
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (digest[i].toLong() and 0xff)
        }
        return "%06d".format(Math.floorMod(value, 1_000_000L))
    }
}
