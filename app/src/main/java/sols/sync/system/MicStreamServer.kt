package sols.sync.system

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class MicStreamServer(
    private val sampleRate: Int = 44100,
    private val channels: Int = 1,
    private val enableNoiseSuppressor: Boolean = true,
    private val onStarted: (Int) -> Unit,
    private val onStopped: () -> Unit
) {
    companion object {
        private const val TAG = "MicStreamServer"
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var recordThread: Thread? = null
    private var serverThread: Thread? = null
    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return
        isRunning = true

        val channelConfig = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        var record: AudioRecord? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (_: Exception) {
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AudioRecord: ${e.message}")
            }
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized properly")
            isRunning = false
            onStopped()
            return
        }

        audioRecord = record

        if (enableNoiseSuppressor) {
            try {
                val audioSessionId = record.audioSessionId
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                        enabled = true
                    }
                }
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(audioSessionId)?.apply {
                        enabled = true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable audio FX: ${e.message}")
            }
        }

        serverThread = Thread {
            try {
                val server = ServerSocket(0)
                serverSocket = server
                val port = server.localPort
                Log.i(TAG, "MicStreamServer bound to port $port")
                onStarted(port)

                while (isRunning) {
                    val socket = server.accept()
                    socket.tcpNoDelay = true
                    activeClients.add(socket)
                    Log.i(TAG, "Client connected to MicStreamServer: ${socket.remoteSocketAddress}")
                }
            } catch (e: Exception) {
                Log.i(TAG, "Server socket closed: ${e.message}")
            }
        }.apply { start() }

        recordThread = Thread {
            try {
                record.startRecording()
                val pcmBuffer = ByteArray(bufferSize)

                while (isRunning) {
                    val readBytes = record.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readBytes > 0 && isRunning) {
                        val iterator = activeClients.iterator()
                        while (iterator.hasNext()) {
                            val client = iterator.next()
                            try {
                                val out = client.getOutputStream()
                                out.write(pcmBuffer, 0, readBytes)
                                out.flush()
                            } catch (_: Exception) {
                                try { client.close() } catch (_: Exception) {}
                                iterator.remove()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) {}
            }
        }.apply { start() }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        for (client in activeClients) {
            try { client.close() } catch (_: Exception) {}
        }
        activeClients.clear()

        try {
            recordThread?.interrupt()
            recordThread = null
        } catch (_: Exception) {}

        try {
            serverThread?.interrupt()
            serverThread = null
        } catch (_: Exception) {}

        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {}
        noiseSuppressor = null

        try {
            agc?.release()
        } catch (_: Exception) {}
        agc = null

        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        onStopped()
    }
}
