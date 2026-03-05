package nekit.Socket.AdapterSocket.Shadowsocks

import nekit.Messages.ConnectSession

/**
 * Interface for stream obfuscation, used in Shadowsocks to modify the data stream.
 *
 * In Swift, this was `StreamObfuscater`.
 */
interface StreamObfuscater {
    /**
     * Obfuscates data for sending over the stream.
     *
     * @param data The original data to obfuscate.
     * @return The obfuscated data.
     */
    fun SObfs(data: ByteArray): ByteArray

    /**
     * De-obfuscates data received from the stream.
     *
     * @param data The obfuscated data to de-obfuscate.
     * @return The de-obfuscated data.
     */
    fun IObfs(data: ByteArray): ByteArray
}
