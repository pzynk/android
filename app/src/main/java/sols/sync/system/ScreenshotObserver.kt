package sols.sync.system

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ScreenshotObserver(
    private val context: Context,
    private val onScreenshotCaptured: (String) -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private var lastProcessedId: Long = -1L

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        checkLatestScreenshot(uri)
    }

    private fun checkLatestScreenshot(targetUri: Uri?) {
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED
            )
            
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            ) ?: return

            cursor.use { c ->
                if (c.moveToFirst()) {
                    val idIndex = c.getColumnIndex(MediaStore.Images.Media._ID)
                    val dataIndex = c.getColumnIndex(MediaStore.Images.Media.DATA)
                    val nameIndex = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateIndex = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)

                    val id = if (idIndex >= 0) c.getLong(idIndex) else -1L
                    val dataPath = if (dataIndex >= 0) c.getString(dataIndex) ?: "" else ""
                    val displayName = if (nameIndex >= 0) c.getString(nameIndex) ?: "" else ""
                    val dateAdded = if (dateIndex >= 0) c.getLong(dateIndex) else 0L

                    if (id == lastProcessedId || id == -1L) return

                    val currentTimeSec = System.currentTimeMillis() / 1000
                    if (currentTimeSec - dateAdded > 15) return

                    val isScreenshot = dataPath.contains("screenshot", ignoreCase = true) ||
                            displayName.contains("screenshot", ignoreCase = true)

                    if (isScreenshot) {
                        lastProcessedId = id
                        val contentUri = Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id.toString()
                        )
                        processImageUri(contentUri)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenshotObserver", "Error querying screenshot", e)
        }
    }

    private fun processImageUri(uri: Uri) {
        Thread {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                inputStream?.use { stream ->
                    val buffer = ByteArrayOutputStream()
                    val data = ByteArray(16384)
                    var nRead: Int
                    while (stream.read(data, 0, data.size).also { nRead = it } != -1) {
                        buffer.write(data, 0, nRead)
                    }
                    buffer.flush()
                    val bytes = buffer.toByteArray()
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    if (base64.isNotBlank()) {
                        onScreenshotCaptured(base64)
                    }
                }
            } catch (e: Exception) {
                Log.e("ScreenshotObserver", "Error reading screenshot image bytes", e)
            }
        }.start()
    }
}
