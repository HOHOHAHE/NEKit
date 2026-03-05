package nekit.Crypto
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

object MD5Hash {

    /**
     * Computes the MD5 hash of a String.
     * The string is first encoded to UTF-8 bytes.
     *
     * @param value The string to hash.
     * @return The MD5 hash as a ByteArray.
     */
    fun final(value: String): ByteArray {
        val data = value.toByteArray(StandardCharsets.UTF_8)
        return final(data)
    }

    /**
     * Computes the MD5 hash of a ByteArray.
     *
     * @param data The byte array to hash.
     * @return The MD5 hash as a ByteArray.
     */
    fun final(data: ByteArray): ByteArray {
        try {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(data)
        } catch (e: Exception) {
            // Wrap in a runtime exception or a custom crypto exception
            // NoSuchAlgorithmException is the primary one to expect here, though unlikely for MD5.
            throw RuntimeException("MD5 hashing failed: ${e.message}", e)
        }
    }

    /**
     * The length of an MD5 digest in bytes (16 bytes).
     */
    const val DIGEST_LENGTH: Int = 16 // CC_MD5_DIGEST_LENGTH is 16
}
