package Socket.AdapterSocket.Shadowsocks

/**
 * Interface for protocol obfuscation, used in Shadowsocks to disguise traffic.
 *
 * In Swift, this was `ProtocolObfuscater`.
 */
interface ProtocolObfuscater {
    /**
     * Obfuscates data for sending (e.g., adds random bytes, changes patterns).
     *
     * @param data The original data to obfuscate.
     * @return The obfuscated data.
     */
    fun PObfs(data: ByteArray): ByteArray

    /**
     * De-obfuscates data received (e.g., removes random bytes, restores patterns).
     *
     * @param data The obfuscated data to de-obfuscate.
     * @return The de-obfuscated data.
     */
    fun IObfs(data: ByteArray): ByteArray
}
