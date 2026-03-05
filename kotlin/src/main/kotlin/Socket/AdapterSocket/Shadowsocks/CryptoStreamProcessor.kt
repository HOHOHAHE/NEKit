package nekit.Socket.AdapterSocket.Shadowsocks

/**
 * Interface for stream cipher operations (encryption and decryption).
 * Used in Shadowsocks for encrypting/decrypting the data stream.
 *
 * In Swift, this was `StreamCrypto`.
 */
interface CryptoStreamProcessor {
    /**
     * Encrypts the given data.
     *
     * @param data The original data to encrypt.
     * @return The encrypted data.
     */
    fun encrypt(data: ByteArray): ByteArray

    /**
     * Decrypts the given data.
     *
     * @param data The encrypted data to decrypt.
     * @return The decrypted data.
     */
    fun decrypt(data: ByteArray): ByteArray
}
