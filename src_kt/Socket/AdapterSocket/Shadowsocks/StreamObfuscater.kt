import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.random.Random // For HTTP Obfuscater random selection
import java.io.ByteArrayOutputStream // Added missing import
import org.slf4j.LoggerFactory // Added import

// Assuming ConnectSession.kt (Messages), IPAddress.kt (Utils), Port.kt (Utils),
// ShadowsocksCryptoProcessor.kt (placeholder), ShadowsocksAdapter.kt (placeholder for interface),
// HMAC.kt (Crypto), HashAlgorithm.kt (Crypto), RandomUtils.kt (Utils) are available.

// --- Placeholder Interfaces/Classes (ensure consistency) ---

// Interface for what StreamObfuscater expects from ShadowsocksAdapter (inputStreamProcessor)
interface ShadowsocksAdapterStreamFeedback {
    fun input(data: ByteArray) // Data from StreamObfuscater (de-obfuscated) to Adapter
}

// Interface for what StreamObfuscater expects from CryptoStreamProcessor (outputStreamProcessor)
interface CryptoProcessorStreamOutput {
    val key: ByteArray?
    val writeIV: ByteArray? // This is the IV for the current encryption stream
    fun output(data: ByteArray) // Data from StreamObfuscater (to be encrypted) to CryptoStreamProcessor
}

// --- End Placeholders ---


// Base Factory for Stream Obfuscators
open class ShadowsocksStreamObfuscaterFactoryBase {
    open fun build(session: ConnectSession): ShadowsocksStreamObfuscaterBase =
        ShadowsocksStreamObfuscaterBase(session)
}

// Base class for Stream Obfuscators
open class ShadowsocksStreamObfuscaterBase(val session: ConnectSession) {
    open var inputStreamProcessorRef: WeakReference<ShadowsocksAdapterStreamFeedback?> = WeakReference(null)
    private var _outputStreamProcessorRef: WeakReference<CryptoProcessorStreamOutput?> = WeakReference(null)

    // Custom setter to also grab key and writeIV when outputStreamProcessor is set
    open var outputStreamProcessor: CryptoProcessorStreamOutput?
        get() = _outputStreamProcessorRef.get()
        set(value) {
            _outputStreamProcessorRef = WeakReference(value)
            // When crypto processor is set, capture its key and current writeIV for OTA HMAC
            key = value?.key
            writeIV = value?.writeIV
        }

    // These are copied from the CryptoStreamProcessor when it's set
    protected var key: ByteArray? = null
    protected var writeIV: ByteArray? = null


    @Throws(Exception::class)
    open fun input(data: ByteArray) { // Data from Crypto (decrypted), to be de-obfuscated
        inputStreamProcessorRef.get()?.input(data)
    }

    open fun output(data: ByteArray) { // Data from Local, to be obfuscated then passed to Crypto
        outputStreamProcessor?.output(data)
    }
}

// --- Origin Stream Obfuscator (Handles Shadowsocks addressing) ---
class ShadowsocksOriginStreamObfuscaterFactory : ShadowsocksStreamObfuscaterFactoryBase() {
    override fun build(session: ConnectSession): ShadowsocksStreamObfuscaterBase =
        ShadowsocksOriginStreamObfuscater(session)
}

class ShadowsocksOriginStreamObfuscater(session: ConnectSession) : ShadowsocksStreamObfuscaterBase(session) {
    private var requestSent = false

    private fun formatRequestData(initialData: ByteArray): ByteArray {
        val host = session.host
        val port = session.port.toUShort() // Ensure port is UShort for network order

        val hostBytes: ByteArray
        val atyp: Byte

        val parsedIp = IPAddress.parse(host)
        if (parsedIp != null) {
            if (parsedIp.isIPv4) {
                atyp = SOCKS5_ATYP_IPV4 // Using SOCKS5 ATYP constants for address type
                hostBytes = parsedIp.addressBytes
            } else {
                atyp = SOCKS5_ATYP_IPV6
                hostBytes = parsedIp.addressBytes
            }
        } else { // Domain name
            atyp = SOCKS5_ATYP_DOMAINNAME
            val domainData = host.toByteArray(StandardCharsets.UTF_8) // Typically UTF-8 for domains
            if (domainData.size > 255) throw IllegalArgumentException("Domain name too long for Shadowsocks addressing.")
            // Format for domain: [len][domain_string]
            hostBytes = ByteArray(1 + domainData.size)
            hostBytes[0] = domainData.size.toByte()
            System.arraycopy(domainData, 0, hostBytes, 1, domainData.size)
        }

        // Request format: ATYP (1) | DST.ADDR (var) | DST.PORT (2) | UserData (var)
        val headerSize = 1 + hostBytes.size + 2
        val buffer = ByteBuffer.allocate(headerSize + initialData.size)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put(atyp)
        buffer.put(hostBytes)
        buffer.putShort(port.toShort())
        buffer.put(initialData)
        return buffer.array()
    }

    override fun output(data: ByteArray) { // Data from Local, to be obfuscated then passed to Crypto
        if (requestSent) {
            super.output(data) // Pass through to CryptoStreamProcessor
        } else {
            requestSent = true
            val requestWithHeader = formatRequestData(data)
            super.output(requestWithHeader)
        }
    }
    // input() uses base class pass-through behavior (no de-obfuscation for origin stream)
}


// --- OTA (One-Time Authentication) Stream Obfuscator ---
class ShadowsocksOTAStreamObfuscaterFactory : ShadowsocksStreamObfuscaterFactoryBase() {
    override fun build(session: ConnectSession): ShadowsocksStreamObfuscaterBase =
        ShadowsocksOTAStreamObfuscater(session)
}

class ShadowsocksOTAStreamObfuscater(session: ConnectSession) : ShadowsocksStreamObfuscaterBase(session) {
    private val otaLogger = LoggerFactory.getLogger(ShadowsocksOTAStreamObfuscater::class.java)
    private var chunkCount: UInt = 0u // Corresponds to `count: UInt32` in Swift
    private var requestSent = false

    companion object {
        // Max payload size in one OTA chunk. 0xFFFF (max UDP) - 12 (OTA overhead: len + hmac)
        const val DATA_BLOCK_SIZE = 0xFFFF - 12 - 2 // -2 for length field itself
    }

    private fun formatOTAAddressHeader(): ByteArray {
        // [ATYP (1) | LEN (1, if domain) | ADDR (var) | PORT (2) | HMAC_SHA1 (10)]
        val host = session.host
        val port = session.port.toUShort()

        val addrHeaderPayload: ByteArray // ATYP | LEN (if domain) | ADDR | PORT
        val atyp: Byte
        val hostBytesForAddrHeader: ByteArray

        val parsedIp = IPAddress.parse(host)
        if (parsedIp != null) {
            if (parsedIp.isIPv4) {
                atyp = SOCKS5_ATYP_IPV4
                hostBytesForAddrHeader = parsedIp.addressBytes
            } else {
                atyp = SOCKS5_ATYP_IPV6
                hostBytesForAddrHeader = parsedIp.addressBytes
            }
            val tempBuffer = ByteBuffer.allocate(1 + hostBytesForAddrHeader.size + 2)
            tempBuffer.order(ByteOrder.BIG_ENDIAN)
            tempBuffer.put(atyp)
            tempBuffer.put(hostBytesForAddrHeader)
            tempBuffer.putShort(port.toShort())
            addrHeaderPayload = tempBuffer.array()
        } else { // Domain name
            atyp = SOCKS5_ATYP_DOMAINNAME
            val domainData = host.toByteArray(StandardCharsets.UTF_8)
            if (domainData.size > 255) throw IllegalArgumentException("Domain name too long for OTA header.")

            val tempBuffer = ByteBuffer.allocate(1 + 1 + domainData.size + 2)
            tempBuffer.order(ByteOrder.BIG_ENDIAN)
            tempBuffer.put(atyp)
            tempBuffer.put(domainData.size.toByte())
            tempBuffer.put(domainData)
            tempBuffer.putShort(port.toShort())
            addrHeaderPayload = tempBuffer.array()
        }

        // Calculate HMAC for ATYP | ADDR | PORT part
        // Key for HMAC is key + writeIV (from CryptoStreamProcessor)
        val currentKey = key ?: throw IllegalStateException("OTA Obfuscater: Encryption key not set.")
        val currentWriteIV = writeIV ?: throw IllegalStateException("OTA Obfuscater: Write IV not set.")

        val hmacKeyMaterial = ByteArray(currentKey.size + currentWriteIV.size)
        System.arraycopy(currentWriteIV, 0, hmacKeyMaterial, 0, currentWriteIV.size) // IV first in Swift code for OTA header HMAC key
        System.arraycopy(currentKey, 0, hmacKeyMaterial, currentWriteIV.size, currentKey.size)

        val hmac = HMAC.final(addrHeaderPayload, HashAlgorithm.SHA1, hmacKeyMaterial) // Assuming HMAC.kt and HashAlgorithm.kt
        val hmacShort = hmac.copyOfRange(0, 10) // Take first 10 bytes

        return addrHeaderPayload + hmacShort // Original Swift code had 0x13 as first byte, then this.
                                            // The 0x13 was for "OTA enabled, address included".
                                            // Let's assume this header is built and then prefixed by 0x13 by caller if needed, or add here.
                                            // The Swift code: `var response: [UInt8] = [0x13]`, then appends.
                                            // So, yes, prefix with 0x13.
        // return byteArrayOf(0x13.toByte()) + addrHeaderPayload + hmacShort
        // The swift code for requestData():
        // response = [0x13]
        // response.append(UInt8(session.host.utf8.count)) // if domain
        // ... (addr + port) ...
        // responseData.append(HMAC...)
        // This means the ATYP was part of the HMACed data.
        // My addrHeaderPayload already contains ATYP, ADDR, PORT.
        return byteArrayOf(0x13.toByte()) + addrHeaderPayload + hmacShort

    }


    override fun output(data: ByteArray) { // Data from Local, to be obfuscated then passed to Crypto
        val outputStream = ByteArrayOutputStream()

        if (!requestSent) {
            requestSent = true
            val otaAddrHeader = formatOTAAddressHeader()
            outputStream.write(otaAddrHeader)
            otaLogger.info("Sent OTA address header ({} bytes) for session {}.", otaAddrHeader.size, session)
        }

        var dataOffset = 0
        while (dataOffset < data.size) {
            val blockLength = minOf(data.size - dataOffset, DATA_BLOCK_SIZE)

            // Chunk: [DataLen (2 bytes, BigEndian) | HMAC-SHA1 (10 bytes) of Data | Data]
            val chunkBuffer = ByteBuffer.allocate(2 + 10 + blockLength)
            chunkBuffer.order(ByteOrder.BIG_ENDIAN)
            chunkBuffer.putShort(blockLength.toShort())

            // HMAC for data chunk: uses writeIV + chunkCount (big endian UInt32) as key material for HMAC
            val currentWriteIV = writeIV ?: throw IllegalStateException("OTA Obfuscater: Write IV not set for chunk HMAC.")
            val hmacKeyForChunk = ByteBuffer.allocate(currentWriteIV.size + 4)
            hmacKeyForChunk.order(ByteOrder.BIG_ENDIAN)
            hmacKeyForChunk.put(currentWriteIV)
            hmacKeyForChunk.putInt(chunkCount.toInt()) // UInt32 count

            val dataChunkToHmac = data.copyOfRange(dataOffset, dataOffset + blockLength)
            val hmac = HMAC.final(dataChunkToHmac, HashAlgorithm.SHA1, hmacKeyForChunk.array())
            chunkBuffer.put(hmac, 0, 10) // First 10 bytes of HMAC

            chunkBuffer.put(dataChunkToHmac) // Actual data chunk

            outputStream.write(chunkBuffer.array())

            chunkCount++ // Increment chunk counter
            dataOffset += blockLength
        }

        val finalOutputData = outputStream.toByteArray()
        if (finalOutputData.isNotEmpty()) {
            otaLogger.info("Outputting {} bytes (includes OTA chunking) for session {}.", finalOutputData.size, session)
            super.output(finalOutputData) // Pass to CryptoStreamProcessor
        }
    }

    @Throws(Exception::class)
    override fun input(data: ByteArray) { // Data from Crypto (decrypted), to be de-obfuscated (de-chunked, HMAC verified)
        // TODO: Implement OTA de-chunking and HMAC verification for input data.
        // This is complex and involves:
        // 1. Buffering incoming data.
        // 2. Reading chunk length (2 bytes).
        // 3. Reading HMAC (10 bytes).
        // 4. Reading data chunk.
        // 5. Verifying HMAC using readIV + expected chunkCount.
        // 6. If HMAC is valid, pass de-chunked data to inputStreamProcessor.
        // 7. Increment expected chunkCount.
        // 8. Handle errors (bad HMAC, incorrect length).
        otaLogger.warn("input() de-chunking and HMAC verification not implemented for session {}. Passing through {} bytes.", session, data.size)
        super.input(data) // Placeholder: pass through
    }
}

// SOCKS5 ATYP constants used by ShadowsocksOriginStreamObfuscater for addressing
// Should be defined in a common place if used by SOCKS5 components too.
private const val SOCKS5_ATYP_IPV4: Byte = 0x01
private const val SOCKS5_ATYP_DOMAINNAME: Byte = 0x03
private const val SOCKS5_ATYP_IPV6: Byte = 0x04
