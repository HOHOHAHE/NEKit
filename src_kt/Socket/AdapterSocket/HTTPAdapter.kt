import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.charset.StandardCharsets

// Assuming AdapterSocket.kt, RawTCPSocketProtocol.kt, ConnectSession.kt, HTTPAuthentication.kt (Utils),
// RawSocketFactory.kt, SocketStatus.kt, AdapterSocketEvent.kt, EventSource.kt,
// ObserverFactory.kt, HTTPURL.kt (Utils) are available.

// --- HTTPAdapterException Definitions ---
sealed class HTTPAdapterException(message: String) : Exception(message) {
    object InvalidURLInSession : HTTPAdapterException("Invalid URL constructed from session host/port for CONNECT request.")
    object ConnectRequestSerializationFailure : HTTPAdapterException("Failed to serialize HTTP CONNECT request header.")
    object ProxyConnectResponseInvalid : HTTPAdapterException("Invalid or non-200 response from proxy for CONNECT request.")
}
// --- End HTTPAdapterException Definitions ---

// --- Placeholder for HTTP Constants ---
// TODO: Move to a common HTTP utilities file.
object HTTPConstants {
    val CRLF = "\r\n"
    val DOUBLE_CRLF: ByteArray = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    val HTTP_1_1 = "HTTP/1.1"
}
// --- End Placeholder for HTTP Constants ---

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
        println("INFO: HTTPAdapter created for proxy $serverHost:$serverPort (Auth: ${auth != null}, Secured: $secured)")
    }

    override fun openSocketWith(session: ConnectSession) {
        super.openSocketWith(session) // Sets this.session, observer, rawSocket.delegate

        val currentRawSocket = rawSocket ?: run {
            System.err.println("ERROR: HTTPAdapter: Raw socket is null in openSocketWith.")
            _status = SocketStatus.CLOSED // Mark as closed/failed by AdapterSocket's standard
            this.delegate?.get()?.didErrorOccur(IllegalStateException("Raw socket not available for HTTPAdapter"), this)
            this.delegate?.get()?.didDisconnect(this)
            return
        }

        if (isCancelled) {
            println("INFO: HTTPAdapter: openSocketWith called on a cancelled socket for session: $session")
            return
        }

        internalState = State.CONNECTING_TO_PROXY
        _status = SocketStatus.CONNECTING // Overall status
        observer?.signal(AdapterSocketEvent.SocketOpened(this, session))

        println("INFO: HTTPAdapter: Connecting to proxy $serverHost:$serverPort (TLS: $secured) for session: $session")

        val connectionScope = CoroutineScope(Dispatchers.Default) // TODO: Use a managed scope from AdapterSocket
        connectionScope.launch {
            try {
                currentRawSocket.connectTo(
                    host = serverHost,
                    port = serverPort,
                    enableTLS = secured, // Connect with TLS if this is a SecureHTTPAdapter scenario
                    tlsSettings = null // Or provide actual settings
                )
                // If connectTo succeeds, RawTCPSocketDelegate.didConnect (implemented below) will be called.
            } catch (e: Exception) {
                System.err.println("ERROR: HTTPAdapter: Failed to connect to proxy $serverHost:$serverPort: ${e.message}")
                handleConnectionFailure(e)
            }
        }
    }

    // Called by RawTCPSocketDelegate when connection to proxy is established (including TLS if `secured`)
    override fun didConnect(socket: RawTCPSocketProtocol) {
        // Note: super.didConnect(socket) in AdapterSocket sets _status = .ESTABLISHED and calls delegate.didConnect.
        // This is too early for HTTPAdapter, as we still need to send CONNECT and get proxy's OK.
        // So, we don't call super.didConnect() here. Instead, manage status and call delegate upon successful CONNECT response.

        println("INFO: HTTPAdapter: Raw socket connected to proxy $serverHost:$serverPort. Current internal state: $internalState")

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
            println("INFO: HTTPAdapter: Sending CONNECT request:\n$connectRequestString")

            val writeScope = CoroutineScope(Dispatchers.Default) // TODO: Use managed scope
            writeScope.launch {
                try {
                    // AdapterSocket.write calls rawSocket.write
                    this@HTTPAdapter.write(requestData) // Use our own write which calls rawSocket.write
                    // After write completes, didWrite will be called. Then we need to read response.
                } catch (e: Exception) {
                    System.err.println("ERROR: HTTPAdapter: Failed to write CONNECT request: ${e.message}")
                    handleConnectionFailure(e)
                }
            }
        } else {
            System.err.println("WARN: HTTPAdapter: didConnect called in unexpected state: $internalState")
        }
    }

    // Called by RawTCPSocketDelegate after CONNECT request is written
    override fun didWrite(data: ByteArray?, by: RawTCPSocketProtocol) {
        super.didWrite(data, by) // Signals event via observer

        if (internalState == State.SENDING_CONNECT_REQUEST) {
            internalState = State.READING_CONNECT_RESPONSE
            println("INFO: HTTPAdapter: CONNECT request sent. Reading proxy response.")
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
            println("INFO: HTTPAdapter: Received proxy response for CONNECT:\n$responseString")
            // Basic check for "HTTP/1.x 2xx" status line.
            // A more robust parser would be needed for full HTTP compliance.
            val lines = responseString.lines()
            if (lines.isNotEmpty()) {
                val statusLine = lines[0]
                if (statusLine.startsWith("HTTP/1.0 2", ignoreCase = true) ||
                    statusLine.startsWith("HTTP/1.1 2", ignoreCase = true)) { // Check for 2xx success

                    println("INFO: HTTPAdapter: CONNECT request successful. Tunnel established.")
                    internalState = State.FORWARDING
                    _status = SocketStatus.ESTABLISHED // Overall status update

                    observer?.signal(AdapterSocketEvent.ReadyForForward(this))
                    delegate?.get()?.didBecomeReadyToForward(this)
                    // If there was any data buffered after the response (e.g. from TCP segmenting),
                    // it should be passed on. This simple parser assumes `data` is only the header block.
                    // A proper HTTP parser would handle this.
                } else {
                    System.err.println("ERROR: HTTPAdapter: Proxy CONNECT request failed: $statusLine")
                    val error = HTTPAdapterException.ProxyConnectResponseInvalid("Proxy CONNECT failed: $statusLine")
                    observer?.signal(AdapterSocketEvent.ErrorOccurred(error, this))
                    handleConnectionFailure(error)
                }
            } else {
                System.err.println("ERROR: HTTPAdapter: Empty response from proxy for CONNECT.")
                val error = HTTPAdapterException.ProxyConnectResponseInvalid("Empty response from proxy for CONNECT.")
                observer?.signal(AdapterSocketEvent.ErrorOccurred(error, this))
                handleConnectionFailure(error)
            }
        } else if (internalState == State.FORWARDING) {
            // Data received from target server, through the proxy. Forward to our delegate.
            delegate?.get()?.didRead(data, this)
        } else {
            System.err.println("WARN: HTTPAdapter: didRead called in unexpected state: $internalState")
        }
    }

    override fun didDisconnect(socket: RawTCPSocketProtocol) {
        // This is called from RawTCPSocketDelegate when the underlying raw socket disconnects.
        // AdapterSocket's base implementation already updates status, signals event, and calls delegate.
        println("INFO: HTTPAdapter: Underlying raw socket disconnected. Current internal state: $internalState")
        val wasForwarding = (internalState == State.FORWARDING)
        internalState = State.STOPPED
        super.didDisconnect(socket) // Let AdapterSocket base handle common disconnect logic
        if (!wasForwarding && _status != SocketStatus.CLOSED) {
            // If disconnect happened before FORWARDING state, it might be a connection setup error.
            // The delegate.didDisconnect is already called by super.didDisconnect.
            // Any specific error related to HTTP phase should have been caught earlier.
        }
    }

    override fun didErrorOccur(error: Throwable, on: RawTCPSocketProtocol) {
        // This is called from RawTCPSocketDelegate for errors on the raw socket.
        // AdapterSocket's base implementation signals event and calls forceDisconnect.
        println("ERROR: HTTPAdapter: Raw socket error. Current internal state: $internalState. Error: ${error.message}")
        internalState = State.STOPPED
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
