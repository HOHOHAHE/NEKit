import java.lang.ref.WeakReference
// Assuming necessary imports from Crypto module (CryptoAlgorithm, CryptoOperation, StreamCryptoProtocol,
// CCCryptoAdapter, SodiumStreamCryptoAdapter, MD5Hash, CryptoHelper)
// Assuming Buffer.kt from Utils module.
// Assuming placeholders for obfuscators are available.

// --- Placeholders for Obfuscator components (should be in their respective files) ---
// These were part of ShadowsocksAdapterNested in ShadowsocksAdapterFactory.kt context
// interface ProtocolObfuscater { fun output(data: ByteArray): ByteArray } // Simplified
// interface StreamObfuscater { fun input(data: ByteArray) } // Simplified

// Let's refine based on usage: inputStreamProcessor: StreamObfuscater.StreamObfuscaterBase!
// outputStreamProcessor: ProtocolObfuscater.ProtocolObfuscaterBase!
// This implies these are interfaces or base classes.
object ShadowsocksAdapterComps { // Using a different name to avoid conflict with potential ShadowsocksAdapter class
    interface StreamObfuscaterBase { // Renamed from StreamObfuscater to avoid conflict with the interface in Factory
        fun input(data: ByteArray) // throws Exception in Swift, add if needed
    }
    interface ProtocolObfuscaterBase {
        fun output(data: ByteArray)
    }
}
// --- End Placeholders ---


/**
 * Factory for creating [ShadowsocksCryptoProcessor] instances.
 * Configured with a password and algorithm, it derives the encryption key.
 */
class ShadowsocksCryptoProcessorFactory(
    password: String,
    private val algorithm: CryptoAlgorithm
) {
    private val key: ByteArray = CryptoHelper.deriveKey(password, algorithm) // Use deriveKey from CryptoHelper

    fun build(): ShadowsocksCryptoProcessor {
        return ShadowsocksCryptoProcessor(key, algorithm)
    }
}

/**
 * Handles encryption and decryption for a Shadowsocks stream.
 * It manages IVs (Initialization Vectors) and interacts with underlying stream crypto implementations.
 */
class ShadowsocksCryptoProcessor(
    private val key: ByteArray,
    private val algorithm: CryptoAlgorithm
) {
    // Weak references to other processors in the chain (if any)
    var inputStreamProcessor: WeakReference<ShadowsocksAdapterComps.StreamObfuscaterBase?> = WeakReference(null)
    var outputStreamProcessor: WeakReference<ShadowsocksAdapterComps.ProtocolObfuscaterBase?> = WeakReference(null)

    private var readIV: ByteArray? = null
    private var ivSent: Boolean = false // Was sendKey in Swift

    private val processingBuffer: Buffer = Buffer(initialCapacity = CryptoHelper.getIVLength(algorithm) + 1024) // Initial capacity for IV + some data

    private val writeIV: ByteArray by lazy { CryptoHelper.generateIV(algorithm) }
    private val ivLength: Int by lazy { CryptoHelper.getIVLength(algorithm) }

    // Lazy initialization for encryptor and decryptor.
    // Note: These are stateful if readIV/writeIV changes or if the crypto itself is stateful after init.
    // For stream ciphers like ChaCha20 or AES-CFB, they are stateful with the IV.
    private val encryptor: StreamCryptoProtocol by lazy { getCrypto(CryptoOperation.ENCRYPT) }
    private val decryptor: StreamCryptoProtocol by lazy {
        // Decryptor should only be initialized once readIV is available.
        // This lazy init might be problematic if readIV is not set before first decryption.
        // The input() method handles readIV initialization before decryption.
        if (readIV == null) throw IllegalStateException("readIV must be set before initializing decryptor.")
        getCrypto(CryptoOperation.DECRYPT)
    }
    // Re-evaluate lazy for decryptor: it depends on readIV which is set late.
    // Make it nullable and initialize on demand, or ensure readIV is always set before first use.
    private var _decryptorInstance: StreamCryptoProtocol? = null


    /**
     * Processes incoming encrypted data from the network.
     * Handles IV extraction for the first packet, then decrypts and forwards to the input stream processor.
     * @param data Encrypted data received from the network.
     */
    @Throws(Exception::class) // Matches Swift's `throws` on inputStreamProcessor.input
    fun input(data: ByteArray) {
        var currentData = data
        if (readIV == null) {
            processingBuffer.append(currentData)
            readIV = processingBuffer.get(ivLength) // Attempt to get IV
            if (readIV == null) {
                // Not enough data for IV yet, keep buffering.
                // The Swift code `try inputStreamProcessor!.input(data: Data())` seems to imply
                // that if IV is not complete, it might still call the next processor with empty data.
                // This is unusual. For now, just buffer and wait for more data.
                println("INFO: ShadowsocksCryptoProcessor: Buffering data, waiting for full IV ($ivLength bytes). Have ${processingBuffer.count} bytes.")
                return // Wait for more data
            }
            // IV successfully read
            currentData = processingBuffer.getAll() ?: ByteArray(0) // Get remaining data after IV
            processingBuffer.release() // Clear buffer
            println("INFO: ShadowsocksCryptoProcessor: Read IV (${readIV!!.size} bytes). Remaining data: ${currentData.size} bytes.")
            // Initialize decryptor now that readIV is available
            _decryptorInstance = getCrypto(CryptoOperation.DECRYPT)
        }

        val currentDecryptor = _decryptorInstance ?: throw IllegalStateException("Decryptor not initialized, readIV was null.")

        // Perform decryption
        // Assuming StreamCryptoProtocol.update returns new ByteArray.
        // TODO: Optimize for in-place decryption if StreamCryptoProtocol supports it to avoid copies.
        val decryptedData = currentDecryptor.update(currentData)

        // Pass decrypted data to the next processor in the chain (e.g., input stream obfuscater)
        inputStreamProcessor.get()?.input(decryptedData)
            ?: println("WARN: ShadowsocksCryptoProcessor: inputStreamProcessor is null, decrypted data not forwarded.")
    }

    /**
     * Processes outgoing plaintext data to be sent to the network.
     * Encrypts the data, prepends the IV if not already sent, and forwards to the output stream processor.
     * @param data Plaintext data to be encrypted and sent.
     */
    fun output(data: ByteArray) {
        // TODO: Optimize for in-place encryption if StreamCryptoProtocol supports it.
        var dataToEncrypt = data
        val encryptedData = encryptor.update(dataToEncrypt)

        val dataToSend: ByteArray
        if (!ivSent) {
            ivSent = true
            dataToSend = ByteArray(writeIV.size + encryptedData.size)
            System.arraycopy(writeIV, 0, dataToSend, 0, writeIV.size)
            System.arraycopy(encryptedData, 0, dataToSend, writeIV.size, encryptedData.size)
            println("INFO: ShadowsocksCryptoProcessor: Sending IV (${writeIV.size} bytes) + encrypted data (${encryptedData.size} bytes).")
        } else {
            dataToSend = encryptedData
        }

        // Pass encrypted data (with IV if it was the first packet) to the next processor (e.g., output protocol obfuscater)
        outputStreamProcessor.get()?.output(dataToSend)
            ?: println("WARN: ShadowsocksCryptoProcessor: outputStreamProcessor is null, encrypted data not sent.")
    }


    private fun getCrypto(operation: CryptoOperation): StreamCryptoProtocol {
        // Ensure IVs are set for operations that need them, especially relevant for decryptor
        val currentReadIV = if (operation == CryptoOperation.DECRYPT) this.readIV else null
        val currentWriteIV = if (operation == CryptoOperation.ENCRYPT) this.writeIV else null // writeIV is lazy

        // TODO: Ensure CCCryptoAdapter and SodiumStreamCryptoAdapter are correctly translated and available.
        return when (algorithm) {
            CryptoAlgorithm.AES128_CFB, CryptoAlgorithm.AES192_CFB, CryptoAlgorithm.AES256_CFB -> {
                val iv = if (operation == CryptoOperation.DECRYPT) currentReadIV else currentWriteIV
                if (iv == null && operation == CryptoOperation.DECRYPT && readIV == null) { // Should not happen if input() logic is correct
                    throw IllegalStateException("AES decryptor cannot be created without readIV.")
                }
                // Assuming CCCryptoAdapter.Algorithm.AES matches the intent for CryptoAlgorithm.AESxxxCFB
                CCCryptoAdapter(operation, CCCryptoAdapter.Mode.CFB, CCCryptoAdapter.Algorithm.AES, iv, key)
            }
            CryptoAlgorithm.CHACHA20 -> {
                val iv = if (operation == CryptoOperation.DECRYPT) currentReadIV else currentWriteIV
                 if (iv == null && operation == CryptoOperation.DECRYPT && readIV == null) {
                    throw IllegalStateException("ChaCha20 decryptor cannot be created without readIV.")
                }
                SodiumStreamCryptoAdapter(key, iv!!, SodiumStreamCryptoAdapter.Algorithm.CHACHA20, operation)
            }
            CryptoAlgorithm.SALSA20 -> {
                val iv = if (operation == CryptoOperation.DECRYPT) currentReadIV else currentWriteIV
                 if (iv == null && operation == CryptoOperation.DECRYPT && readIV == null) {
                    throw IllegalStateException("Salsa20 decryptor cannot be created without readIV.")
                }
                SodiumStreamCryptoAdapter(key, iv!!, SodiumStreamCryptoAdapter.Algorithm.SALSA20, operation)
            }
            CryptoAlgorithm.RC4_MD5 -> {
                // Custom key derivation for RC4-MD5: K = MD5(key + IV)
                val iv = if (operation == CryptoOperation.DECRYPT) currentReadIV else currentWriteIV
                 if (iv == null && operation == CryptoOperation.DECRYPT && readIV == null) {
                    throw IllegalStateException("RC4-MD5 decryptor cannot be created without readIV.")
                }
                val combinedKeyMaterial = ByteArray(key.size + iv!!.size)
                System.arraycopy(key, 0, combinedKeyMaterial, 0, key.size)
                System.arraycopy(iv, 0, combinedKeyMaterial, key.size, iv.size)
                val finalKey = MD5Hash.final(combinedKeyMaterial) // Assuming MD5Hash.kt
                // RC4 in CommonCrypto might not use an IV directly in CCCryptorCreateWithMode if key is already derived.
                CCCryptoAdapter(operation, CCCryptoAdapter.Mode.RC4, CCCryptoAdapter.Algorithm.RC4, null, finalKey)
            }
            // else -> throw IllegalArgumentException("Unsupported algorithm for ShadowsocksCryptoProcessor: $algorithm")
        }
    }
}

// Placeholder for SodiumStreamCryptoAdapter, assuming it's similar to CCCryptoAdapter but for Libsodium stream ciphers
// TODO: This needs to be properly translated from SodiumStreamCrypto.swift
private class SodiumStreamCryptoAdapter(
    key: ByteArray,
    iv: ByteArray,
    algorithm: Algorithm,
    operation: CryptoOperation // Added operation to distinguish encrypt/decrypt setup if needed by underlying
) : StreamCrypto {
    enum class Algorithm { CHACHA20, SALSA20 }
    init {
        println("INFO: SodiumStreamCryptoAdapter (Placeholder) created for ${algorithm.name}, op: $operation. KeyLen: ${key.size}, IVLen: ${iv.size}")
        // TODO: Actual LibSodium/JNI/JCE setup here
    }
    override fun update(data: ByteArray): ByteArray {
        println("INFO: SodiumStreamCryptoAdapter (Placeholder) update called for ${data.size} bytes. Op: $operation")
        // TODO: Actual crypto op
        return data.copyOf() // Placeholder: passthrough
    }
}
