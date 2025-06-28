import org.slf4j.LoggerFactory

import Messages.ConnectSession
import Socket.AdapterSocket.AdapterSocket
import Socket.AdapterSocket.HTTPAdapter // Corrected import for HTTPAdapter
import RawSocket.RawSocketFactory // Assuming this is the correct import for RawSocketFactory
import Utils.HTTPAuthentication // Assuming this is the correct import for HTTPAuthentication

// Assuming HTTPAuthenticationAdapterFactory.kt is available.
// It is the superclass, so its import is handled by the package structure.


/**
 * Factory specifically for creating [HTTPAdapter] instances.
 * It extends [HTTPAuthenticationAdapterFactory] to inherit host, port, and auth properties.
 */
open class HTTPAdapterFactory(
    serverHost: String,
    serverPort: Int,
    auth: HTTPAuthentication?
) : HTTPAuthenticationAdapterFactory(serverHost, serverPort, auth) {

    /**
     * Creates and returns an [HTTPAdapter] configured with the factory's
     * server host, port, and authentication details.
     * The underlying raw socket for the adapter is obtained from [RawSocketFactory].
     *
     * @param session The connect session for which the adapter is being created.
     *                While passed, the base HTTPAdapter placeholder doesn't use it in constructor.
     * @return A new [HTTPAdapter] instance.
     */
    override fun getAdapterFor(session: ConnectSession): AdapterSocket {
        // The serverHost, serverPort, and auth are properties of the superclass HTTPAuthenticationAdapterFactory
        val rawSocket = RawSocketFactory.getRawSocket() // Create a new raw socket
        // Pass the raw socket to the HTTPAdapter constructor
        return HTTPAdapter(this.serverHost, this.serverPort, this.auth, rawSocket)
    }
}
