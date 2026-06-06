package sols.sync

import android.app.Application
import com.google.android.material.color.DynamicColors
import sols.sync.network.model.BroadcastMessage
import java.util.concurrent.ConcurrentHashMap

class SyncApp : Application() {
    /** Application-level cache of devices discovered via UDP broadcast. */
    val discoveredCache = ConcurrentHashMap<String, BroadcastMessage>()
    
    /** Application-level cache of connection states for devices. */
    val deviceStates = ConcurrentHashMap<String, String>()

    /** Application-level cache of media states for devices. */
    val mediaStates = ConcurrentHashMap<String, sols.sync.network.protocol.ServerMessage.MediaState>()

    data class TerminalInfo(
        val enabled: Boolean,
        val port: Int,
        val username: String,
        val password: String?
    )
    /** Application-level cache of terminal information for devices. */
    val terminalInfos = ConcurrentHashMap<String, TerminalInfo>()

    override fun onCreate() {
        super.onCreate()
        // Apply Material You wallpaper-based dynamic color on Android 12+
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
