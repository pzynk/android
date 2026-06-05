package sols.sync.network

/**
 * Networking defaults shared by the discovery scanner and the TCP
 * client. These must match the desktop's
 * `network::DEFAULT_DISCOVERY_PORT` / `DEFAULT_TCP_PORT`.
 */
object NetworkConfig {
    /** UDP port the desktop broadcasts its presence on. */
    const val DISCOVERY_PORT: Int = 8200

    /** Fallback TCP port if a broadcast doesn't carry one. */
    const val DEFAULT_TCP_PORT: Int = 8080
}
