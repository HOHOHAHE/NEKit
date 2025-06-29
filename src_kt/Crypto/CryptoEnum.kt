package com.example.nekit.Crypto

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
enum class CryptoAlgorithm(val rawValue: String, val requiresBouncyCastle: Boolean = false) {
    AES128_CFB("AES-128-CFB"),
    AES192_CFB("AES-192-CFB"),
    AES256_CFB("AES-256-CFB"),
    CHACHA20("CHACHA20", requiresBouncyCastle = true),
    SALSA20("SALSA20", requiresBouncyCastle = true),
    RC4_MD5("RC4-MD5", requiresBouncyCastle = true); // RC4 itself might need BC for consistent availability

    // getJceTransformation is less relevant for stream ciphers if they don't use mode/padding strings.
    // It's primarily used by CCCryptoAdapter which handles block ciphers with modes like CFB.
    // For stream ciphers, the algorithm name itself is often the transformation.
    fun getJceTransformation(mode: String = "NONE", padding: String = "NoPadding"): String {
        return when (this) {
            AES128_CFB, AES192_CFB, AES256_CFB -> "AES/$mode/$padding" // Assuming mode is CFB as per CCCryptoAdapter
            CHACHA20 -> this.getJceAlgorithmName() // e.g., "ChaCha7539"
            SALSA20 -> this.getJceAlgorithmName()  // e.g., "Salsa20"
            RC4_MD5 -> this.getJceAlgorithmName()   // e.g., "RC4"
        }
    }

    fun getJceAlgorithmName(): String {
        return when (this) {
            AES128_CFB, AES192_CFB, AES256_CFB -> "AES"
            CHACHA20 -> "ChaCha7539" // BouncyCastle name for ChaCha20 stream cipher
            SALSA20 -> "Salsa20"     // BouncyCastle name
            RC4_MD5 -> "RC4"         // BouncyCastle uses "RC4", standard JCE might use "ARCFOUR"
                                     // MD5 part is for key derivation, handled separately.
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
