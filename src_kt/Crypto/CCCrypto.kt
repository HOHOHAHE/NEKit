import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.slf4j.LoggerFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider

import com.example.nekit.Crypto.CryptoOperation // Corrected import
import com.example.nekit.Crypto.StreamCryptoProtocol // Corrected import


class CCCryptoAdapter(
    operation: CryptoOperation,
    mode: CCCryptoAdapter.Mode,
    algorithm: CCCryptoAdapter.Algorithm,
    initialVector: ByteArray?,
    key: ByteArray
) : StreamCryptoProtocol {

    private val logger = LoggerFactory.getLogger(CCCryptoAdapter::class.java)

    enum class Algorithm {
        AES, CAST, RC4; // Original names

        fun toJceAlgorithmName(): String {
            return when (this) {
                AES -> "AES"
                RC4 -> "ARCFOUR" // Or "RC4"
                CAST -> "CAST5"   // BouncyCastle provides CAST5. Standard JCE might not.
            }
        }
    }

    enum class Mode {
        CFB, RC4; // Original names. Note: RC4 is a stream cipher, mode might be implicit or "NONE"

        fun toJceModeString(): String {
            return when (this) {
                CFB -> "CFB"
                RC4 -> "NONE" // For stream ciphers like RC4, mode is often "NONE" or part of alg name.
            }
        }
    }

    private val cipher: Cipher

    init {
        // BouncyCastle provider is now registered globally in GlobalInitializer.
        // No need for individual registration here.

        val jceAlgorithmName = algorithm.toJceAlgorithmName()
        val jceModeString = mode.toJceModeString()
        // CommonCrypto used ccNoPadding. JCE equivalent is "NoPadding".
        val jcePaddingString = "NoPadding"

        // Construct the transformation string, e.g., "AES/CFB/NoPadding"
        // For RC4, it's typically just "ARCFOUR/NONE/NoPadding" or "RC4"
        val transformation = if (algorithm == Algorithm.RC4) {
            jceAlgorithmName // RC4 often doesn't specify mode/padding in the same way
        } else {
            "$jceAlgorithmName/$jceModeString/$jcePaddingString"
        }

        try {
            cipher = if (algorithm == Algorithm.CAST) {
                try {
                    // Attempt to use BouncyCastle for CAST5
                    val instance = Cipher.getInstance(transformation, BouncyCastleProvider.PROVIDER_NAME)
                    logger.info("Using BouncyCastle provider for CAST5 algorithm (transformation: {}).", transformation)
                    instance
                } catch (e: Exception) {
                    logger.warn("Failed to get CAST5 instance from BouncyCastle for transformation '{}': {}. Falling back to default JCE provider.", transformation, e.message)
                    // Fallback to default JCE provider if BC is not available or fails
                    Cipher.getInstance(transformation)
                }
            } else {
                Cipher.getInstance(transformation)
            }

            val secretKeySpec = SecretKeySpec(key, jceAlgorithmName)

            if (initialVector != null) {
                // IV is used for modes like CFB.
                // For RC4: Standard JCE RC4/ARCFOUR does not use an IV with Cipher.init().
                // If an IV is provided for RC4, it implies a custom key derivation scheme
                // (e.g., key = HASH(original_key + IV)) which should be performed *before*
                // this adapter is called. This adapter expects the final, ready-to-use key.
                // Shadowsocks RC4-MD5 handles this in CryptoStreamProcessor.
                if (algorithm == Algorithm.RC4) {
                    if (initialVector != null && jceModeString == "NONE") {
                        // This case is unusual for standard JCE RC4. The key should already be derived if IV was part of it.
                        logger.warn("RC4 algorithm (mode NONE) received an initialVector. Standard JCE RC4 typically does not use an IV directly in Cipher.init(). Ensure the provided key is the final key. IV will be ignored for RC4 in NONE mode if not used by provider implicitly.")
                        // Some providers might implicitly use IV for RC4 if passed, others error or ignore.
                        // To be safe and standard, if mode is NONE (typical for RC4), don't pass IV.
                        cipher.init(operation.toJceMode(), secretKeySpec)
                    } else if (jceModeString != "NONE") { // Should not happen for RC4 as configured
                        val ivSpec = IvParameterSpec(initialVector) // initialVector would be non-null here
                        cipher.init(operation.toJceMode(), secretKeySpec, ivSpec)
                    }
                    else { // initialVector is null and mode is NONE
                        cipher.init(operation.toJceMode(), secretKeySpec)
                    }
                } else { // For AES, CAST in CFB mode (CFB requires an IV)
                    val ivSpec = IvParameterSpec(initialVector) // initialVector must not be null here
                    cipher.init(operation.toJceMode(), secretKeySpec, ivSpec)
                }
            } else { // No IV provided
                // CFB mode requires an IV. If not provided, this will be an error for AES/CAST.
                // RC4 does not strictly require an IV.
                if (algorithm != Algorithm.RC4 && jceModeString != "NONE") {
                    throw IllegalArgumentException("IV is required for $transformation algorithm/mode but was null.")
                }
                cipher.init(operation.toJceMode(), secretKeySpec)
            }

        } catch (e: Exception) {
            // Wrap in a runtime exception or a custom crypto exception
            throw RuntimeException("Failed to initialize Cipher: ${e.message}", e)
        }
    }

    /**
     * Processes the input data.
     * Note: The Swift version updated data in-place. This version returns a new ByteArray.
     * If in-place update is critical, the method signature and implementation needs to change
     * to accept an output buffer.
     */
    override fun update(data: ByteArray): ByteArray {
        try {
            // cipher.update can return null if no data is output (e.g. buffering),
            // but for stream ciphers or NoPadding, it usually outputs something.
            // For simplicity, assuming it always outputs for the given configurations.
            // A more robust implementation would handle null return from cipher.update().
            return cipher.update(data) ?: ByteArray(0)
        } catch (e: Exception) {
            throw RuntimeException("Cipher update failed: ${e.message}", e)
        }
    }

    // No explicit deinit/release needed for JCE Cipher objects in most cases.
    // They are garbage collected. If a specific provider's SPI uses native resources
    // not managed by GC, it should implement AutoCloseable or a close() method.
}
