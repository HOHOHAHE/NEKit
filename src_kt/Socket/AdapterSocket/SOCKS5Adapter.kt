import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob // Added for managed scope
import kotlinx.coroutines.cancel // Added for managed scope
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory // Added import

import RawSocket.RawTCPSocketProtocol // Corrected import
import RawSocket.RawSocketFactory // Corrected import
import Messages.ConnectSession // Corrected import
import Utils.IPAddress // Corrected import
import Utils.Port // Corrected import
import Socket.SocketStatus // Corrected import
import Event.Event.AdapterSocketEvent // Corrected import
import Messages.EventSource // Corrected import
import Event.ObserverFactory // Corrected import

// --- SOCKS5 Constants ---
private const val SOCKS_VERSION_5: Byte = 0x05
private const val SOCKS5_AUTH_METHOD_NONE: Byte = 0x00
// private const val SOCKS5_AUTH_METHOD_GSSAPI: Byte = 0x01 // Not used in this simple case
// private const val SOCKS5_AUTH_METHOD_USERPASS: Byte = 0x02 // Not used
private const val SOCKS5_CMD_CONNECT: Byte = 0x01
// private const val SOCKS5_CMD_BIND: Byte = 0x02
// private const val SOCKS5_CMD_UDP_ASSOCIATE: Byte = 0x03
private const val SOCKS5_ATYP_IPV4: Byte = 0x01
private const val SOCKS5_ATYP_DOMAINNAME: Byte = 0x03
private const val SOCKS5_ATYP_IPV6: Byte = 0x04
private const val SOCKS5_REPLY_SUCCESS: Byte = 0x00
// --- End SOCKS5 Constants ---

/**
 * Adapter for connecting to a remote host through a SOCKS5 proxy.
 * Implements the SOCKS5 handshake and connection protocol.
 */
open class SOCKS5Adapter(
    val serverHost: String,
    val serverPort: Int,
    initialRawSocket: RawTCPSocketProtocol? = RawSocketFactory.getRawSocket()
) : AdapterSocket(initialRawSocket) {

   private val socks5AdapterLogger = LoggerFactory.getLogger(SOCKS5Adapter::class.java)

   // Managed CoroutineScope for the SOCKS5Adapter lifecycle
   private val socks5AdapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private enum class State {
        IDLE,
        CONNECTING_TO_PROXY,      // Raw socket connecting to SOCKS5 server
        SENT_GREETING,            // Sent initial greeting (VER, NMETHODS, METHODS)
        READING_AUTH_RESPONSE,    // Reading server's auth method choice (VER, METHOD)
        SENT_CONNECT_REQUEST,     // Sent CONNECT command (VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT)
        READING_CONNECT_REPLY_HEADER, // Reading initial part of connect reply (VER, REP, RSV, ATYP)
        READING_CONNECT_REPLY_ADDR_PORT, // Reading BND.ADDR, BND.PORT part of reply
        FORWARDING,               // Handshake complete, data forwarding
        STOPPED
    }

    private var internalState: State = State.IDLE

    // Initial greeting: Version 5, 1 auth method, 0x00 (No Authentication Required)
    private val socks5GreetingMessage: ByteArray = byteArrayOf(SOCKS_VERSION_5, 0x01, SOCKS5_AUTH_METHOD_NONE)
    private var connectReplyAtyp: Byte = 0 // To store ATYP from connect reply header

    init {
        socks5AdapterLogger.info("SOCKS5Adapter created for proxy {}:{}.", serverHost, serverPort)
    }

    override fun openSocketWith(session: ConnectSession) {
        super.openSocketWith(session) // Sets this.session, observer, rawSocket.delegate

        val currentRawSocket = rawSocket ?: run {
            System.err.println("ERROR: SOCKS5Adapter: Raw socket is null in openSocketWith.")
            _status = SocketStatus.CLOSED
            this.delegate?.get()?.didErrorOccur(IllegalStateException("Raw socket not available for SOCKS5Adapter"), this)
            this.delegate?.get()?.didDisconnect(this)
            return
        }
        if (isCancelled) return

        internalState = State.CONNECTING_TO_PROXY
        _status = SocketStatus.CONNECTING
        observer?.signal(AdapterSocketEvent.SocketOpened(this, session))
        socks5AdapterLogger.info("Connecting to SOCKS5 proxy {}:{} for session: {}", serverHost, serverPort, session)

        socks5AdapterScope.launch { // Using managed scope
            try {
                currentRawSocket.connectTo(host = serverHost, port = serverPort)
                // Result handled by didConnect (from RawTCPSocketDelegate)
            } catch (e: Exception) {
                socks5AdapterLogger.error("Failed to connect to proxy {}:{}: {}", serverHost, serverPort, e.message, e)
                handleConnectionFailure(e)
            }
        }
    }

    // Called by RawTCPSocketDelegate when connection to SOCKS5 proxy is established
    override fun didConnect(socket: RawTCPSocketProtocol) {
        // DO NOT call super.didConnect(socket) here, as SOCKS5 handshake is not yet complete.
        // AdapterSocket.didConnect signals delegate?.didConnectWith(this), which is premature.
        socks5AdapterLogger.info("Raw socket connected to proxy. Sending SOCKS5 greeting.")
        internalState = State.SENT_GREETING
        socks5AdapterScope.launch { // Using managed scope
            try {
                this@SOCKS5Adapter.write(socks5GreetingMessage) // Write greeting
                // After write completes (in didWrite), we'll expect server's auth choice
            } catch (e: Exception) {
                socks5AdapterLogger.error("Failed to write SOCKS5 greeting: {}", e.message, e)
                handleConnectionFailure(e)
            }
        }
    }

    // Called by RawTCPSocketDelegate after a write operation completes
    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        super.didWrite(data, by) // Signals event via observer, but not to main delegate yet

        when (internalState) {
            State.SENT_GREETING -> {
                internalState = State.READING_AUTH_RESPONSE
                socks5AdapterLogger.info("Greeting sent. Reading auth method response (2 bytes).")
                rawSocket?.readDataTo(length = 2)
            }
            State.SENT_CONNECT_REQUEST -> {
                internalState = State.READING_CONNECT_REPLY_HEADER
                socks5AdapterLogger.info("Connect request sent. Reading connect reply header (4 bytes: VER,REP,RSV,ATYP).")
                // Some SOCKS servers might send BND.ADDR and BND.PORT immediately if address is fixed size (IPv4/IPv6)
                // Reading 4 bytes for VER, REP, RSV, ATYP first is safer.
                // Swift read 5 bytes: VER, REP, RSV, ATYP, first_byte_of_BND.ADDR or DOMAIN_LEN
                // Let's read 4 first.
                rawSocket?.readDataTo(length = 4)
            }
            State.FORWARDING -> {
                // This is a write completion for data being forwarded.
                delegate?.get()?.didWrite(data, this)
            }
            else -> {
                socks5AdapterLogger.warn("didWrite called in unexpected state: {}", internalState)
            }
        }
    }

    // Called by RawTCPSocketDelegate when data is received from SOCKS5 proxy
    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        super.didRead(data, from) // Signals event via observer

        when (internalState) {
            State.READING_AUTH_RESPONSE -> {
                if (data.size == 2 && data[0] == SOCKS_VERSION_5 && data[1] == SOCKS5_AUTH_METHOD_NONE) {
                    println("INFO: SOCKS5Adapter: Auth method NO_AUTHENTICATION chosen by server. Sending connect request.")
                    sendConnectRequest()
                } else {
                    val errorMsg = "SOCKS5 Auth method negotiation failed. Received: ${data.joinToString { it.toUByte().toString(16) }}"
                    socks5AdapterLogger.error(errorMsg)
                    handleConnectionFailure(IOException(errorMsg))
                }
            }
            State.READING_CONNECT_REPLY_HEADER -> { // We read 4 bytes: VER, REP, RSV, ATYP
                if (data.size < 4 || data[0] != SOCKS_VERSION_5) {
                    val errorMsg = "Invalid SOCKS5 connect reply header: ${data.joinToString { it.toUByte().toString(16) }}"
                    socks5AdapterLogger.error(errorMsg)
                    handleConnectionFailure(IOException(errorMsg))
                    return
                }
                val replyCode = data[1]
                if (replyCode != SOCKS5_REPLY_SUCCESS) {
                    val errorMsg = "SOCKS5 server denied connection. Reply code: $replyCode"
                    socks5AdapterLogger.error(errorMsg)
                    handleConnectionFailure(IOException(errorMsg))
                    return
                }
                connectReplyAtyp = data[3]
                val remainingBytesToRead = when (connectReplyAtyp) {
                    SOCKS5_ATYP_IPV4 -> 4 + 2 // Remaining IP (4 bytes) + Port (2 bytes)
                    SOCKS5_ATYP_IPV6 -> 16 + 2 // Remaining IP (16 bytes) + Port (2 bytes)
                    SOCKS5_ATYP_DOMAINNAME -> {
                        // Next byte is domain length (L), then L bytes for domain, then 2 for port.
                        // We need to read the length byte first.
                        // This state machine needs to be more granular if we read byte by byte here.
                        // For now, assume the Swift logic of reading a fixed chunk then more was simplified.
                        // Let's read just the domain length byte.
                        socks5AdapterLogger.info("Connect reply ATYP is DOMAIN. Reading domain length (1 byte).")
                        internalState = State.READING_CONNECT_REPLY_ADDR_PORT // Special sub-state for domain
                        rawSocket?.readDataTo(length = 1) // Read the length byte for domain
                        return // Don't proceed further in this didRead call for domain
                    }
                    else -> {
                        val errorMsg = "Unknown ATYP in SOCKS5 connect reply: $connectReplyAtyp"
                        socks5AdapterLogger.error(errorMsg)
                        handleConnectionFailure(IOException(errorMsg))
                        return
                    }
                }
                internalState = State.READING_CONNECT_REPLY_ADDR_PORT
                socks5AdapterLogger.info("Connect reply header OK. Reading BND.ADDR & BND.PORT ({} bytes).", remainingBytesToRead)
                rawSocket?.readDataTo(length = remainingBytesToRead)
            }
            State.READING_CONNECT_REPLY_ADDR_PORT -> {
                // This state is now more complex due to ATYP_DOMAINNAME potentially having two reads.
                // If connectReplyAtyp was DOMAINNAME, `data` here is the single length byte.
                if (connectReplyAtyp == SOCKS5_ATYP_DOMAINNAME && data.size == 1) {
                    val domainLength = data[0].toUByte().toInt()
                    val remainingBytesToRead = domainLength + 2 // Domain + Port
                    socks5AdapterLogger.info("Domain length is {}. Reading domain and port ({} bytes).", domainLength, remainingBytesToRead)
                    // Still in READING_CONNECT_REPLY_ADDR_PORT but waiting for the rest
                    rawSocket?.readDataTo(length = remainingBytesToRead)
                    return // Don't mark as forwarding yet
                }
                // If we are here, it means we've read the BND.ADDR and BND.PORT
                // (or for domain, domain_name + BND.PORT after reading length separately)
                socks5AdapterLogger.info("Received full SOCKS5 connect reply. Handshake successful. BND.ADDR/PORT data size: {}.", data.size)
                internalState = State.FORWARDING
                _status = SocketStatus.ESTABLISHED // Now the SOCKS5 tunnel is established
                observer?.signal(AdapterSocketEvent.Connected(this)) // Signal internal event
                observer?.signal(AdapterSocketEvent.ReadyForForward(this))
                delegate?.get()?.didConnect(this) // Signal delegate that WE (AdapterSocket) are connected
                delegate?.get()?.didBecomeReadyToForward(this)
            }
            State.FORWARDING -> {
                delegate?.get()?.didRead(data, this)
            }
            else -> {
                socks5AdapterLogger.warn("didRead called in unexpected state: {}", internalState)
            }
        }
    }

    private fun sendConnectRequest() {
        val host = session.host
        val port = session.port.toUShort()

        val hostBytes: ByteArray
        val atyp: Byte

        // Determine ATYP and prepare hostBytes
        val parsedIp = IPAddress.parse(host) // Check if host is an IP address
        if (parsedIp != null) {
            if (parsedIp.isIPv4) {
                atyp = SOCKS5_ATYP_IPV4
                hostBytes = parsedIp.addressBytes
            } else { // IPv6
                atyp = SOCKS5_ATYP_IPV6
                hostBytes = parsedIp.addressBytes
            }
        } else { // Domain name
            atyp = SOCKS5_ATYP_DOMAINNAME
            val domainBytes = host.toByteArray(StandardCharsets.UTF_8) // Or ASCII
            if (domainBytes.size > 255) {
                handleConnectionFailure(IOException("Domain name too long for SOCKS5: ${domainBytes.size} bytes"))
                return
            }
            // Payload: [len][domain_bytes]
            hostBytes = ByteArray(1 + domainBytes.size)
            hostBytes[0] = domainBytes.size.toByte()
            System.arraycopy(domainBytes, 0, hostBytes, 1, domainBytes.size)
        }

        // VER | CMD | RSV | ATYP | DST.ADDR | DST.PORT
        //  1  |  1  |  1  |  1   | Variable |    2
        val buffer = ByteBuffer.allocate(1 + 1 + 1 + 1 + hostBytes.size + 2)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(SOCKS_VERSION_5)
        buffer.put(SOCKS5_CMD_CONNECT)
        buffer.put(0x00) // RSV
        buffer.put(atyp)
        buffer.put(hostBytes)
        buffer.putShort(port.toShort())

        val requestData = buffer.array()
        internalState = State.SENT_CONNECT_REQUEST
        socks5AdapterScope.launch { // Using managed scope
            try {
                this@SOCKS5Adapter.write(requestData)
            } catch (e: Exception) {
                socks5AdapterLogger.error("Failed to write SOCKS5 connect request: {}", e.message, e)
                handleConnectionFailure(e)
            }
        }
    }

    private fun handleConnectionFailure(error: Throwable) {
        socks5AdapterLogger.error("Connection or handshake failure: {}", error.message, error)
        internalState = State.STOPPED
        socks5AdapterScope.cancel("SOCKS5Adapter connection failure") // Cancel scope on failure
        // Use AdapterSocket's forceDisconnect to ensure proper state update and delegate notification
        forceDisconnect(becauseOf = error)
    }

    override fun disconnect(becauseOf: Throwable?) {
        internalState = State.STOPPED
        socks5AdapterScope.cancel("SOCKS5Adapter disconnected") // Cancel scope on disconnect
        super.disconnect(becauseOf)
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        internalState = State.STOPPED
        socks5AdapterScope.cancel("SOCKS5Adapter force-disconnected") // Cancel scope on force disconnect
        super.forceDisconnect(becauseOf)
    }

    override fun toString(): String {
        val sessionStr = if (::_session.isInitialized) session.toString() else "uninitialized"
        return "<${this::class.simpleName ?: "SOCKS5Adapter"} proxy:$serverHost:$serverPort session:$sessionStr status:$status internalState:$internalState>"
    }
}
