import org.slf4j.LoggerFactory

import Messages.ConnectSession
import Socket.AdapterSocket.AdapterSocket
import Socket.AdapterSocket.DirectAdapter // Corrected import for DirectAdapter
import RawSocket.RawSocketFactory // Assuming this is the correct import for RawSocketFactory


/**
 * Base class for adapter factories.
 * An adapter factory is responsible for creating instances of [AdapterSocket].
 */
open class AdapterFactory {

    constructor() {
        // Default constructor
    }

    /**
     * Builds an adapter socket for the given connect session.
     * Subclasses should override this method to provide specific adapter types.
     * The base implementation returns a [DirectAdapter].
     *
     * @param session The connect session for which to create an adapter.
     * @return An [AdapterSocket] instance.
     */
    open fun getAdapterFor(session: ConnectSession): AdapterSocket {
        // Default behavior is to return a direct adapter.
        return getDirectAdapter(session) // Pass session for context if DirectAdapter needs it
    }

    /**
     * Helper method to get a [DirectAdapter].
     * It creates a DirectAdapter and initializes its underlying raw socket.
     *
     * @return A [DirectAdapter] instance.
     */
    // Made it take session for consistency, though DirectAdapter itself might not use session in constructor
    // but rather in openSocketWith. RawSocketFactory.getRawSocket() is general.
    fun getDirectAdapter(session: ConnectSession): AdapterSocket {
        // RawSocketFactory.getRawSocket() creates the low-level socket.
        // DirectAdapter's constructor now takes this socket.
        val rawSocket = RawSocketFactory.getRawSocket() // Assuming this returns a valid RawTCPSocketProtocol
        val adapter = DirectAdapter() // DirectAdapter constructor now handles its own raw socket via RawSocketFactory
        // The original Swift code: adapter.socket = RawSocketFactory.getRawSocket()
        // My AdapterSocket.kt takes rawSocket in constructor or expects it to be set.
        // The DirectAdapter placeholder above now gets it from RawSocketFactory itself.
        // If AdapterSocket.rawSocket was settable: (adapter as? AdapterSocket)?.rawSocket = rawSocket
        return adapter
    }
}

/**
 * Factory for creating [DirectAdapter] instances.
 * This class is often used for type checking (e.g., `is DirectAdapterFactory`)
 * to identify direct connection rules.
 */
class DirectAdapterFactory : AdapterFactory() {
    // Inherits default constructor and getAdapterFor (which returns DirectAdapter)
    // No override needed if it just produces DirectAdapters via the base class logic.
    // If it needed to *ensure* DirectAdapter or do special setup, it could override.
    // For now, its existence for type checking is the main point as per Swift comment.

    // If there was a need for specific DirectAdapter creation:
    // override fun getAdapterFor(session: ConnectSession): AdapterSocket {
    //     return getDirectAdapter(session) // Explicitly call, or new DirectAdapter(...)
    // }
}
