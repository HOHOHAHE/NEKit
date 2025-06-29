package com.example.nekit.Socket.AdapterSocket.Factory

import org.slf4j.LoggerFactory

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Socket.AdapterSocket.HTTPAdapter
import com.example.nekit.RawSocket.RawSocketFactory
import com.example.nekit.Utils.HTTPAuthentication

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

    // This is the method that AdapterFactoryParser.parseServerAdapterFactory expects.
    // It creates a new instance of the factory itself, which then can be used to getAdapterFor.
    // This is a common pattern in Swift where `Type` objects are passed around.
    // In Kotlin, we pass the class directly or a lambda that constructs it.
    // Here, it's a factory method on the factory itself.
    override fun create(serverHost: String, serverPort: Int, auth: HTTPAuthentication?): HTTPAuthenticationAdapterFactory {
        return HTTPAdapterFactory(serverHost, serverPort, auth)
    }
}