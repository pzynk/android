package sols.sync.network.tcp

import android.util.Log
import java.io.Closeable
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Thin wrapper around a [Socket] used to open the sync TCP connection
 * to a desktop peer discovered via [sols.sync.network.discovery.DiscoveryScanner].
 *
 * Mirrors the desktop side `network::tcp` (the client side here).
 */
class TcpClient : Closeable {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    /**
     * Connect to `host:port`. Blocking — call from a background thread.
     * @return true if the connection was established.
     */
    fun connect(host: String, port: Int, timeoutMs: Int = 3000): Boolean {
        close()
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 120_000
            socket = s
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            Log.i(TAG, "Connected to $host:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to $host:$port", e)
            false
        }
    }

    @Synchronized
    fun writeLine(payload: String): Boolean {
        val w = writer ?: return false
        return try {
            w.write(payload)
            w.newLine()
            w.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Write line failed", e)
            false
        }
    }

    fun readLine(): String? {
        return try {
            reader?.readLine()
        } catch (e: Exception) {
            Log.e(TAG, "Read line failed", e)
            null
        }
    }

    /** Send a UTF-8 encoded payload. Blocking. */
    fun send(payload: String): Boolean {
        val s = socket ?: return false
        return try {
            s.getOutputStream().apply {
                write(payload.toByteArray(Charsets.UTF_8))
                flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            false
        }
    }

    @Synchronized
    fun writeIncomingFileStreaming(
        filename: String,
        uri: android.net.Uri,
        contentResolver: android.content.ContentResolver,
        sha256: String,
        fileSize: Long,
        onProgress: ((sentBytes: Long) -> Unit)? = null,
        cancelCheck: (() -> Boolean)? = null
    ): Boolean {
        val w = writer ?: return false
        val s = socket ?: return false
        return try {
            val quoted = org.json.JSONObject.quote(filename)
            val escapedFilename = quoted.substring(1, quoted.length - 1)
            
            // Write JSON prefix
            w.write("{\"type\":\"IncomingFile\",\"filename\":\"$escapedFilename\",\"base64_data\":\"")
            w.flush()
            
            // Stream encode file content to Base64 directly into socket OutputStream
            val rawOut = s.getOutputStream()
            
            class NonClosingOutputStream(private val delegate: java.io.OutputStream) : java.io.OutputStream() {
                override fun write(b: Int) = delegate.write(b)
                override fun write(b: ByteArray) = delegate.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
                override fun flush() = delegate.flush()
                override fun close() {}
            }
            
            val nonClosing = NonClosingOutputStream(rawOut)
            val bufferedOut = java.io.BufferedOutputStream(nonClosing, 65536)
            val base64Out = android.util.Base64OutputStream(bufferedOut, android.util.Base64.NO_WRAP)
            
            var totalBytesSent = 0L
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (cancelCheck?.invoke() == true || Thread.currentThread().isInterrupted) {
                        throw java.io.IOException("Streaming file write cancelled")
                    }
                    base64Out.write(buffer, 0, bytesRead)
                    totalBytesSent += bytesRead
                    onProgress?.invoke(totalBytesSent)
                }
            }
            base64Out.close() // finishes encoding and flushes, but doesn't close socket
            bufferedOut.flush() // flush remaining bytes
            
            // Write JSON suffix
            w.write("\",\"sha256\":\"$sha256\"}")
            w.newLine()
            w.flush()
            true
        } catch (e: Exception) {
            Log.e("TcpClient", "Streaming file write failed", e)
            close()
            false
        }
    }

    fun readIncomingFileStreaming(
        totalBytes: Long,
        onProgress: (bytesRead: Long) -> Unit,
        onWriteBytes: (bytes: ByteArray, length: Int) -> Unit
    ): String? {
        val r = reader ?: return null
        try {
            // 1. Read until we find `"base64_data":"`
            val target = "\"base64_data\":\""
            var targetIdx = 0
            var totalRead = 0L
            
            while (targetIdx < target.length) {
                val c = r.read()
                if (c == -1) return null
                totalRead++
                if (c.toChar() == target[targetIdx]) {
                    targetIdx++
                } else {
                    targetIdx = if (c.toChar() == target[0]) 1 else 0
                }
            }
            
            // 2. Read base64 data until we see the ending `"`
            val charBuf = CharArray(4096)
            var charBufLen = 0
            
            while (true) {
                val c = r.read()
                if (c == -1) return null
                totalRead++
                val ch = c.toChar()
                if (ch == '"') {
                    break;
                }
                
                if (ch.isLetterOrDigit() || ch == '+' || ch == '/' || ch == '=') {
                    charBuf[charBufLen++] = ch
                    if (charBufLen == charBuf.size) {
                        val base64Str = String(charBuf, 0, charBufLen)
                        val decoded = android.util.Base64.decode(base64Str, android.util.Base64.NO_WRAP)
                        onWriteBytes(decoded, decoded.size)
                        charBufLen = 0
                        onProgress(totalRead)
                    }
                }
            }
            
            if (charBufLen > 0) {
                val base64Str = String(charBuf, 0, charBufLen)
                val decoded = android.util.Base64.decode(base64Str, android.util.Base64.NO_WRAP)
                onWriteBytes(decoded, decoded.size)
                onProgress(totalRead)
            }
            
            // 3. Read suffix until `\n`
            val suffixBuilder = StringBuilder()
            while (true) {
                val c = r.read()
                if (c == -1) break
                totalRead++
                val ch = c.toChar()
                if (ch == '\n') break
                suffixBuilder.append(ch)
            }
            
            val suffix = suffixBuilder.toString()
            val shaKey = "\"sha256\":\""
            val shaIdx = suffix.indexOf(shaKey)
            if (shaIdx != -1) {
                val start = shaIdx + shaKey.length
                val end = suffix.indexOf("\"", start)
                if (end != -1) {
                    return suffix.substring(start, end)
                }
            }
            return ""
        } catch (e: Exception) {
            Log.e("TcpClient", "Error in readIncomingFileStreaming", e)
            return null
        }
    }

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        reader = null
        writer = null
    }

    private companion object {
        const val TAG = "TcpClient"
    }
}
