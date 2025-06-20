// Assuming AdapterFactory.kt is available.

/**
 * Base factory for creating adapter sockets that connect to a specific server.
 * It holds the server's host and port information.
 *
 * Subclasses are expected to override [getAdapterFor] to create specific types of
 * [AdapterSocket] instances (e.g., HTTP, SOCKS5) configured with this server information.
 * If [getAdapterFor] is not overridden, it defaults to the behavior in [AdapterFactory],
 * which typically creates a [DirectAdapter].
 *
 * @property serverHost The hostname or IP address of the server.
 * @property serverPort The port number of the server.
 */
open class ServerAdapterFactory(
    val serverHost: String,
    val serverPort: Int
) : AdapterFactory() { // Extends the base AdapterFactory

    // No override of getAdapterFor here.
    // If a subclass of ServerAdapterFactory does not override getAdapterFor,
    // it will inherit AdapterFactory.getAdapterFor, which (in my current translation)
    // returns a DirectAdapter. This might be desired if a "server" could also be direct,
    // but more likely, concrete subclasses like HTTPAdapterFactory, SOCKS5AdapterFactory, etc.,
    // MUST override getAdapterFor to return their specific adapter type.
}
