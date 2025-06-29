package com.example.nekit.Socket.ProxySocket

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import org.slf4j.LoggerFactory // Added import

import com.example.nekit.Messages.HTTPHeader
import com.example.nekit.Messages.HTTPHeaderParseException
import com.example.nekit.Utils.HTTPStreamScanner
import com.example.nekit.Utils.ReadAction
import com.example.nekit.Utils.ProcessedData
import com.example.nekit.Utils.HTTPConstants
import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Socket.SocketDelegate
import com.example.nekit.Event.Event.ProxySocketEvent
import com.example.nekit.Opt

// --- Placeholder for HTTP Data Constants ---
// TODO: Move to a common HTTP utilities file if not already there.
object HTTPDataConstants {
    val DOUBLE_CRLF: ByteArray = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    val CONNECT_SUCCESS_RESPONSE: ByteArray = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
}
// --- End Placeholder ---

/**
 * Handles client connections for an HTTP proxy server.
 * It parses incoming HTTP requests, manages the HTTP CONNECT handshake,
 * and forwards data between the client and an appropriate AdapterSocket.
 */
class HTTPProxySocket(
    clientRawSocket: RawTCPSocketProtocol,
    observe: Boolean = true
) : ProxySocket(clientRawSocket, observe) {

    private val httpProxyLogger = LoggerFactory.getLogger(HTTPProxySocket::class.java)

    private enum class ReadState(val descriptionVal: String) {
        INVALID("invalid"),
        READING_FIRST_HEADER("reading first header"),
        PENDING_FIRST_HEADER("waiting to send first header"), // When first request is not CONNECT, header is held
        READING_HEADER("reading header (forwarding)"),     // For subsequent requests on keep-alive
        READING_CONTENT("reading content (forwarding)"),
        STOPPED("stopped");
        override fun toString(): String = descriptionVal
    }

    private enum class WriteState(val descriptionVal: String) {
        INVALID("invalid"),
        SENDING_CONNECT_RESPONSE("sending response header for CONNECT"),
        FORWARDING("forwarding data"), // Changed from "waiting to begin forwarding" for clarity
        STOPPED("stopped");
        override fun toString(): String = descriptionVal
    }

    lateinit var destinationHost: String
        private set
    var destinationPort: Int = 0
        private set

    private var currentHttpHeader: HTTPHeader? = null
    private val httpScanner: HTTPStreamScanner = HTTPStreamScanner() // From Utils

    private var internalReadStatus: ReadState = ReadState.INVALID
    private var internalWriteStatus: WriteState = WriteState.INVALID

    var isConnectCommand: Boolean = false
        private set

    // Expose descriptions as in Swift
    val readStatusDescription: String get() = internalReadStatus.toString()
    val writeStatusDescription: String get() = internalWriteStatus.toString()

    init {
        httpProxyLogger.info("Created with rawSocket: {}", rawSocket)
    }

    override fun openSocket() {
        super.openSocket() // Signals event, base class is ready
        if (isCancelled) return

        httpProxyLogger.info("openSocket() called for session {}. Reading initial HTTP header.", session)
        internalReadStatus = ReadState.READING_FIRST_HEADER
        // Read up to the end of the first HTTP header block
        rawSocket.readDataTo(delimiter = HTTPDataConstants.DOUBLE_CRLF, maxLength = Opt.MAX_NWTCPSOCKET_READ_DATA_SIZE)
    }

    /**
     * Called by the Tunnel to request more data from the client AFTER the initial header/request
     * has been processed and a ConnectSession has been established.
     * This is used for forwarding client data to the established adapter.
     */
    override fun readData() { // This method is called by the Tunnel for data forwarding phase
        if (isCancelled) return

        if (internalReadStatus == ReadState.PENDING_FIRST_HEADER) {
            // This means the first request was NOT CONNECT, and its header (currentHttpHeader)
            // was held back. Now, the Tunnel is asking for it.
            val headerData = currentHttpHeader?.toByteArray() // Uses HTTPHeader.toString().toByteArray()
            if (headerData != null) {
                httpProxyLogger.info("Forwarding pending first header ({} bytes) for session {}.", headerData.size, session)
                // This data goes to the Tunnel's delegate (which is usually the AdapterSocket via Tunnel)
                delegate?.get()?.didRead(headerData, this)
                internalReadStatus = ReadState.READING_CONTENT // Expect body or next request
                // After sending header, check HTTPStreamScanner for what to read next (content or new header)
                processNextScannerAction()
            } else {
                httpProxyLogger.error("Pending first header is null for session {}. Cannot forward.", session)
                forceDisconnect(becauseOf = IllegalStateException("Pending header was null for session $session"))
            }
            return
        }

        // For ongoing reading in forwarding or content reading states
        processNextScannerAction()
    }

    private fun processNextScannerAction() {
        if (isCancelled) return
        when (val action = httpScanner.nextAction) { // httpScanner is from Utils
            is ReadAction.ReadContent -> {
                internalReadStatus = ReadState.READING_CONTENT
                if (action.length > 0) {
                    httpProxyLogger.debug("Reading content of length: {} for session {}", action.length, session)
                    rawSocket.readDataTo(length = action.length)
                } else { // Length 0 or -1 means read anything available (e.g. for chunked or EOF)
                    httpProxyLogger.debug("Reading any available content for session {}", session)
                    rawSocket.readData()
                }
            }
            is ReadAction.ReadHeader -> {
                internalReadStatus = ReadState.READING_HEADER
                httpProxyLogger.debug("Reading next HTTP header for session {}", session)
                rawSocket.readDataTo(delimiter = HTTPDataConstants.DOUBLE_CRLF, maxLength = Opt.MAX_NWTCPSOCKET_READ_DATA_SIZE)
            }
            is ReadAction.Stop -> {
                internalReadStatus = ReadState.STOPPED
                httpProxyLogger.info("HTTPStreamScanner indicated stop for session {}. Disconnecting.", session)
                disconnect(becauseOf = IOException("HTTP stream scanner indicated end or error for session $session."))
            }
        }
    }


    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        super.didRead(data, from) // Signals ProxySocketEvent.ReadData (observer only)

        if (isCancelled) return

        val processedDataResult: ProcessedData // Renamed from HTTPStreamScanner.Result to avoid conflict
        try {
            // httpScanner is from Utils, its input method returns ProcessedData (Header or Content)
            processedDataResult = httpScanner.input(data)
        } catch (e: Exception) {
            httpProxyLogger.error("HTTPStreamScanner error for session {}: {}", session, e.message, e)
            forceDisconnect(becauseOf = e)
            return
        }

        when (internalReadStatus) {
            ReadState.READING_FIRST_HEADER -> {
                if (processedDataResult is ProcessedData.Header) {
                    val header = processedDataResult.httpHeader // This is the HTTPHeader from Utils
                    currentHttpHeader = header
                    // TODO: Implement HTTPHeader.removeProxyHeaders() and rewriteToRelativePath() if they modify state
                    // header.removeProxyHeaders()
                    // header.rewriteToRelativePath()

                    destinationHost = header.host ?: "" // HTTPHeader from Utils provides these
                    destinationPort = header.port
                    isConnectCommand = header.isConnect

                    if (destinationHost.isEmpty()) {
                        httpProxyLogger.error("Failed to parse host from HTTP header for session (raw data processing).")
                        forceDisconnect(becauseOf = HTTPHeaderParseException.MissingHostField)
                        return
                    }

                    // Create ConnectSession using host/port from header
                    // Using the factory method for ConnectSession for failable init logic
                    this.session = ConnectSession.create(destinationHost, destinationPort, fakeIPEnabled = false) // Fake IP not relevant for proxy target

                    if (this.session == null) {
                        httpProxyLogger.error("Failed to create ConnectSession for {}:{} (raw data processing).", destinationHost, destinationPort)
                        forceDisconnect(becauseOf = IllegalStateException("ConnectSession creation failed for HTTP proxy for $destinationHost:$destinationPort"))
                        return
                    }

                    httpProxyLogger.info("Parsed first header for session {}. Host: {}, Port: {}, CONNECT: {}", this.session, destinationHost, destinationPort, isConnectCommand)
                    observer?.signal(ProxySocketEvent.ReceivedRequest(this.session!!, this))
                    delegate?.get()?.didReceive(this.session!!, this) // Notify Tunnel to create AdapterSocket

                    if (!isConnectCommand) {
                        // For non-CONNECT, the header itself is data to be sent to adapter.
                        // Adapter will be established, then respondTo called, then this header is sent.
                        internalReadStatus = ReadState.PENDING_FIRST_HEADER
                        // Don't read more from client yet. Wait for respondTo, then Tunnel calls readData().
                    } else {
                        // For CONNECT, after this, we wait for respondTo, then send "200 OK", then forward.
                        // No body expected from client for CONNECT.
                        internalReadStatus = ReadState.FORWARDING // Or a specific "AWAITING_CONNECT_RESPONSE" state
                        // No more reads from client until CONNECT is established.
                    }

                } else { // Expected header but got content or error
                    httpProxyLogger.error("Expected HTTP header for session {}, but StreamScanner result was not Header.", session)
                    forceDisconnect(becauseOf = HTTPHeaderParseException.MalformedHeader)
                }
            }
            ReadState.READING_HEADER -> { // Subsequent header (e.g. after a keep-alive)
                if (processedDataResult is ProcessedData.Header) {
                    val header = processedDataResult.httpHeader
                    currentHttpHeader = header
                    // TODO: header.removeProxyHeaders(); header.rewriteToRelativePath()
                    httpProxyLogger.info("Parsed subsequent header for session {}. Forwarding to delegate.", session)
                    delegate?.get()?.didRead(header.toByteArray(), this) // Forward header data
                    // After forwarding header, decide what to read next based on scanner
                    processNextScannerAction()
                } else {
                    httpProxyLogger.error("Expected subsequent HTTP header for session {}, but StreamScanner result was not Header.", session)
                    forceDisconnect(becauseOf = HTTPHeaderParseException.MalformedHeader)
                }
            }
            ReadState.READING_CONTENT -> { // HTTP body content
                if (processedDataResult is ProcessedData.Content) {
                    val content = processedDataResult.data
                    httpProxyLogger.debug("Received content ({} bytes) for session {}. Forwarding to delegate.", content.size, session)
                    delegate?.get()?.didRead(content, this)
                    // After forwarding content, decide what to read next
                    processNextScannerAction()
                } else {
                     httpProxyLogger.error("Expected HTTP content for session {}, but StreamScanner result was not Content.", session)
                    forceDisconnect(becauseOf = HTTPHeaderParseException.MalformedHeader)
                }
            }
            else -> {
                httpProxyLogger.warn("Data read in unexpected read state: {} for session {}", internalReadStatus, session)
            }
        }
    }

    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        super.didWrite(data, by) // Signals ProxySocketEvent.WroteData

        when (internalWriteStatus) {
            WriteState.SENDING_CONNECT_RESPONSE -> {
                httpProxyLogger.info("Successfully sent CONNECT response for session {}. Transitioning to forwarding.", session)
                internalWriteStatus = WriteState.FORWARDING
                internalReadStatus = ReadState.FORWARDING // Also ready to forward reads from client
                _status = SocketStatus.ESTABLISHED // Overall socket status
                observer?.signal(ProxySocketEvent.ReadyForForward(this))
                delegate?.get()?.didBecomeReadyToForward(this)
                // After CONNECT OK, client might send data. Start reading.
                readData()
            }
            WriteState.FORWARDING -> {
                delegate?.get()?.didWrite(data, this)
            }
            else -> {
                httpProxyLogger.warn("Data written in unexpected write state: {} for session {}", internalWriteStatus, session)
            }
        }
    }

    override fun respondTo(adapter: AdapterSocket) {
        super.respondTo(adapter) // Signals event
        if (isCancelled) return

        if (isConnectCommand) {
            httpProxyLogger.info("Adapter ready for CONNECT for session {}. Sending 200 OK to client.", session)
            internalWriteStatus = WriteState.SENDING_CONNECT_RESPONSE
            // write() is suspend in RawTCPSocketProtocol, ProxySocket.write calls it.
            // Launch in a scope or make respondTo suspend.
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                try {
                    write(HTTPDataConstants.CONNECT_SUCCESS_RESPONSE)
                    // didWrite callback will handle transition to FORWARDING state.
                } catch (e: Exception) {
                    httpProxyLogger.error("Failed to write CONNECT success response for session {}: {}", session, e.message, e)
                    forceDisconnect(becauseOf = e)
                }
            }
        } else {
            // For non-CONNECT, adapter is ready, means we can start forwarding data from client.
            // The first request's header might be pending.
            httpProxyLogger.info("Adapter ready for non-CONNECT for session {}. Transitioning to forwarding.", session)
            internalWriteStatus = WriteState.FORWARDING
            // If header was pending, Tunnel will call readData() which will send it.
            // If no header pending (e.g. client sent body before this), then just ready.
            if (internalReadStatus != ReadState.PENDING_FIRST_HEADER) {
                internalReadStatus = ReadState.FORWARDING // Or READING_HEADER/CONTENT based on scanner
            }
             _status = SocketStatus.ESTABLISHED
            observer?.signal(ProxySocketEvent.ReadyForForward(this))
            delegate?.get()?.didBecomeReadyToForward(this)
            // If there was data from client buffered by scanner, or if scanner expects next header, trigger read.
            // This is complex. Simplest is just to be ready. Tunnel will call readData().
            // If readStatus was PENDING_FIRST_HEADER, Tunnel's call to readData() will send it.
            // If client might have sent more data, start reading.
            if (internalReadStatus != ReadState.PENDING_FIRST_HEADER) {
                 readData()
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
