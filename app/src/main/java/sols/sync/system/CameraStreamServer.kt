package sols.sync.system

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraStreamServer(
    private val context: Context,
    private val isFrontCamera: Boolean = false,
    private val width: Int = 640,
    private val height: Int = 480,
    private val targetFps: Int = 30,
    private val rotation: Int = 0,
    private val onStarted: (Int) -> Unit,
    private val onStopped: () -> Unit
) {
    companion object {
        private const val TAG = "CameraStreamServer"
    }

    private var serverSocket: ServerSocket? = null
    private val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
    private var serverThread: Thread? = null
    private var isRunning = false

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    private var isProcessing = false

    fun start() {
        if (isRunning) return
        isRunning = true
        
        // Start background thread for TCP HTTP server
        serverThread = Thread {
            try {
                val server = ServerSocket(0) // bind to a random free port
                serverSocket = server
                val port = server.localPort
                Log.i(TAG, "MJPEG Server started on port $port")
                onStarted(port)

                while (isRunning) {
                    val socket = server.accept()
                    handleClient(socket)
                }
            } catch (e: Exception) {
                Log.i(TAG, "Server socket closed: ${e.message}")
            }
        }.apply { start() }

        // Start background thread for camera
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        startCamera()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        // Close server socket and clients
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        for (client in clients) {
            client.stop()
        }
        clients.clear()

        // Stop camera
        stopCamera()

        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null

        executor.shutdown()
        onStopped()
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = findCameraId(manager) ?: return
            val chars = manager.getCameraCharacteristics(cameraId)
            val bestSize = findBestSize(chars, width, height)
            val finalWidth = bestSize.width
            val finalHeight = bestSize.height
            Log.i(TAG, "Target resolution: ${width}x${height}, chosen size: ${finalWidth}x${finalHeight}")

            imageReader = ImageReader.newInstance(finalWidth, finalHeight, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        if (isRunning && clients.isNotEmpty()) {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            broadcastFrame(bytes)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing camera image", e)
                    } finally {
                        image.close()
                    }
                }, cameraHandler)
            }

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, Handler(Looper.getMainLooper()))

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera", e)
        }
    }

    private fun stopCamera() {
        try {
            captureSession?.stopRepeating()
            captureSession?.close()
        } catch (_: Exception) {}
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {}
        cameraDevice = null

        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null
    }

    private fun findCameraId(manager: CameraManager): String? {
        val targetFacing = if (isFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == targetFacing) {
                return id
            }
        }
        return manager.cameraIdList.firstOrNull()
    }

    private fun findBestSize(chars: CameraCharacteristics, targetWidth: Int, targetHeight: Int): android.util.Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()

        for (size in sizes) {
            if (size.width == targetWidth && size.height == targetHeight) {
                return size
            }
        }

        val targetRatio = targetWidth.toDouble() / targetHeight.toDouble()
        var bestSize = sizes.firstOrNull() ?: android.util.Size(640, 480)
        var minDiff = Double.MAX_VALUE

        for (size in sizes) {
            val ratio = size.width.toDouble() / size.height.toDouble()
            val ratioDiff = Math.abs(ratio - targetRatio)
            val areaDiff = Math.abs((size.width * size.height) - (targetWidth * targetHeight))

            val score = ratioDiff * 1000000.0 + areaDiff
            if (score < minDiff) {
                minDiff = score
                bestSize = size
            }
        }
        return bestSize
    }

    private fun findBestFpsRange(chars: CameraCharacteristics, targetFps: Int): android.util.Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        var bestRange: android.util.Range<Int>? = null
        var bestDiff = Int.MAX_VALUE
        for (range in ranges) {
            val diff = Math.abs(range.upper - targetFps)
            if (diff < bestDiff) {
                bestDiff = diff
                bestRange = range
            } else if (diff == bestDiff) {
                if (bestRange == null || Math.abs(range.lower - targetFps) < Math.abs(bestRange.lower - targetFps)) {
                    bestRange = range
                }
            }
        }
        return bestRange
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return
        val surface = reader.surface

        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                
                // Set target FPS range dynamically
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val chars = manager.getCameraCharacteristics(camera.id)
                val fpsRange = findBestFpsRange(chars, targetFps)
                if (fpsRange != null) {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
                }

                // Hardware JPEG rotation
                set(CaptureRequest.JPEG_ORIENTATION, rotation)
                
                // Lower JPEG quality to ensure smooth 30fps streaming even at 4K resolution
                set(CaptureRequest.JPEG_QUALITY, 50.toByte())
            }

            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(builder.build(), null, cameraHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set repeating request", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Configure failed")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }

    private fun handleClient(socket: Socket) {
        Thread {
            try {
                // Set temporary read timeout to prevent slow or malicious connections holding open socket thread forever
                socket.soTimeout = 5000
                val input = socket.getInputStream().bufferedReader()
                val line = input.readLine() ?: return@Thread
                if (line.startsWith("GET")) {
                    // Reset read timeout as we won't read from this socket anymore
                    socket.soTimeout = 0
                    val client = ClientHandler(socket)
                    clients.add(client)
                    Log.i(TAG, "Client connected: ${socket.remoteSocketAddress}. Active clients: ${clients.size}")
                } else {
                    socket.close()
                }
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun broadcastFrame(jpeg: ByteArray) {
        val iterator = clients.iterator()
        val now = System.currentTimeMillis()
        while (iterator.hasNext()) {
            val client = iterator.next()
            
            // Watchdog check: if client has been writing a frame for too long, they have a low quality connection.
            val writeStart = client.lastWriteStart
            if (writeStart > 0 && (now - writeStart > 1500)) {
                Log.w(TAG, "Client write timed out (low quality connection), disconnecting: ${client.socket.remoteSocketAddress}")
                client.stop()
                iterator.remove()
                continue
            }

            if (client.socket.isClosed || !client.isRunning || client.socket.isOutputShutdown) {
                client.stop()
                iterator.remove()
                Log.i(TAG, "Client disconnected. Active clients: ${clients.size}")
                continue
            }
            client.offerFrame(jpeg)
        }
    }

    class ClientHandler(val socket: Socket) {
        private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(2)
        private var thread: Thread? = null
        @Volatile var isRunning = true
        @Volatile var lastWriteStart: Long = 0

        init {
            thread = Thread {
                try {
                    val out = socket.getOutputStream()
                    // Write HTTP headers
                    out.write(
                        ("HTTP/1.1 200 OK\r\n" +
                         "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                         "Connection: close\r\n" +
                         "Pragma: no-cache\r\n" +
                         "Cache-Control: no-cache, private\r\n\r\n").toByteArray()
                    )

                    val boundary = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ".toByteArray()
                    val mid = "\r\n\r\n".toByteArray()
                    val suffix = "\r\n\r\n".toByteArray()

                    while (isRunning) {
                        val jpeg = queue.take() // Blocks if empty
                        lastWriteStart = System.currentTimeMillis()
                        out.write(boundary)
                        out.write(jpeg.size.toString().toByteArray())
                        out.write(mid)
                        out.write(jpeg)
                        out.write(suffix)
                        out.flush()
                        lastWriteStart = 0
                    }
                } catch (_: Exception) {
                } finally {
                    isRunning = false
                    lastWriteStart = 0
                    try { socket.close() } catch (_: Exception) {}
                }
            }.apply { start() }
        }

        fun offerFrame(jpeg: ByteArray) {
            while (!queue.offer(jpeg)) {
                queue.poll()
            }
        }

        fun stop() {
            isRunning = false
            thread?.interrupt()
            try { socket.close() } catch (_: Exception) {}
        }
    }


}
