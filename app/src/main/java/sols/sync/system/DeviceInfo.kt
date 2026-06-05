package sols.sync.system

import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Host info helpers mirroring `desktop/src-tauri/src/system`.
 */
object DeviceInfo {

    /** Human-readable device name advertised to peers. */
    fun getDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

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
