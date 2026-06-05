package sols.sync.network.model

import org.json.JSONObject

/**
 * Wire-format payload broadcast by the desktop over UDP so that this
 * Android device can discover it on the local network.
 *
 * Mirrors `desktop/src-tauri/src/network/message.rs::BroadcastMessage`.
 */
data class BroadcastMessage(
    /** Local IPv4 address of the broadcasting device. */
    val ip: String,
    /** Human-readable hostname of the broadcasting device. */
    val name: String,
    /** TCP port to connect to for the sync session. */
    val port: Int,
) {
    companion object {
        /** Parse a JSON payload received over UDP. Returns null on malformed input. */
        fun fromJson(raw: String): BroadcastMessage? {
            return try {
                val json = JSONObject(raw)
                BroadcastMessage(
                    ip = json.getString("ip"),
                    name = json.getString("name"),
                    port = json.getInt("port"),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
