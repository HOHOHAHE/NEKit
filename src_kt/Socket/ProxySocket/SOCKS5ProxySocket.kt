import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream // Added missing import
import java.io.IOException // Added missing import
import org.slf4j.LoggerFactory // Added import

// Assuming ProxySocket.kt, RawTCPSocketProtocol.kt, ConnectSession.kt (Messages),
// AdapterSocket.kt, SocketDelegate.kt, ProxySocketEvent.kt (Event) are available.
// Also IPAddress.kt, Port.kt (Utils).

// --- SOCKS5 Constants (already defined in SOCKS5Adapter.kt, ensure consistency or move to common place) ---
private const val SOCKS_VERSION_5: Byte = 0x05
private const val SOCKS5_AUTH_METHOD_NONE: Byte = 0x00
private const val SOCKS5_CMD_CONNECT: Byte = 0x01
private const val SOCKS5_ATYP_IPV4: Byte = 0x01
private const val SOCKS5_ATYP_DOMAINNAME: Byte = 0x03
private const val SOCKS5_ATYP_IPV6: Byte = 0x04
private const val SOCKS5_REPLY_SUCCESS: Byte = 0x00
// Reply codes for errors can be added here if needed for sending error responses.
// --- End SOCKS5 Constants ---

/**
 * Handles server-side SOCKS5 proxy logic for a client connection.
 * It parses the SOCKS5 handshake, commands (only CONNECT is fully handled for forwarding),
 * and then relays data if connection to target is successful.
 */
class SOCKS5ProxySocket(
    clientRawSocket: RawTCPSocketProtocol,
    observe: Boolean = true
) : ProxySocket(clientRawSocket, observe) {

    private val socks5Logger = LoggerFactory.getLogger(SOCKS5ProxySocket::class.java)

    private enum class ReadState(val descriptionVal: String) {
        INVALID("invalid"),
        READING_VERSION_NMETHODS("reading version and nmethods"),
        READING_METHODS("reading methods"),
        READING_REQUEST_HEADER("reading request header (VER, CMD, RSV, ATYP)"),
        READING_IPV4_ADDRESS("reading IPv4 address"),
        READING_DOMAIN_LENGTH("reading domain length"),
        READING_DOMAIN("reading domain"),
        READING_IPV6_ADDRESS("reading IPv6 address"),
        READING_PORT("reading port"),
        FORWARDING("forwarding"),
        STOPPED("stopped");
        override fun toString(): String = descriptionVal
    }

    private enum class WriteState(val descriptionVal: String) {
        INVALID("invalid"),
        SENDING_AUTH_RESPONSE("sending auth response"),
        SENDING_CONNECT_REPLY("sending connect reply"),
        FORWARDING("forwarding"),
        STOPPED("stopped");
        override fun toString(): String = descriptionVal
    }

    lateinit var destinationHost: String // Can be domain or IP string
        private set
    var destinationPort: Int = 0
        private set

    private var internalReadStatus: ReadState = ReadState.INVALID
    private var internalWriteStatus: WriteState = WriteState.INVALID

    // Buffer for accumulating parts of the SOCKS5 handshake if they arrive fragmented
    private val handshakeBuffer = ByteArrayOutputStream() // From java.io

    val readStatusDescription: String get() = internalReadStatus.toString()
    val writeStatusDescription: String get() = internalWriteStatus.toString()

    init {
        socks5Logger.info("Created with rawSocket: {}", rawSocket)
    }

    override fun openSocket() {
        super.openSocket()
        if (isCancelled) return

        socks5Logger.info("openSocket() called for session {}. Reading SOCKS5 version/nmethods (2 bytes).", session)
        internalReadStatus = ReadState.READING_VERSION_NMETHODS
        rawSocket.readDataTo(length = 2)
    }

    // Helper to send SOCKS5 error reply and disconnect
    private fun sendErrorReplyAndDisconnect(replyCode: Byte, errorMessage: String) {
        socks5Logger.error("Session {}: {}. Sending reply code {} and disconnecting.", session, errorMessage, replyCode)
        val response = ByteBuffer.allocate(10) // Standard size for error reply with dummy address/port
        response.order(ByteOrder.BIG_ENDIAN)
        response.put(SOCKS_VERSION_5)
        response.put(replyCode)
        response.put(0x00) // RSV
        response.put(SOCKS5_ATYP_IPV4) // ATYP (IPv4)
        response.putInt(0) // BND.ADDR (0.0.0.0)
        response.putShort(0) // BND.PORT (0)

        val writeScope = CoroutineScope(Dispatchers.Default) // TODO: Use managed scope
        writeScope.launch {
            try {
                write(response.array()) // Send error reply
                // After write, didWrite might trigger further state changes or just log.
                // Then force disconnect.
            } catch (e: Exception) {
                socks5Logger.error("Session {}: Exception while sending error reply: {}", session, e.message, e)
            } finally {
                forceDisconnect(becauseOf = IOException("$errorMessage for session $session"))
            }
        }
    }


    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        super.didRead(data, from) // Signals observer
        if (isCancelled || internalReadStatus == ReadState.STOPPED) return

        handshakeBuffer.write(data) // Accumulate data
        val bufferBytes = handshakeBuffer.toByteArray() // Current accumulated bytes

        try {
            when (internalReadStatus) {
                ReadState.FORWARDING -> {
                    handshakeBuffer.reset() // Not needed for forwarding
                    delegate?.get()?.didRead(bufferBytes, this)
                    return // Done with this data chunk
                }
                ReadState.READING_VERSION_NMETHODS -> {
                    if (bufferBytes.size < 2) { rawSocket.readDataTo(length = 2 - bufferBytes.size); return } // Need more
                    handshakeBuffer.reset()
                    if (bufferBytes[0] != SOCKS_VERSION_5) {
                        sendErrorReplyAndDisconnect(0xFF.toByte(), "Unsupported SOCKS version: ${bufferBytes[0]}")
                        return
                    }
                    val nMethods = bufferBytes[1].toUByte().toInt()
                    if (nMethods == 0) {
                        sendErrorReplyAndDisconnect(0xFF.toByte(), "No authentication methods provided by client.")
                        return
                    }
                    internalReadStatus = ReadState.READING_METHODS
                    rawSocket.readDataTo(length = nMethods)
                }
                ReadState.READING_METHODS -> {
                    if (bufferBytes.size < data.size /*actual nMethods read in this call*/) { /*This logic is tricky if nMethods was large*/ }
                    // Assuming data contains all nMethods bytes.
                    handshakeBuffer.reset()
                    var noAuthFound = false
                    for (method in bufferBytes) {
                        if (method == SOCKS5_AUTH_METHOD_NONE) {
                            noAuthFound = true
                            break
                        }
                    }
                    if (!noAuthFound) {
                        sendErrorReplyAndDisconnect(0xFF.toByte(), "No acceptable authentication methods (only NO_AUTH supported).")
                        return
                    }
                    val response = byteArrayOf(SOCKS_VERSION_5, SOCKS5_AUTH_METHOD_NONE)
                    internalWriteStatus = WriteState.SENDING_AUTH_RESPONSE // Next state after write
                    // Write is suspend, launch in a scope
                    CoroutineScope(Dispatchers.Default).launch { write(response) }
                }
                ReadState.READING_REQUEST_HEADER -> { // VER, CMD, RSV, ATYP
                    if (bufferBytes.size < 4) { rawSocket.readDataTo(length = 4 - bufferBytes.size); return }
                    handshakeBuffer.reset()
                    if (bufferBytes[0] != SOCKS_VERSION_5) {
                        sendErrorReplyAndDisconnect(0xFF.toByte(), "Invalid SOCKS version in request: ${bufferBytes[0]}.")
                        return
                    }
                    if (bufferBytes[1] != SOCKS5_CMD_CONNECT) {
                        sendErrorReplyAndDisconnect(0x07.toByte(), "Unsupported SOCKS command: ${bufferBytes[1]}. Only CONNECT supported.")
                        return
                    }
                    // RSV (bufferBytes[2]) must be 0x00
                    val atyp = bufferBytes[3]
                    when (atyp) {
                        SOCKS5_ATYP_IPV4 -> {
                            internalReadStatus = ReadState.READING_IPV4_ADDRESS
                            rawSocket.readDataTo(length = 4) // Read 4 bytes for IPv4 address
                        }
                        SOCKS5_ATYP_DOMAINNAME -> {
                            internalReadStatus = ReadState.READING_DOMAIN_LENGTH
                            rawSocket.readDataTo(length = 1) // Read 1 byte for domain length
                        }
                        SOCKS5_ATYP_IPV6 -> {
                            internalReadStatus = ReadState.READING_IPV6_ADDRESS
                            rawSocket.readDataTo(length = 16) // Read 16 bytes for IPv6 address
                        }
                        else -> {
                            sendErrorReplyAndDisconnect(0x08.toByte(), "Unsupported address type (ATYP): $atyp.")
                            return
                        }
                    }
                }
                ReadState.READING_IPV4_ADDRESS -> {
                    if (bufferBytes.size < 4) { rawSocket.readDataTo(length = 4 - bufferBytes.size); return }
                    handshakeBuffer.reset()
                    destinationHost = InetAddress.getByAddress(bufferBytes).hostAddress
                    internalReadStatus = ReadState.READING_PORT
                    rawSocket.readDataTo(length = 2) // Read 2 bytes for port
                }
                ReadState.READING_DOMAIN_LENGTH -> {
                    if (bufferBytes.isEmpty()) { rawSocket.readDataTo(length = 1); return } // Should not happen if readDataTo(1) was called
                    handshakeBuffer.reset()
                    val domainLength = bufferBytes[0].toUByte().toInt()
                    internalReadStatus = ReadState.READING_DOMAIN
                    rawSocket.readDataTo(length = domainLength)
                }
                ReadState.READING_DOMAIN -> {
                    // Assuming data contains the full domain
                    handshakeBuffer.reset()
                    destinationHost = String(bufferBytes, StandardCharsets.UTF_8) // Or ASCII
                    internalReadStatus = ReadState.READING_PORT
                    rawSocket.readDataTo(length = 2)
                }
                ReadState.READING_IPV6_ADDRESS -> {
                    if (bufferBytes.size < 16) { rawSocket.readDataTo(length = 16 - bufferBytes.size); return }
                    handshakeBuffer.reset()
                    destinationHost = InetAddress.getByAddress(bufferBytes).hostAddress
                    internalReadStatus = ReadState.READING_PORT
                    rawSocket.readDataTo(length = 2)
                }
                ReadState.READING_PORT -> {
                    if (bufferBytes.size < 2) { rawSocket.readDataTo(length = 2 - bufferBytes.size); return }
                    handshakeBuffer.reset()
                    destinationPort = ByteBuffer.wrap(bufferBytes).order(ByteOrder.BIG_ENDIAN).short.toUShort().toInt()

                    socks5Logger.info("Parsed SOCKS5 request for {}:{} for session {}.", destinationHost, destinationPort, session)
                    // Do not change to FORWARDING yet. Wait for respondTo from Tunnel.
                    // internalReadStatus = ReadState.FORWARDING; // Premature

                    // Create ConnectSession and notify delegate
                    // Using factory for ConnectSession for failable init logic
                    this.session = ConnectSession.create(destinationHost, destinationPort, fakeIPEnabled = false)
                    if (this.session == null) {
                        sendErrorReplyAndDisconnect(0x01.toByte(), "Failed to create session (internal server error).")
                        return
                    }
                    observer?.signal(ProxySocketEvent.ReceivedRequest(this.session!!, this))
                    delegate?.get()?.didReceive(this.session!!, this)
                    // Now wait for respondTo() to be called by Tunnel.
                }
                else -> { /* Should not happen if states are managed correctly */ }
            }
        } catch (e: Exception) {
            socks5Logger.error("Error during SOCKS5 handshake processing (state {}) for session {}: {}", internalReadStatus, session, e.message, e)
            sendErrorReplyAndDisconnect(0x01.toByte(), "General SOCKS5 server error during handshake.")
        }
    }

    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        super.didWrite(data, by) // Signals observer

        when (internalWriteStatus) {
            WriteState.SENDING_AUTH_RESPONSE -> {
                // Auth response sent, now expect client's connect request
                internalReadStatus = ReadState.READING_REQUEST_HEADER
                rawSocket.readDataTo(length = 4) // Read VER, CMD, RSV, ATYP
            }
            WriteState.SENDING_CONNECT_REPLY -> {
                // Connect reply sent to client. If it was success, transition to forwarding.
                // Check if reply was success (this logic should be in respondTo or here based on reply data)
                // For now, assume if SENDING_CONNECT_REPLY was set, it was a success reply.
                socks5Logger.info("Connect reply sent for session {}. Transitioning to forwarding.", session)
                internalWriteStatus = WriteState.FORWARDING
                internalReadStatus = ReadState.FORWARDING // Also ready to read and forward from client
                _status = SocketStatus.ESTABLISHED // Overall socket status
                observer?.signal(ProxySocketEvent.ReadyForForward(this))
                delegate?.get()?.didBecomeReadyToForward(this)
                // Start reading data from client to forward
                readData()
            }
            WriteState.FORWARDING -> {
                delegate?.get()?.didWrite(data, this)
            }
            else -> {
                socks5Logger.warn("Data written in unexpected write state: {} for session {}", internalWriteStatus, session)
            }
        }
    }

    override fun respondTo(adapter: AdapterSocket) {
        super.respondTo(adapter) // Signals observer
        if (isCancelled) return

        socks5Logger.info("Adapter ready for {}:{}. Sending SOCKS5 success reply for session {}.", destinationHost, destinationPort, session)
        // Construct SOCKS5 success reply: VER, REP=0x00, RSV, ATYP, BND.ADDR, BND.PORT
        // BND.ADDR and BND.PORT should be the address/port the proxy *bound* for the client on the server side,
        // or the address/port of the proxy itself that the client is connected to.
        // For CONNECT, often zeros or proxy's listening address for this connection.
        // Let's use 0.0.0.0:0 as a generic bound address for simplicity.
        val response = ByteBuffer.allocate(10) // VER,REP,RSV,ATYP_IP4,IPv4(4),PORT(2)
        response.order(ByteOrder.BIG_ENDIAN)
        response.put(SOCKS_VERSION_5)
        response.put(SOCKS5_REPLY_SUCCESS) // Success
        response.put(0x00) // RSV
        response.put(SOCKS5_ATYP_IPV4) // ATYP (e.g., IPv4, can be actual BND.ADDR type)
        response.putInt(0) // BND.ADDR (0.0.0.0)
        response.putShort(0) // BND.PORT (0)

        internalWriteStatus = WriteState.SENDING_CONNECT_REPLY
        val writeScope = CoroutineScope(Dispatchers.Default) // TODO: Use managed scope
        writeScope.launch {
            try {
                write(response.array())
                // didWrite callback will handle transition to FORWARDING state.
            } catch (e: Exception) {
                socks5Logger.error("Failed to write SOCKS5 success reply for session {}: {}", session, e.message, e)
                forceDisconnect(becauseOf = e)
            }
        }
    }

    override fun disconnect(becauseOf: Throwable?) {
        internalReadStatus = ReadState.STOPPED
        internalWriteStatus = WriteState.STOPPED
        super.disconnect(becauseOf)
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        internalReadStatus = ReadState.STOPPED
        internalWriteStatus = WriteState.STOPPED
        super.forceDisconnect(becauseOf)
    }
}
