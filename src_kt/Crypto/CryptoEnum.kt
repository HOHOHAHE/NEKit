import java.security.MessageDigest // For digestLength
import javax.crypto.Cipher        // For ENCRYPT_MODE, DECRYPT_MODE

enum class CryptoOperation {
    ENCRYPT, DECRYPT;

    fun toJceMode(): Int {
        return when (this) {
            ENCRYPT -> Cipher.ENCRYPT_MODE
            DECRYPT -> Cipher.DECRYPT_MODE
        }
    }
}

/**
 * Defines symmetric encryption algorithms.
 * The rawValue corresponds to the original Swift string, for reference or specific lookup if needed.
 * JCE names are provided where standard. Others may require BouncyCastle or custom implementation.
 */
enum class CryptoAlgorithm(val rawValue: String) {
    AES128_CFB("AES-128-CFB"), // Key length (128) is determined by the key, JCE alg is "AES/CFB/NoPadding"
    AES192_CFB("AES-192-CFB"), // Key length (192) is determined by the key, JCE alg is "AES/CFB/NoPadding"
    AES256_CFB("AES-256-CFB"), // Key length (256) is determined by the key, JCE alg is "AES/CFB/NoPadding"
    CHACHA20("CHACHA20"),     // Requires BouncyCastle or JDK 11+ (ChaCha20-Poly1305)
    SALSA20("SALSA20"),       // Requires BouncyCastle
    RC4_MD5("RC4-MD5");       // Custom; RC4 part is "ARCFOUR" or "RC4". MD5 part likely for KDF or stream modification.

    fun getJceTransformation(padding: String = "NoPadding"): String { // Assuming NoPadding as common case from CCCrypto
        return when (this) {
            AES128_CFB, AES192_CFB, AES256_CFB -> "AES/CFB/$padding"
            // CHACHA20, SALSA20, RC4_MD5 require specific handling, often without this kind of transformation string.
            // For CHACHA20, JCE standard might be "ChaCha20-Poly1305" or "ChaCha20".
            // BouncyCastle might use "ChaCha7539" for the base ChaCha20 stream cipher.
            CHACHA20 -> "ChaCha20" // TODO: Verify JCE name or use BouncyCastle "ChaCha7539"
            SALSA20 -> "Salsa20"   // TODO: Verify JCE name or use BouncyCastle
            RC4_MD5 -> "ARCFOUR"   // TODO: RC4-MD5 is custom. Base is ARCFOUR. MD5 part needs separate handling.
        }
    }
     fun getJceAlgorithmName(): String {
        return when (this) {
            AES128_CFB, AES192_CFB, AES256_CFB -> "AES"
            CHACHA20 -> "ChaCha20" // Or "ChaCha7539" with BouncyCastle
            SALSA20 -> "Salsa20"
            RC4_MD5 -> "ARCFOUR" // Or "RC4"
        }
    }
}

enum class HashAlgorithm {
    MD5, SHA1, SHA224, SHA256, SHA384, SHA512;

    /**
     * Returns the JCE algorithm name for use with `java.security.MessageDigest`.
     */
    val jceName: String
        get() = when (this) {
            MD5 -> "MD5"
            SHA1 -> "SHA-1"
            SHA224 -> "SHA-224"
            SHA256 -> "SHA-256"
            SHA384 -> "SHA-384"
            SHA512 -> "SHA-512"
        }

    /**
     * Returns the JCE algorithm name for use with `javax.crypto.Mac` (e.g., "HmacSHA256").
     */
    val hmacJceName: String
        get() = when (this) {
            MD5 -> "HmacMD5"
            SHA1 -> "HmacSHA1"
            SHA224 -> "HmacSHA224"
            SHA256 -> "HmacSHA256"
            SHA384 -> "HmacSHA384"
            SHA512 -> "HmacSHA512"
        }

    /**
     * Returns the digest length in bytes.
     */
    val digestLength: Int
        get() = try {
            // MessageDigest instances are not expensive to create for this purpose.
            MessageDigest.getInstance(this.jceName).digestLength
        } catch (e: Exception) {
            // Should not happen for standard algorithms
            when (this) { // Fallback to known constants if getInstance fails for some reason
                MD5 -> 16
                SHA1 -> 20
                SHA224 -> 28
                SHA256 -> 32
                SHA384 -> 48
                SHA512 -> 64
            }
        }
}
