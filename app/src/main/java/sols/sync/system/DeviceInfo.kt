package sols.sync.system

import android.content.Context
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * Host info helpers mirroring `desktop/src-tauri/src/system`.
 */
object DeviceInfo {

    /** Human-readable device name advertised to peers. */
    fun getDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("sync_device", Context.MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    /** First non-loopback IPv4 address of this device, or null. */
    fun getLocalIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
