import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.random.Random

import org.slf4j.LoggerFactory // Added import

// Assuming CryptoStreamProcessor.kt, ShadowsocksAdapter.kt (as interfaces/placeholders),
// Buffer.kt (Utils), HTTPConstants.kt (Utils, for DOUBLE_CRLF), RandomUtils.kt (Utils, for fill),
// HMAC.kt (Crypto), HashAlgorithm.kt (Crypto) are available.

// --- Placeholder Definitions ---

// From CryptoStreamProcessor.kt context, refine if needed:
// interface ShadowsocksCryptoProcessorInterface {
//    val key: ByteArray // Used by TLSProtocolObfuscater
//    fun input(data: ByteArray) // throws Exception
// }
// For now, let's use the class name directly if it's already translated, or a placeholder.
// class ShadowsocksCryptoProcessor(key: ByteArray, algorithm: CryptoAlgorithm) { ... }


// Interface for what ProtocolObfuscater expects from ShadowsocksAdapter (outputStreamProcessor)
interface ShadowsocksAdapterProtocolFeedback {
    val port: Port // Used by HTTPProtocolObfuscater for Host header
    fun becomeReadyToForward()
    fun output(data: ByteArray) // To send data to network (via rawSocket)
    val rawSocket: RawTCPSocketProtocol? // Used by TLSProtocolObfuscater to request reads
}


object HTTPObfuscaterConstants { // Was HTTPProtocolObfuscater.userAgent
    val USER_AGENTS = listOf(
        "Mozilla/5.0 (Windows NT 6.3; WOW64; rv:40.0) Gecko/20100101 Firefox/40.0",
        "Mozilla/5.0 (Windows NT 6.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2228.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/535.11 (KHTML, like Gecko) Ubuntu/11.10 Chromium/27.0.1453.93 Chrome/27.0.1453.93 Safari/537.36",
        // Add more if needed, or keep it shorter for Kotlin example
    )
    const val HEADER_LENGTH_ESTIMATE = 30 // Used in HTTPProtocolObfuscater for fake request data length
}

// Utils.Random.fill placeholder
object RandomUtils {
    private val random = java.security.SecureRandom()
    fun fill(data: ByteArray, from: Int = 0, length: Int = data.size - from) {
        val bytesToFill = ByteArray(length)
        random.nextBytes(bytesToFill)
        System.arraycopy(bytesToFill, 0, data, from, length)
    }
}

// --- End Placeholder Definitions ---


// Base Factory for Protocol Obfuscators
open class ShadowsocksProtocolObfuscaterFactoryBase {
    // open fun build(): ShadowsocksProtocolObfuscaterBase = ShadowsocksProtocolObfuscaterBase()
    // Making it abstract or having subclasses implement it fully.
    // The Swift one returned ProtocolObfuscaterBase(), which is not very useful unless it's Origin.
    // Let's assume subclasses will override.
    open fun build(): ShadowsocksProtocolObfuscaterBase {
        // Defaulting to Origin type if not overridden, as per Swift base Factory.build()
        return ShadowsocksOriginProtocolObfuscater()
    }
}

// Base class for Protocol Obfuscators
open class ShadowsocksProtocolObfuscaterBase {
    open var inputStreamProcessorRef: WeakReference<ShadowsocksCryptoProcessor?> = WeakReference(null)
    open var outputStreamProcessorRef: WeakReference<ShadowsocksAdapterProtocolFeedback?> = WeakReference(null)

    protected val inputStreamProcessor: ShadowsocksCryptoProcessor? get() = inputStreamProcessorRef.get()
    protected val outputStreamProcessor: ShadowsocksAdapterProtocolFeedback? get() = outputStreamProcessorRef.get()


    open fun start() {}
    @Throws(Exception::class)
    open fun input(data: ByteArray) {
        // Default: pass through to crypto processor
        inputStreamProcessor?.input(data)
    }
    open fun output(data: ByteArray) {
        // Default: pass through to adapter output
        outputStreamProcessor?.output(data)
    }

    open fun didWrite() {} // Callback from adapter after data is written to socket
}

// --- Origin Protocol Obfuscator ---
class ShadowsocksOriginProtocolObfuscaterFactory : ShadowsocksProtocolObfuscaterFactoryBase() {
    override fun build(): ShadowsocksProtocolObfuscaterBase = ShadowsocksOriginProtocolObfuscater()
}

class ShadowsocksOriginProtocolObfuscater : ShadowsocksProtocolObfuscaterBase() {
    override fun start() {
        outputStreamProcessor?.becomeReadyToForward()
    }
    // input and output methods use base class pass-through behavior.
}

// --- HTTP Simple Protocol Obfuscator ---
class ShadowsocksHTTPProtocolObfuscaterFactory(
    private val method: String = "GET",
    private val hosts: List<String>,
    private val customHeader: String?
) : ShadowsocksProtocolObfuscaterFactoryBase() {
    override fun build(): ShadowsocksProtocolObfuscaterBase =
        ShadowsocksHTTPProtocolObfuscater(method, hosts, customHeader)
}

class ShadowsocksHTTPProtocolObfuscater(
    private val method: String,
    private val hosts: List<String>,
    private val customHeader: String?
) : ShadowsocksProtocolObfuscaterBase() {

    private var readingFakeHeader = false
    private var headerSent = false // Was sendHeader
    private val buffer: Buffer = Buffer(initialCapacity = 8192) // From Utils

    private fun generateHttpHeader(encapsulatingData: ByteArray): String {
        val hostToUse = hosts.randomOrNull() ?: "localhost"
        // Adapter's port for Host header construction
        val adapterPort = outputStreamProcessor?.port?.hostOrderValue ?: 80
        val hostField = if (adapterPort == 80 || adapterPort == 443) hostToUse else "$hostToUse:$adapterPort"

        val pathDataHex = hexlify(encapsulatingData)
        var header = "$method /$pathDataHex HTTP/1.1${HTTPConstants.CRLF}"
        header += "Host: $hostField${HTTPConstants.CRLF}"

        if (customHeader != null) {
            header += customHeader.lines().joinToString(HTTPConstants.CRLF) { it.trim() } + HTTPConstants.CRLF
        } else {
            header += "User-Agent: ${HTTPObfuscaterConstants.USER_AGENTS.randomOrNull() ?: "NEKit/1.0"}${HTTPConstants.CRLF}"
            header += "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8${HTTPConstants.CRLF}"
            header += "Accept-Language: en-US,en;q=0.8${HTTPConstants.CRLF}"
            header += "Accept-Encoding: gzip, deflate${HTTPConstants.CRLF}" // Note: Server must not actually gzip if client can't handle
            header += "DNT: 1${HTTPConstants.CRLF}"
            header += "Connection: keep-alive${HTTPConstants.CRLF}"
        }
        header += HTTPConstants.CRLF // End of headers
        return header
    }

    private fun hexlify(data: ByteArray): String {
        return data.joinToString("") { "%02x".format(it) }
    }

    override fun start() {
        readingFakeHeader = true // Expecting server to send some data first (which we ignore)
        outputStreamProcessor?.becomeReadyToForward() // Signal adapter that it can start sending (output method will be called)
    }

    @Throws(Exception::class)
    override fun input(data: ByteArray) { // Data from remote server (after decryption by CryptoStreamProcessor)
        if (readingFakeHeader) {
            buffer.append(data)
            // Try to find end of fake HTTP header from server
            if (buffer.get(to = HTTPConstants.DOUBLE_CRLF) != null) {
                readingFakeHeader = false
                val remainingData = buffer.getAll() // Get whatever is left in buffer after DOUBLE_CRLF
                buffer.release()
                if (remainingData != null && remainingData.isNotEmpty()) {
                    inputStreamProcessor?.input(remainingData)
                }
            }
            // If DOUBLE_CRLF not found, just keep buffering and wait for more data.
            // The Swift code also called `inputStreamProcessor.input(data: Data())` if not enough data for header.
            // This seems to indicate a signal or empty data flush.
            // For now, if header not complete, we just buffer. If it's important to signal, can add:
            // else { inputStreamProcessor?.input(ByteArray(0)) }
            return
        }
        inputStreamProcessor?.input(data)
    }

    override fun output(data: ByteArray) { // Data from local (to be sent to CryptoStreamProcessor for encryption)
        if (headerSent) {
            outputStreamProcessor?.output(data)
        } else {
            // This logic for fakeRequestDataLength is from Swift, seems to pick a portion of initial data to hide in URL path.
            // Relies on inputStreamProcessor (CryptoProcessor) having its key property available.
            val cryptoKeyLength = inputStreamProcessor?.key?.size ?: 16 // Default if key not available
            var fakeRequestDataLength = cryptoKeyLength + HTTPObfuscaterConstants.HEADER_LENGTH_ESTIMATE

            if (data.size - fakeRequestDataLength > 64) {
                fakeRequestDataLength += Random.nextInt(64)
            } else if (fakeRequestDataLength > data.size) { // Ensure not to take more than available
                fakeRequestDataLength = data.size
            }

            val dataToEncapsulate = data.copyOfRange(0, fakeRequestDataLength)
            val remainingData = data.copyOfRange(fakeRequestDataLength, data.size)

            val httpHeaderString = generateHttpHeader(encapsulatingData = dataToEncapsulate)
            var outputData = httpHeaderString.toByteArray(StandardCharsets.UTF_8)
            if (remainingData.isNotEmpty()) {
                outputData += remainingData
            }

            headerSent = true
            outputStreamProcessor?.output(outputData)
        }
    }
}


// --- TLS 1.2 Ticket Auth Protocol Obfuscator ---
class ShadowsocksTLSProtocolObfuscaterFactory(
    private val hosts: List<String>
) : ShadowsocksProtocolObfuscaterFactoryBase() {
    override fun build(): ShadowsocksProtocolObfuscaterBase =
        ShadowsocksTLSProtocolObfuscater(hosts)
}

class ShadowsocksTLSProtocolObfuscater(
    private val hosts: List<String>
) : ShadowsocksProtocolObfuscaterBase() {
    private val logger = LoggerFactory.getLogger(ShadowsocksTLSProtocolObfuscater::class.java)

    // TLS Constants
    private object TLS {
        const val VERSION_TLS12: Short = 0x0303
        const val CONTENT_TYPE_HANDSHAKE: Byte = 22
        const val CONTENT_TYPE_APPDATA: Byte = 23
        const val HANDSHAKE_TYPE_CLIENT_HELLO: Byte = 1
    }

    // More detailed states for TLS Auth
    private enum class State {
        INITIAL,                // Start state
        CLIENT_HELLO_SENT,      // Fake ClientHello sent, first data packet can be processed
        SERVER_RESPONSE_EXPECTED, // Waiting for server's initial (likely TLS-like) response
        FORWARDING,             // Handshake emulation complete, forwarding data
        STOPPED
    }
    private var currentStatus: State = State.INITIAL
    private val clientRandom: ByteArray = ByteArray(32).apply { RandomUtils.fill(this) }
    private val incomingRecordBuffer: Buffer = Buffer(capacity = 4096) // Buffer for TLS records from server
    private var tlsAuthHmacKey: ByteArray? = null // Derived from SS master key

    // Data that needs to be sent after ClientHello (first encrypted payload)
    private var pendingFirstData: ByteArray? = null


    private fun deriveTlsAuthHmacKey(): ByteArray {
        val masterKey = inputStreamProcessor?.key
            ?: throw IllegalStateException("Cannot derive TLS Auth HMAC key: Shadowsocks master key not available.")
        // TODO: Verify the exact KDF spec for tlsauth HMAC key (salt, info).
        // This is a placeholder KDF. Common practice is HKDF-SHA1.
        // For simplicity, using a direct HMAC for derivation or a simplified KDF.
        // Let's assume a simple derivation for now: HMAC-SHA1(masterKey, "tlsauth hmac key")
        return HMAC.final(masterKey, HashAlgorithm.SHA1, "tlsauth hmac key".toByteArray(StandardCharsets.UTF_8))
            .copyOfRange(0, 16) // Example: Use first 16 bytes if SHA1 is too long
    }

    override fun start() {
        if (currentStatus != State.INITIAL) {
            logger.warn("start() called in non-initial state: {}", currentStatus)
            return
        }

        try {
            tlsAuthHmacKey = deriveTlsAuthHmacKey()
        } catch (e: Exception) {
            logger.error("Failed to derive TLS Auth HMAC key: {}", e.message, e)
            // Critical failure, cannot proceed with obfuscation
            outputStreamProcessor?.didErrorOccur(e, this) // Notify adapter of error
            currentStatus = State.STOPPED
            return
        }

        logger.info("TLS Auth Obfuscator started. Generating ClientHello.")
        val clientHello = buildClientHello()
        outputStreamProcessor?.output(clientHello) // Send ClientHello record
        currentStatus = State.CLIENT_HELLO_SENT

        // Unlike Origin, tlsauth doesn't immediately call becomeReadyToForward().
        // It waits for the first data packet from the client, which it then sends with an HMAC.
        // After *that* is sent, it calls becomeReadyToForward().
    }

    private fun buildClientHello(): ByteArray {
        val host = outputStreamProcessor?.session?.host ?: "localhost" // Target host for SNI

        // --- Extensions ---
        val extensions = ByteArrayOutputStream()

        // SNI Extension (0x0000)
        val serverNameBytes = host.toByteArray(StandardCharsets.UTF_8)
        val sniPayload = ByteBuffer.allocate(2 + 1 + 2 + serverNameBytes.size) // ListLen, Type, NameLen, Name
        sniPayload.putShort( (1 + 2 + serverNameBytes.size).toShort() ) // ServerNameList length
        sniPayload.put(0x00.toByte()) // Name Type: host_name (0)
        sniPayload.putShort(serverNameBytes.size.toShort())
        sniPayload.put(serverNameBytes)
        extensions.write(byteArrayOf(0x00, 0x00)) // Extension Type: server_name
        extensions.write(ByteBuffer.allocate(2).putShort(sniPayload.position().toShort()).array()) // Extension length
        extensions.write(sniPayload.array(), 0, sniPayload.position())

        // SessionTicket TLS Extension (0x0023) - Empty ticket
        extensions.write(byteArrayOf(0x00, 0x23)) // Extension Type: session_ticket
        extensions.write(byteArrayOf(0x00, 0x00)) // Extension length (0 for empty ticket)

        // TODO: Add other common extensions like ALPN, SignatureAlgorithms, ECPointFormats, SupportedGroups
        // For now, keeping it minimal.

        val extensionsBytes = extensions.toByteArray()

        // --- ClientHello Core ---
        // Ref: RFC 5246 (TLS 1.2) ClientHello structure
        val handshakePayload = ByteBuffer.allocate(200 + extensionsBytes.size) // Estimate
        handshakePayload.order(ByteOrder.BIG_ENDIAN)
        handshakePayload.put(TLS.HANDSHAKE_TYPE_CLIENT_HELLO) // HandshakeType: client_hello (1)
        // Placeholder for Handshake Length (3 bytes) - will be filled later
        handshakePayload.put(0x00.toByte()).put(0x00.toByte()).put(0x00.toByte())
        handshakePayload.putShort(TLS.VERSION_TLS12) // Version: TLS 1.2 (0x0303)
        handshakePayload.put(clientRandom) // Random (32 bytes)
        handshakePayload.put(0x00.toByte()) // Session ID Length: 0 (no session resumption)
        // Cipher Suites (example common suites)
        val cipherSuites = shortArrayOf(
            0xC02B.toShort(), // TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
            0xC02F.toShort(), // TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
            0x009E.toShort()  // TLS_DHE_RSA_WITH_AES_128_GCM_SHA256
        )
        handshakePayload.putShort((cipherSuites.size * 2).toShort()) // Cipher Suites Length (bytes)
        cipherSuites.forEach { handshakePayload.putShort(it) }
        handshakePayload.put(0x01.toByte()) // Compression Methods Length: 1
        handshakePayload.put(0x00.toByte()) // Compression Method: null (0)

        // Extensions
        if (extensionsBytes.isNotEmpty()) {
            handshakePayload.putShort(extensionsBytes.size.toShort()) // Extensions Length
            handshakePayload.put(extensionsBytes)
        } else {
            handshakePayload.putShort(0) // No extensions
        }

        // Fill in actual Handshake Length
        val handshakeLength = handshakePayload.position() - 4 // Exclude Type (1) and Length (3) fields
        handshakePayload.put(1, (handshakeLength shr 16).toByte())
        handshakePayload.put(2, (handshakeLength shr 8).toByte())
        handshakePayload.put(3, (handshakeLength and 0xFF).toByte())

        val finalHandshakeMessage = handshakePayload.array().copyOfRange(0, handshakePayload.position())

        // Wrap in TLS Record
        val record = ByteBuffer.allocate(5 + finalHandshakeMessage.size)
        record.order(ByteOrder.BIG_ENDIAN)
        record.put(TLS.CONTENT_TYPE_HANDSHAKE)
        record.putShort(TLS.VERSION_TLS12)
        record.putShort(finalHandshakeMessage.size.toShort())
        record.put(finalHandshakeMessage)

        logger.debug("Built ClientHello record ({} bytes)", record.position())
        return record.array().copyOfRange(0, record.position())
    }

    private fun packAppDataWithHmac(encryptedPayload: ByteArray, key: ByteArray): ByteArray {
        val hmac = HMAC.final(encryptedPayload, HashAlgorithm.SHA1, key).copyOfRange(0, 10)
        val dataWithHmac = hmac + encryptedPayload

        val record = ByteBuffer.allocate(5 + dataWithHmac.size)
        record.order(ByteOrder.BIG_ENDIAN)
        record.put(TLS.CONTENT_TYPE_APPDATA)
        record.putShort(TLS.VERSION_TLS12)
        record.putShort(dataWithHmac.size.toShort())
        record.put(dataWithHmac)
        return record.array().copyOfRange(0, record.position())
    }

    override fun output(data: ByteArray) { // `data` is encrypted Shadowsocks payload from CryptoStreamProcessor
        logger.debug("TLSAuth output: received {} bytes of encrypted payload in state {}", data.size, currentStatus)
        val hmacKey = tlsAuthHmacKey ?: run {
            logger.error("TLSAuth HMAC key not derived. Cannot process output.")
            outputStreamProcessor?.didErrorOccur(IllegalStateException("TLSAuth HMAC key missing"), this)
            currentStatus = State.STOPPED
            return
        }

        when (currentStatus) {
            State.CLIENT_HELLO_SENT -> {
                // This is the first data packet. Send it with HMAC.
                val appDataRecord = packAppDataWithHmac(data, hmacKey)
                logger.info("TLSAuth: Sending first data packet ({} bytes TLS record) after ClientHello.", appDataRecord.size)
                outputStreamProcessor?.output(appDataRecord)
                currentStatus = State.SERVER_RESPONSE_EXPECTED // Or FORWARDING if server doesn't send handshake
                // Signal ready to forward *after* the first client data packet (which includes ClientHello implicitly before it) is sent
                outputStreamProcessor?.becomeReadyToForward()
            }
            State.FORWARDING -> {
                // Subsequent data packets might not need individual HMACs in some tlsauth variants,
                // or they might. Assuming simple record wrapping for subsequent data.
                // For more security, all app data records could be HMACed.
                // For now, only first data packet has special HMAC.
                val appDataRecord = packDataAsTLSApplicationData(data) // Simple record wrapping
                logger.debug("TLSAuth: Sending subsequent data packet ({} bytes TLS record).", appDataRecord.size)
                outputStreamProcessor?.output(appDataRecord)
            }
            State.INITIAL -> {
                // ClientHello should have been sent by start(). If output is called here, it's unexpected.
                // Or, if start() doesn't send ClientHello, first output() call does.
                // Let's assume start() handles ClientHello. If data comes before ClientHello is sent by start(),
                // we could buffer it.
                logger.warn("TLSAuth: output() called in INITIAL state. Data might be lost if ClientHello not sent. Buffering this data.")
                pendingFirstData = (pendingFirstData ?: ByteArray(0)) + data
            }
            else -> {
                logger.error("TLSAuth: Output called in unexpected state {}. Dropping data.", currentStatus)
            }
        }
    }

    @Throws(Exception::class)
    override fun input(data: ByteArray) { // Data from remote server (already decrypted by CryptoStreamProcessor)
        logger.debug("TLSAuth input: received {} bytes from server in state {}.", data.size, currentStatus)
        incomingRecordBuffer.append(data)

        while (true) {
            val available = incomingRecordBuffer.count
            if (available < 5) { // Need at least record header
                logger.trace("TLSAuth input: Not enough data for record header (need 5, have {}). Buffering.", available)
                break
            }

            val recordHeaderBytes = incomingRecordBuffer.peek(5) // Peek, don't consume yet
            val recordType = recordHeaderBytes[0]
            // val recordVersion = ByteBuffer.wrap(recordHeaderBytes, 1, 2).order(ByteOrder.BIG_ENDIAN).short
            val recordLength = ByteBuffer.wrap(recordHeaderBytes, 3, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()

            if (recordLength < 0 || recordLength > 16384 + 2048) { // Max TLS plaintext + overhead
                logger.error("TLSAuth input: Invalid record length {} received. Closing connection.", recordLength)
                incomingRecordBuffer.release()
                outputStreamProcessor?.didErrorOccur(IOException("Invalid TLS record length: $recordLength"), this)
                currentStatus = State.STOPPED
                break
            }

            if (available < 5 + recordLength) { // Not enough for full record
                logger.trace("TLSAuth input: Not enough data for full record (need {}, have {}). Buffering.", 5 + recordLength, available)
                break
            }

            // Have a full record
            incomingRecordBuffer.get(5) // Consume header
            val recordPayload = incomingRecordBuffer.get(recordLength) ?: break // Should not happen

            when (currentStatus) {
                State.SERVER_RESPONSE_EXPECTED, State.CLIENT_HELLO_SENT -> { // Expecting ServerHello or similar
                    if (recordType == TLS.CONTENT_TYPE_HANDSHAKE) {
                        logger.info("TLSAuth: Received Handshake record from server ({} bytes). TODO: Process/verify ticket/HMAC.", recordPayload.size)
                        // TODO: Actual parsing of ServerHello, ticket, HMAC verification
                        currentStatus = State.FORWARDING
                        logger.info("TLSAuth: Handshake emulation complete (simplified). Transitioning to FORWARDING.")
                    } else if (recordType == TLS.CONTENT_TYPE_APPDATA) {
                        // Some tlsauth variants might send app data immediately if handshake is abbreviated
                        logger.info("TLSAuth: Received first ApplicationData record from server ({} bytes) during handshake phase.", recordPayload.size)
                        // TODO: HMAC verification for server's first data packet if spec requires
                        currentStatus = State.FORWARDING
                        inputStreamProcessor?.input(recordPayload)
                    } else {
                        logger.warn("TLSAuth: Received unexpected record type {} during handshake phase.", recordType)
                        // Potentially an error, close connection
                    }
                }
                State.FORWARDING -> {
                    if (recordType == TLS.CONTENT_TYPE_APPDATA) {
                        logger.debug("TLSAuth: Received ApplicationData record ({} bytes). Passing to crypto.", recordPayload.size)
                        inputStreamProcessor?.input(recordPayload)
                    } else {
                        logger.warn("TLSAuth: Received unexpected record type {} in FORWARDING state.", recordType)
                        // Handle other record types like Alert, ChangeCipherSpec if necessary
                    }
                }
                else -> {
                    logger.warn("TLSAuth: Received data in unexpected state {}. Discarding.", currentStatus)
                }
            }
            incomingRecordBuffer.squeeze()
            if (incomingRecordBuffer.count == 0) break
        }
    }

    // Simplified packDataAsTLSApplicationData, real one might need chunking for max TLS record size
    private fun packDataAsTLSApplicationData(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val record = ByteBuffer.allocate(5 + data.size)
        record.order(ByteOrder.BIG_ENDIAN)
        record.put(TLS.CONTENT_TYPE_APPDATA)
        record.putShort(TLS.VERSION_TLS12)
        record.putShort(data.size.toShort())
        record.put(data)
        return record.array().copyOfRange(0, record.position())
    }
}


// Helper for hexlify (moved from HTTP to be generally available if needed, or keep local)
private fun hexlify(data: ByteArray): String {
    return data.joinToString("") { "%02x".format(it.toUByte().toInt()) } // Ensure unsigned byte for format
}
