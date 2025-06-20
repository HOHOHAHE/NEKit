import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
// BouncyCastle might be needed for some algorithms/modes if not in default JCE
// import org.bouncycastle.jce.provider.BouncyCastleProvider

// --- Placeholder for CryptoEnum.CryptoOperation ---
// This should be defined in its own file: CryptoEnum.kt
enum class CryptoOp { // Renamed to avoid conflict if CryptoOperation is a class/interface
    ENCRYPT, DECRYPT;

    fun toJceMode(): Int {
        return when (this) {
            ENCRYPT -> Cipher.ENCRYPT_MODE
            DECRYPT -> Cipher.DECRYPT_MODE
        }
    }
}
// --- End Placeholder ---

// --- Placeholder for StreamCryptoProtocol.swift ---
// This should be defined in its own file: StreamCryptoProtocol.kt
interface StreamCrypto { // Renamed to avoid conflict
    fun update(data: ByteArray): ByteArray // Assuming it returns new data
    // fun final(): ByteArray // Often present, but not in the provided Swift
}
// --- End Placeholder ---


class CCCryptoAdapter(
    operation: CryptoOp,
    mode: CCCryptoAdapter.Mode,
    algorithm: CCCryptoAdapter.Algorithm,
    initialVector: ByteArray?,
    key: ByteArray
) : StreamCrypto {

    enum class Algorithm {
        AES, CAST, RC4; // Original names

        fun toJceAlgorithmName(): String {
            return when (this) {
                AES -> "AES"
                RC4 -> "ARCFOUR" // Or "RC4"
                CAST -> "CAST5"   // Or "CAST6". TODO: Verify specific CAST version or use BouncyCastle
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
        // Optional: Add BouncyCastle provider if needed for algorithms like CAST or specific modes
        // if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        //     Security.addProvider(BouncyCastleProvider())
        // }

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
            cipher = Cipher.getInstance(transformation)
            // TODO: Check if BouncyCastle is needed for "CAST5/CFB/NoPadding" or other specific transformations.
            // Provider can be specified: Cipher.getInstance(transformation, "BC")

            val secretKeySpec = SecretKeySpec(key, jceAlgorithmName)

            if (initialVector != null) {
                // IV is used for modes like CFB.
                // RC4 typically doesn't use an IV in JCE Cipher.init(), or it's handled differently.
                // CommonCrypto's RC4 with an IV might be a specific variant.
                if (algorithm == Algorithm.RC4) {
                    // TODO: Handle RC4 with IV if it's a non-standard key setup.
                    // JCE RC4 cipher.init usually only takes key. Some implementations might mix IV into key.
                    // For now, assume standard JCE RC4 init if IV is passed for RC4.
                    // This might mean a custom key derivation if IV is used for RC4 keying.
                    // Or, if the IV is for a specific RC4 variant not directly supported by JCE default.
                    // One common way is to hash key+IV to form the actual RC4 key.
                    // For now, let's try to pass it if it's not AES/CAST in CFB mode
                     if (jceModeString != "NONE") { // Only use IV if mode expects it
                        val ivSpec = IvParameterSpec(initialVector)
                        cipher.init(operation.toJceMode(), secretKeySpec, ivSpec)
                    } else {
                         // Potentially a warning or error if IV provided for RC4 in NONE mode
                        cipher.init(operation.toJceMode(), secretKeySpec)
                    }
                } else { // For AES, CAST in CFB mode
                    val ivSpec = IvParameterSpec(initialVector)
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
