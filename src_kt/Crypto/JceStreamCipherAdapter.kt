import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider // For BouncyCastle specific algorithms

// Assuming StreamCrypto.kt (for StreamCrypto interface) and CryptoEnum.kt (for CryptoOperation, CryptoAlgorithm) are available.

/**
 * Adapter for stream ciphers (like ChaCha20, Salsa20, RC4) using JCE and BouncyCastle.
 * This class replaces the JNI-based SodiumStreamCrypto.
 */
class JceStreamCipherAdapter(
    private val operation: CryptoOperation,
    private val algorithm: CryptoAlgorithm,
    private val key: ByteArray,
    private val iv: ByteArray // Initialization Vector or Nonce
) : StreamCrypto {

    private val logger = LoggerFactory.getLogger(JceStreamCipherAdapter::class.java)
    private val cipher: Cipher

    init {
        val jceAlgorithmName = algorithm.getJceAlgorithmName()
        // For stream ciphers, transformation string is often just the algorithm name,
        // or Algorithm/Mode/Padding where Mode is NONE and Padding is NoPadding.
        // The CryptoAlgorithm.getJceTransformation() now provides this.
        val transformation = algorithm.getJceTransformation() // Defaults to mode NONE, padding NoPadding for stream ciphers

        try {
            cipher = if (algorithm.requiresBouncyCastle) {
                try {
                    val instance = Cipher.getInstance(transformation, BouncyCastleProvider.PROVIDER_NAME)
                    logger.info("Using BouncyCastle provider for {} (transformation: {}).", algorithm.rawValue, transformation)
                    instance
                } catch (e: Exception) {
                    logger.warn("Failed to get {} instance from BouncyCastle for transformation '{}': {}. Falling back to default JCE provider.",
                        algorithm.rawValue, transformation, e.message)
                    Cipher.getInstance(transformation) // Fallback
                }
            } else {
                Cipher.getInstance(transformation)
            }

            val secretKeySpec = SecretKeySpec(key, jceAlgorithmName) // Use base alg name for SecretKeySpec (e.g., "ChaCha7539", "Salsa20", "RC4")
            val ivSpec = IvParameterSpec(iv)

            // Initialize the cipher.
            // Note: Some ciphers like RC4 in some JCE providers might not use an IV directly in init,
            // or it's mixed into the key. This setup assumes standard IV usage for stream ciphers like ChaCha20/Salsa20 with IvParameterSpec.
            // If algorithm is RC4 and IV handling is custom (e.g. key+IV hashed to form final key),
            // that custom key derivation should happen before this class and the derived key passed in.
            // The current CryptoAlgorithm.RC4_MD5 implies this by its name, and ShadowsocksCryptoProcessor handles it.
            cipher.init(operation.toJceMode(), secretKeySpec, ivSpec)
            logger.info("JceStreamCipherAdapter initialized for {} operation with algorithm {}.", operation, algorithm.rawValue)

        } catch (e: Exception) {
            logger.error("Failed to initialize Cipher for algorithm {}: {}", algorithm.rawValue, e.message, e)
            throw RuntimeException("Failed to initialize Cipher for ${algorithm.rawValue}: ${e.message}", e)
        }
    }

    /**
     * Encrypts or decrypts data using the configured stream cipher.
     *
     * @param data The input data to process.
     * @return The processed (encrypted or decrypted) data.
     */
    override fun update(data: ByteArray): ByteArray {
        if (data.isEmpty()) {
            return ByteArray(0)
        }
        try {
            // cipher.update for stream ciphers typically returns the processed data immediately.
            // It can return null if no data is output, but for stream ciphers this is less common
            // unless there's internal buffering not typical for simple update calls.
            return cipher.update(data) ?: ByteArray(0).also {
                logger.warn("Cipher.update returned null for algorithm {}, op {}. This might be unexpected for a stream cipher.", algorithm.rawValue, operation)
            }
        } catch (e: Exception) {
            logger.error("Cipher update failed for algorithm {}, op {}: {}", algorithm.rawValue, operation, e.message, e)
            throw RuntimeException("Cipher update failed for ${algorithm.rawValue}, op $operation: ${e.message}", e)
        }
    }

    // Stream ciphers usually don't have a "final" block like block ciphers in padding modes.
    // If cipher.doFinal() were needed, it would be a separate method.
    // The StreamCrypto interface only defines update().
}
