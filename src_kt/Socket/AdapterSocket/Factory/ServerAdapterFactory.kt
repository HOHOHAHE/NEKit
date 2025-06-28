package Socket.AdapterSocket.Factory

import Messages.ConnectSession
import Socket.AdapterSocket.AdapterSocket
import org.slf4j.LoggerFactory // Added import

/**
 * Base class for adapter factories that require a server host and port.
 *
 * In Swift, this was `ServerAdapterFactory`.
 */
open class ServerAdapterFactory(
    open val serverHost: String,
    open val serverPort: Int
) : AdapterFactory() {

    private val logger = LoggerFactory.getLogger(ServerAdapterFactory::class.java)

    /**
     * Default constructor.
     */
    constructor() : this("", 0) // Primary constructor must be called

    /**
     * Builds an adapter socket for the given connect session.
     * Subclasses should override this method to provide specific adapter types.
     * The base implementation returns a [DirectAdapter] (via superclass method).
     *
     * @param session The connect session for which to create an adapter.
     * @return An [AdapterSocket] instance.
     */
    override fun getAdapterFor(session: ConnectSession): AdapterSocket {
        logger.warn("ServerAdapterFactory.getAdapterFor called, returning default DirectAdapter. Subclass should override.")
        return super.getAdapterFor(session)
    }
}