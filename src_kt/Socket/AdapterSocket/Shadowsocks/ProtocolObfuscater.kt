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
    // TODO: This is a highly complex protocol emulation. Translation requires careful byte-level construction
    //       and state management. The following is a structural placeholder.
    //       Many magic numbers and byte sequences from Swift need to be verified and correctly represented.

    private enum class Status { INITIAL, CLIENT_HELLO_SENT, SERVER_HELLO_RECEIVED, FORWARDING, STOPPED }
    private var currentStatus: Status = Status.INITIAL
    private val clientID: ByteArray = ByteArray(32).apply { RandomUtils.fill(this) }
    private val buffer: Buffer = Buffer(capacity = 2048) // From Utils

    override fun start() {
        logger.info("start() called. Sending ClientHello (simulated).")
        // TODO: Actual ClientHello construction and sending via outputStreamProcessor.output()
        // sendClientHello()
        currentStatus = Status.CLIENT_HELLO_SENT
        // After sending ClientHello, expect ServerHello, ChangeCipherSpec, EncryptedHandshakeMessage
        // The Swift code `outputStreamProcessor.socket.readDataTo(length: 129)` is problematic as
        // obfuscator should not directly interact with raw socket of adapter.
        // It should receive data via its `input()` method from CryptoStreamProcessor.
        // This implies the adapter needs to know how much to read for this obfuscator.
        // For now, this read trigger is omitted. The `input` method will handle incoming data.
        outputStreamProcessor?.becomeReadyToForward() // This signals ShadowsocksAdapter it can start outputting.
                                                    // The first output will trigger TLS obfuscator's output().
    }

    @Throws(Exception::class)
    override fun input(data: ByteArray) { // Data from remote server (after decryption by CryptoStreamProcessor)
        logger.info("input({} bytes) in state {}.", data.size, currentStatus)
        // TODO: Implement state machine for parsing server's TLS handshake messages (simulated).
        // This involves parsing record layer, handshake protocol messages.
        // The Swift code had `handleInput(data)` for status 8 and `becomeReadyToForward` for status 1.
        // This implies a more complex state machine and data flow than shown in the snippet.
        // For now, a simplified pass-through or buffering.

        when (currentStatus) {
            Status.CLIENT_HELLO_SENT -> {
                // Assuming this data is ServerHello, ChangeCipherSpec, EncryptedHandshakeMessage
                // We need to "consume" it.
                logger.info("Received server handshake data (simulated consumption).")
                // In real TLS, we'd verify this. Here, we just transition.
                currentStatus = Status.FORWARDING // Simplified: Assume handshake done after first server data.
                // Pass any remaining/actual application data after handshake to crypto processor
                // This placeholder assumes `data` might contain app data after handshake.
                // A real implementation needs to parse TLS records to separate handshake from app data.
                inputStreamProcessor?.input(data)
            }
            Status.FORWARDING -> {
                // Data is packed in TLS records, needs to be unpacked.
                // The Swift `handleInput` had a loop with `buffer.skip(3)`, `buffer.get(length: length)`.
                // This is for unpacking application data from TLS records.
                buffer.append(data)
                val unpackedData = ByteArrayOutputStream()
                while(buffer.count >= 5) { // Minimum TLS record header size
                    val recordHeader = buffer.get(5) ?: break // Should not happen if count >= 5
                    // recordHeader[0] is type (e.g., 0x17 for AppData)
                    // recordHeader[1,2] is version (e.g., 0x0303 for TLS 1.2)
                    // recordHeader[3,4] is length of app data
                    if (recordHeader[0] != 0x17.toByte()) { // Application Data type
                        logger.error("Expected AppData record, got type {}. Skipping.", recordHeader[0])
                        // Malformed or unexpected record, break or try to find next. This is complex.
                        // For placeholder, stop processing this buffer chunk.
                        buffer.setBack(5) // Put header back if we can't process it. Or clear buffer.
                        break
                    }
                    val appDataLength = ByteBuffer.wrap(recordHeader, 3, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
                    if (buffer.count >= appDataLength) {
                        val appData = buffer.get(appDataLength) ?: break // Should not happen
                        unpackedData.write(appData)
                    } else {
                        // Not enough data for this record's payload, put header back and wait for more.
                        buffer.setBack(5)
                        break
                    }
                }
                buffer.squeeze() // Reclaim read space
                if (unpackedData.size() > 0) {
                    inputStreamProcessor?.input(unpackedData.toByteArray())
                }
            }
            else -> {
                logger.warn("Received data in unexpected state {}.", currentStatus)
                // Buffer or drop? For now, buffer.
                buffer.append(data)
            }
        }
    }

    override fun output(data: ByteArray) { // Data from local (to be sent to CryptoStreamProcessor for encryption)
        logger.info("output({} bytes) in state {}.", data.size, currentStatus)
        // TODO: Implement state machine for packaging data into TLS application data records.
        // The Swift code had `handleStatus0`, `handleStatus1`, `handleStatus8` which built complex handshake/data packets.
        // This placeholder will just pass data through or do minimal TLS record wrapping.
        when (currentStatus) {
            Status.INITIAL, Status.CLIENT_HELLO_SENT -> {
                // This is the first application data from client. We need to send our fake ClientHello first,
                // then this data as Application Data.
                // The Swift `start()` called `handleStatus0()` which sent ClientHello.
                // Then `outputStreamProcessor.socket.readDataTo(length: 129)` was called.
                // This implies the server's response to ClientHello is read before client sends app data.
                // This is complex. For now, let's assume `start()` initiated outgoing handshake part.
                // If this `output` is called *after* `start` and `becomeReadyToForward` (from `start`),
                // it means we are ready to send app data.
                // The Swift `handleStatus1` was for first app data, `handleStatus8` for subsequent.
                // This needs to be mapped.
                // For simplicity now:
                if (currentStatus == Status.CLIENT_HELLO_SENT) { // Assume ClientHello was sent by `start` or first part of `output`
                    currentStatus = Status.FORWARDING // Transition after first client data output attempt
                }
                 outputStreamProcessor?.output(packDataAsTLSApplicationData(data))
            }
            Status.FORWARDING -> {
                outputStreamProcessor?.output(packDataAsTLSApplicationData(data))
            }
            else -> {
                logger.error("Output called in unexpected state {}. Dropping data.", currentStatus)
            }
        }
    }

    private fun packDataAsTLSApplicationData(data: ByteArray): ByteArray {
        // Simplified: wrap data in a single TLS Application Data record.
        // Real implementation might need to chunk for max TLS record size.
        if (data.isEmpty()) return ByteArray(0)
        val header = byteArrayOf(
            0x17.toByte(), // Type: Application Data
            0x03, 0x03,    // Version: TLS 1.2 (0x0303)
            (data.size shr 8).toByte(), // Length MSB
            (data.size and 0xFF).toByte()  // Length LSB
        )
        return header + data
    }

    // TODO: Implement methods like authData(), pack(), handleStatus0(), handleStatus1() from Swift
    //       These are complex and involve constructing specific byte sequences for TLS handshake.
    //       This placeholder only provides basic structure.
}


// Helper for hexlify (moved from HTTP to be generally available if needed, or keep local)
private fun hexlify(data: ByteArray): String {
    return data.joinToString("") { "%02x".format(it.toUByte().toInt()) } // Ensure unsigned byte for format
}
