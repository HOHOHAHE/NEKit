// Assuming HTTPAdapter.kt is available in this package or imported.
// Assuming HTTPAuthentication.kt (Utils) and RawSocketFactory.kt (RawSocket) are available
// for the superclass constructor's default arguments if used.

/**
 * Adapter for connecting to a remote host through an HTTP proxy using TLS (HTTPS proxy).
 * The connection to the proxy server itself is secured with TLS.
 *
 * Extends [HTTPAdapter] and sets the `secured` flag to true.
 */
class SecureHTTPAdapter(
    serverHost: String,
    serverPort: Int,
    auth: HTTPAuthentication?,
    // Allow passing a specific rawSocket, otherwise HTTPAdapter's default (from RawSocketFactory) will be used.
    initialRawSocket: RawTCPSocketProtocol? = RawSocketFactory.getRawSocket()
) : HTTPAdapter(
    serverHost = serverHost,
    serverPort = serverPort,
    auth = auth,
    secured = true, // Key difference: connection to proxy is secured
    initialRawSocket = initialRawSocket
) {

    init {
        // Specific initialization for SecureHTTPAdapter, if any, beyond what HTTPAdapter does.
        // For now, primarily relies on the `secured = true` passed to super.
        println("INFO: SecureHTTPAdapter instance created for secure proxy $serverHost:$serverPort (Auth: ${auth != null}).")
    }

    // It inherits openSocketWith and other necessary methods from HTTPAdapter.
    // The `secured = true` flag passed to HTTPAdapter's constructor will ensure that
    // HTTPAdapter.openSocketWith calls rawSocket.connectTo(..., enableTLS = true, ...).

    override fun toString(): String {
        val sessionStr = if (::_session.isInitialized) session.toString() else "uninitialized"
        return "<${this::class.simpleName ?: "SecureHTTPAdapter"} proxy:$serverHost:$serverPort auth:${auth != null} secured:$secured session:$sessionStr status:$status internalState: ${ (this as HTTPAdapter).getInternalStateForDebug() }>"
        // Need to expose internalState for debug if HTTPAdapter keeps it private.
        // For now, just using public properties. If internalState was protected in HTTPAdapter:
        // return "<${this::class.simpleName} proxy:$serverHost:$serverPort auth:${auth != null} secured:$secured session:$sessionStr status:$status internalState:$internalState>"

    }
}

// To make the toString() in SecureHTTPAdapter more informative about its internal state,
// HTTPAdapter would need to expose its internalState, e.g., by making it protected
// or providing a protected getter.
// Modify HTTPAdapter.kt's internalState for this:
//    protected enum class State { ... }
//    protected var internalState: State = State.IDLE
// Then SecureHTTPAdapter can access it in its toString().
// For now, I'll add a debug accessor to HTTPAdapter for this.

// --- Conceptual addition to HTTPAdapter.kt for SecureHTTPAdapter's toString() ---
/*
open class HTTPAdapter(...) {
    // ... (existing code) ...
    protected enum class State { IDLE, CONNECTING_TO_PROXY, SENDING_CONNECT_REQUEST, READING_CONNECT_RESPONSE, FORWARDING, STOPPED }
    protected var internalState: State = State.IDLE // Changed from private to protected

    // For debugging purposes by subclasses like SecureHTTPAdapter
    internal fun getInternalStateForDebug(): State {
        return internalState
    }
    // ...
}
*/
// --- End conceptual addition ---
