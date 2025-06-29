package Crypto
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import Crypto.CryptoEnum.HashAlgorithm // Import HashAlgorithm

object HMAC {

    fun final(value: String, algorithm: HashAlgorithm, key: ByteArray): ByteArray {
        val data = value.toByteArray(StandardCharsets.UTF_8)
        return final(data, algorithm, key)
    }

    fun final(data: ByteArray, algorithm: HashAlgorithm, key: ByteArray): ByteArray {
        try {
            val hmacJceName = algorithm.hmacJceName // e.g., "HmacSHA256"
            val mac = Mac.getInstance(hmacJceName)
            // Note: The algorithm name for SecretKeySpec should match the Mac algorithm type (e.g. "HmacSHA256"),
            // not just the hash (e.g. "SHA256"). JCE can be picky.
            // However, often just passing the hmacJceName works for the key algorithm.
            val secretKey = SecretKeySpec(key, hmacJceName)
            mac.init(secretKey)
            return mac.doFinal(data)
        } catch (e: Exception) {
            // Wrap in a runtime exception or a custom crypto exception
            throw RuntimeException("HMAC computation failed: ${e.message}", e)
        }
    }

    /**
     * Computes HMAC for a portion of a ByteArray.
     * The original Swift version took UnsafeRawPointer and length.
     * This version takes a ByteArray and offsets for more safety in Kotlin.
     *
     * @param data The input byte array.
     * @param offset The offset in the data array to start from.
     * @param length The number of bytes to process from the data array.
     * @param algorithm The hash algorithm to use.
     * @param key The secret key for HMAC.
     * @return The computed HMAC value as a ByteArray.
     */
    fun final(data: ByteArray, offset: Int, length: Int, algorithm: HashAlgorithm, key: ByteArray): ByteArray {
        try {
            if (offset < 0 || length < 0 || offset + length > data.size) {
                throw IndexOutOfBoundsException("Invalid offset or length for HMAC computation.")
            }
            val hmacJceName = algorithm.hmacJceName
            val mac = Mac.getInstance(hmacJceName)
            val secretKey = SecretKeySpec(key, hmacJceName)
            mac.init(secretKey)
            mac.update(data, offset, length)
            return mac.doFinal()
        } catch (e: Exception) {
            throw RuntimeException("HMAC computation failed: ${e.message}", e)
        }
    }
}
