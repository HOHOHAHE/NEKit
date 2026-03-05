package nekit.Crypto
/**
 * Protocol/interface for stream-based cryptographic operations (encryption/decryption).
 */
interface StreamCryptoProtocol {
    /**
     * Processes the input data and returns the transformed data.
     *
     * Implementations will handle encryption or decryption based on their initialization.
     * Unlike the original Swift protocol which used an `inout Data` parameter to modify
     * data in-place, this Kotlin interface expects the method to return a new ByteArray
     * containing the processed data. This is a more idiomatic approach in Kotlin/Java.
     *
     * @param data The input data to process.
     * @return A new ByteArray containing the encrypted or decrypted data.
     */
    fun update(data: ByteArray): ByteArray

    // Optional: Real-world stream cryptos often have a `doFinal()` or `final()` method
    // to handle any remaining buffered data and padding (though padding is less common for stream ciphers).
    // fun final(data: ByteArray? = null): ByteArray // Example
}
