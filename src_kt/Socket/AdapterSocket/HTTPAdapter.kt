package com.example.nekit.Socket.AdapterSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob // Added for managed scope
import kotlinx.coroutines.cancel // Added for managed scope
import java.io.IOException
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory

import com.example.nekit.Utils.HTTPConstants
import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Utils.HTTPAuthentication
import com.example.nekit.RawSocket.RawSocketFactory
import com.example.nekit.Socket.SocketStatus
import com.example.nekit.Event.Event.AdapterSocketEvent
import com.example.nekit.Messages.EventSource
import com.example.nekit.Event.ObserverFactory
import com.example.nekit.Utils.HTTPURL

/**
 * Adapter for connecting to a remote host through an HTTP proxy using the CONNECT method.
 *
 * @property serverHost The hostname or IP address of the HTTP proxy server.
 * @property serverPort The port number of the HTTP proxy server.
 * @property auth Optional [HTTPAuthentication] credentials for the proxy server.
 * @property secured If true, the initial connection to the proxy server itself will be over TLS.
 *                   This is typically set by [SecureHTTPAdapter].
 * @param initialRawSocket The underlying raw socket to use for communication. Defaults to a new one from [RawSocketFactory].
 */
open class HTTPAdapter(
    val serverHost: String,
    val serverPort: Int,
    val auth: HTTPAuthentication?,
    protected var secured: Boolean = false, // Subclass (SecureHTTPAdapter) will set this to true
    initialRawSocket: RawTCPSocketProtocol? = RawSocketFactory.getRawSocket()
) : AdapterSocket(initialRawSocket, observe = true) {

   // Managed CoroutineScope for the HTTPAdapter lifecycle
   private val httpAdapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val httpAdapterLogger = LoggerFactory.getLogger(this::class.java) // Specific logger

    private enum class State {
        IDLE,
        CONNECTING_TO_PROXY, // Raw socket connecting to proxy
        SENDING_CONNECT_REQUEST, // Sending HTTP CONNECT method
        READING_CONNECT_RESPONSE, // Reading response from proxy (e.g., "HTTP/1.1 200 OK")
        FORWARDING, // CONNECT successful, data is being forwarded
        STOPPED
    }

    private var internalState: State = State.IDLE

    init {
        httpAdapterLogger.info("Created for proxy {}:{} (Auth: {}, Secured: {})", serverHost, serverPort, auth != null, secured)
    }

    override fun openSocketWith(session: ConnectSession) {
        super.openSocketWith(session) // Sets this.session, observer, rawSocket.delegate

        val currentRawSocket = rawSocket ?: run {
            httpAdapterLogger.error("Raw socket is null in openSocketWith for session: {}.", session)
            _status = SocketStatus.CLOSED // Mark as closed/failed by AdapterSocket's standard
            this.delegate?.get()?.didErrorOccur(IllegalStateException("Raw socket not available for HTTPAdapter"), this)
            this.delegate?.get()?.didDisconnect(this)
            return
        }

        if (isCancelled) {
            httpAdapterLogger.info("openSocketWith called on a cancelled socket for session: {}", session)
            return
        }

        internalState = State.CONNECTING_TO_PROXY
        _status = SocketStatus.CONNECTING // Overall status
        observer?.signal(AdapterSocketEvent.SocketOpened(this, session))

        httpAdapterLogger.info("Connecting to proxy {}:{} (TLS: {}) for session: {}", serverHost, serverPort, secured, session)

        httpAdapterScope.launch { // Using managed scope
            try {
                currentRawSocket.connectTo(
                    host = serverHost,
                    port = serverPort,
                    enableTLS = secured, // Connect with TLS if this is a SecureHTTPAdapter scenario
                    tlsSettings = null // Or provide actual settings
                )
                // If connectTo succeeds, RawTCPSocketDelegate.didConnect (implemented below) will be called.
            } catch (e: Exception) {
                httpAdapterLogger.error("Failed to connect to proxy {}:{}: {}", serverHost, serverPort, e.message, e)
                handleConnectionFailure(e)
            }
        }
    }

    // Called by RawTCPSocketDelegate when connection to proxy is established (including TLS if `secured`)
    override fun didConnect(socket: RawTCPSocketProtocol) {
        // Note: super.didConnect(socket) in AdapterSocket sets _status = .ESTABLISHED and calls delegate.didConnect.
        // This is too early for HTTPAdapter, as we still need to send CONNECT and get proxy's OK.
        // So, we don't call super.didConnect() here. Instead, manage status and call delegate upon successful CONNECT response.

        httpAdapterLogger.info("Raw socket connected to proxy {}:{}. Current internal state: {}", serverHost, serverPort, internalState)

        if (internalState == State.CONNECTING_TO_PROXY) {
            // Now send the HTTP CONNECT request
            val targetHost = session.host // Host from ConnectSession
            val targetPort = session.port

            // Construct CONNECT request (Simplified: assumes US-ASCII for headers, which is standard)
            // Using HTTP/1.1. Could use session.httpVersion if available and relevant.
            var connectRequestString = "CONNECT $targetHost:$targetPort ${HTTPConstants.HTTP_1_1}${HTTPConstants.CRLF}"
            connectRequestString += "Host: $targetHost:$targetPort${HTTPConstants.CRLF}"
            // Content-Length for CONNECT is typically 0 or absent. Some proxies might require it.
            connectRequestString += "Content-Length: 0${HTTPConstants.CRLF}"
            // User-Agent is optional but good practice.
            connectRequestString += "User-Agent: NEKit-Kotlin-Adapter/1.0${HTTPConstants.CRLF}"
            connectRequestString += "Proxy-Connection: keep-alive${HTTPConstants.CRLF}" // Common for CONNECT

            auth?.encode()?.let { encodedAuth ->
                connectRequestString += "Proxy-Authorization: Basic $encodedAuth${HTTPConstants.CRLF}"
            }
            connectRequestString += HTTPConstants.CRLF // End of headers

            val requestData = connectRequestString.toByteArray(StandardCharsets.US_ASCII)

            internalState = State.SENDING_CONNECT_REQUEST
            httpAdapterLogger.debug("Sending CONNECT request:\n{}", connectRequestString) // DEBUG for potentially large header

            httpAdapterScope.launch { // Using managed scope
                try {
                    // AdapterSocket.write calls rawSocket.write
                    this@HTTPAdapter.write(requestData) // Use our own write which calls rawSocket.write
                    // After write completes, didWrite will be called. Then we need to read response.
                } catch (e: Exception) {
                    httpAdapterLogger.error("Failed to write CONNECT request: {}", e.message, e)
                    handleConnectionFailure(e)
                }
            }
        } else {
            httpAdapterLogger.warn("didConnect called in unexpected state: {}", internalState)
        }
    }

    // Called by RawTCPSocketDelegate after CONNECT request is written
    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        super.didWrite(data, by) // Signals event via observer

        if (internalState == State.SENDING_CONNECT_REQUEST) {
            internalState = State.READING_CONNECT_RESPONSE
            httpAdapterLogger.info("CONNECT request sent. Reading proxy response.")
            // Read until double CRLF (end of HTTP headers)
            // AdapterSocket.readDataTo(delimiter) calls rawSocket.readDataTo(delimiter)
            this.rawSocket?.readDataTo(HTTPConstants.DOUBLE_CRLF, Opt.MAX_NWTCPSCAN_LENGTH)
        } else if (internalState == State.FORWARDING) {
            // This is a write completion for data being forwarded.
            delegate?.get()?.didWrite(data, this)
        }
    }

    // Called by RawTCPSocketDelegate when data is received from proxy
    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        super.didRead(data, from) // Signals event via observer

        if (internalState == State.READING_CONNECT_RESPONSE) {
            val responseString = String(data, StandardCharsets.US_ASCII) // Or ISO_8859_1
            httpAdapterLogger.debug("Received proxy response for CONNECT:\n{}", responseString) // DEBUG for potentially large header
            // Basic check for "HTTP/1.x 2xx" status line.
            // A more robust parser would be needed for full HTTP compliance.
            val lines = responseString.lines()
            if (lines.isNotEmpty()) {
                val statusLine = lines[0]
                if (statusLine.startsWith("HTTP/1.0 2", ignoreCase = true) ||
                    statusLine.startsWith("HTTP/1.1 2", ignoreCase = true)) { // Check for 2xx success

                    httpAdapterLogger.info("CONNECT request successful. Tunnel established.")
                    internalState = State.FORWARDING
                    _status = SocketStatus.ESTABLISHED // Overall status update

                    observer?.signal(AdapterSocketEvent.ReadyForForward(this))
                    delegate?.get()?.didBecomeReadyToForward(this)
                    // If there was any data buffered after the response (e.g. from TCP segmenting),
                    // it should be passed on. This simple parser assumes `data` is only the header block.
                    // A proper HTTP parser would handle this.
                } else {
                    httpAdapterLogger.error("Proxy CONNECT request failed: {}", statusLine)
                    val error = HTTPAdapterException.ProxyConnectResponseInvalid("Proxy CONNECT failed: $statusLine")
                    observer?.signal(AdapterSocketEvent.ErrorOccurred(error, this))
                    handleConnectionFailure(error)
                }
            } else {
                httpAdapterLogger.error("Empty response from proxy for CONNECT.")
                val error = HTTPAdapterException.ProxyConnectResponseInvalid("Empty response from proxy for CONNECT.")
                observer?.signal(AdapterSocketEvent.ErrorOccurred(error, this))
                handleConnectionFailure(error)
            }
        } else if (internalState == State.FORWARDING) {
            // Data received from target server, through the proxy. Forward to our delegate.
            delegate?.get()?.didRead(data, this)
        } else {
            httpAdapterLogger.warn("didRead called in unexpected state: {}", internalState)
        }
    }

    override fun didDisconnect(socket: RawTCPSocketProtocol) {
        httpAdapterLogger.info("Underlying raw socket disconnected. Current internal state: {}", internalState)
        val wasForwarding = (internalState == State.FORWARDING)
        internalState = State.STOPPED
        httpAdapterScope.cancel("HTTPAdapter disconnected") // Cancel scope on disconnect
        super.didDisconnect(socket) // Let AdapterSocket base handle common disconnect logic
        if (!wasForwarding && _status != SocketStatus.CLOSED) {
            // If disconnect happened before FORWARDING state, it might be a connection setup error.
            // The delegate.didDisconnect is already called by super.didDisconnect.
            // Any specific error related to HTTP phase should have been caught earlier.
        }
    }

    override fun didErrorOccur(error: Throwable, on: RawTCPSocketProtocol) {
        httpAdapterLogger.error("Raw socket error. Current internal state: {}. Error: {}", internalState, error.message, error)
        internalState = State.STOPPED
        httpAdapterScope.cancel("HTTPAdapter error occurred") // Cancel scope on error
        super.didErrorOccur(error, on) // Let AdapterSocket base handle common error logic (signals event, force disconnects)
    }


    private fun handleConnectionFailure(error: Throwable) {
        internalState = State.STOPPED
        // Call AdapterSocket's forceDisconnect to ensure proper cleanup and delegate notification
        // The error passed here will be used in session.disconnected and AdapterSocketEvent.ErrorOccurred
        forceDisconnect(becauseOf = error)
    }

    override fun toString(): String {
        val sessionStr = if (::_session.isInitialized) session.toString() else "uninitialized"
        return "<${this::class.simpleName ?: "HTTPAdapter"} proxy:$serverHost:$serverPort auth:${auth != null} secured:$secured session:$sessionStr status:$status internalState:$internalState>"
    }
}
