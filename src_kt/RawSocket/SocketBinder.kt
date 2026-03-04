package com.example.nekit.RawSocket

import java.net.DatagramSocket
import java.net.Socket

/**
 * Interface to allow platform-specific network binding (e.g., Android's ConnectivityManager.Network)
 * to be injected into the shared networking module without adding a direct dependency on the platform SDK.
 */
interface SocketBinder {
    /**
     * Binds a standard TCP Socket to a specific network.
     */
    fun bindSocket(socket: Socket)

    /**
     * Binds a UDP DatagramSocket to a specific network.
     */
    fun bindDatagramSocket(socket: DatagramSocket)
}

/**
 * Global factory/holder for the SocketBinder instances.
 * 
 * The platform application (e.g., Android App) should set the appropriate binders here
 * during initialization or when network connectivity changes.
 */
object SocketBinderFactory {
    /**
     * The binder to use when forcing connections over the Cellular network.
     */
    var cellularBinder: SocketBinder? = null
}
