import java.security.SecureRandom
import java.security.MessageDigest // For MD5 in KDF

// Assuming CryptoAlgorithm.kt is in the same package or imported.
// Assuming MD5Hash.kt will provide a similar function, or using MessageDigest directly.

object CryptoHelper {

    // Corresponds to infoDictionary in Swift
    private val algorithmInfo: Map<CryptoAlgorithm, Pair<Int, Int>> = mapOf(
        CryptoAlgorithm.AES128_CFB to (16 to 16), // Key length to IV length
        CryptoAlgorithm.AES192_CFB to (24 to 16),
        CryptoAlgorithm.AES256_CFB to (32 to 16),
        CryptoAlgorithm.CHACHA20 to (32 to 12),   // Standard ChaCha20 (RFC 8439) uses a 12-byte nonce.
        CryptoAlgorithm.SALSA20 to (32 to 8),    // Salsa20 often uses an 8-byte nonce. (XSalsa20 uses 24)
        CryptoAlgorithm.RC4_MD5 to (16 to 16)    // Key length for RC4, and "IV" length for its KDF part.
    )

    fun getKeyLength(algorithm: CryptoAlgorithm): Int {
        return algorithmInfo[algorithm]?.first ?: throw IllegalArgumentException("Unknown algorithm: $algorithm for key length")
    }

    fun getIVLength(algorithm: CryptoAlgorithm): Int {
        // IV lengths are now updated based on common JCE/BouncyCastle or standard algorithm usage.
        // Specific variants (like XChaCha20 or XSalsa20) would need their own CryptoAlgorithm enum entries
        // if their IV sizes differ and need to be distinguished by this helper.
        return algorithmInfo[algorithm]?.second ?: throw IllegalArgumentException("Unknown algorithm: $algorithm for IV length")
    }

    /**
     * Generates a cryptographically secure random Initialization Vector (IV).
     */
    fun generateIV(algorithm: CryptoAlgorithm): ByteArray {
        val ivLength = getIVLength(algorithm)
        val iv = ByteArray(ivLength)
        SecureRandom().nextBytes(iv)
        return iv
    }

    // MD5 hashing logic using java.security.MessageDigest.
    private fun md5Hash(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("MD5").digest(data)
    }
    private fun md5Hash(str: String): ByteArray {
        return md5Hash(str.toByteArray(Charsets.UTF_8))
    }


    /**
     * Derives a key from a password using a custom MD5-based Key Derivation Function (KDF).
     * This is similar to the "evp_bytestokey" KDF used in OpenSSL (and older versions of some crypto protocols)
     * when a single round of MD5 is used.
     * result = MD5(password + prev_hash) + MD5(password + prev_hash + prev_hash) + ...
     * The Swift code is slightly different:
     * extendPasswordData = md5_0 + password
     * hash_1 = MD5(extendPasswordData)
     * extendPasswordData = hash_1 + password
     * hash_2 = MD5(extendPasswordData)
     * ...
     * And result is concatenation of hash_0, hash_1, ...
     * No, the Swift code is:
     *    result_buffer = empty
     *    md5_i = md5(password)
     *    temp_buffer = md5_i + password
     *    loop {
     *        copy md5_i to result_buffer
     *        md5_i = md5(temp_buffer) // (previous md5_i + password)
     *        temp_buffer = md5_i + password (new md5_i + password)
     *    }
     * This is not a standard KDF. Let's replicate the logic carefully.
     */
    fun deriveKey(password: String, algorithm: CryptoAlgorithm): ByteArray {
        val keyLength = getKeyLength(algorithm)
        // The Swift code's result buffer was "getIVLength + getKeyLength", but then it takes a subdata of getKeyLength.
        // This implies the KDF generates more bytes than needed for the key, or that the IV was also derived this way.
        // The return is only the key part. Let's assume the KDF needs to produce at least `keyLength` bytes.
        // The Swift code `result = Data(count: getIVLength(methodType) + getKeyLength(methodType))`
        // then `return result.subdata(in: 0..<getKeyLength(methodType))`
        // This is very strange. It means it calculates IV+Key then throws away IV.
        // For now, let's assume the goal is just to produce `keyLength` bytes.
        // If the IV is also derived this way, the calling code would need to handle that.
        // The KDF in Swift is:
        // md5_0 = MD5(password)
        // result = md5_0
        // temp = md5_0 + password_bytes
        // md5_1 = MD5(temp)
        // result += md5_1
        // temp = md5_1 + password_bytes
        // ... until result is long enough.

val derivedKeyMaterial = java.io.ByteArrayOutputStream()
        val passwordBytes = password.toByteArray(Charsets.UTF_8)

        var currentMD5 = md5Hash(passwordBytes) // md5_0

        while (derivedKeyMaterial.size() < keyLength) {
            derivedKeyMaterial.write(currentMD5)
            // Prepare data for next hash: currentMD5 + passwordBytes
            val temp = ByteArrayOutputStream()
            temp.write(currentMD5)
            temp.write(passwordBytes)
            currentMD5 = md5Hash(temp.toByteArray())
        }

        return derivedKeyMaterial.toByteArray().copyOfRange(0, keyLength)
    }
}
