package sols.sync.system

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

data class TrustedPeer(
    val deviceId: String,
    val name: String,
    val token: String,
    val os: String = "unknown",
)

class TrustedPeersStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "sync_trusted_peers",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(deviceId: String): TrustedPeer? {
        val raw = prefs.getString(deviceId, null) ?: return null
        return try {
            val json = JSONObject(raw)
            TrustedPeer(
                deviceId = deviceId,
                name = json.getString("name"),
                token = json.getString("token"),
                os = json.optString("os", "unknown"),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun put(peer: TrustedPeer) {
        val json = JSONObject()
            .put("name", peer.name)
            .put("token", peer.token)
            .put("os", peer.os)
        prefs.edit().putString(peer.deviceId, json.toString()).apply()
    }

    fun remove(deviceId: String) {
        prefs.edit().remove(deviceId).apply()
    }

    fun all(): List<TrustedPeer> = prefs.all.keys.mapNotNull { get(it) }
}
