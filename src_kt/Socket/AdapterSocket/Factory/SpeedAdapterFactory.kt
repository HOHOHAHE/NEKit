import org.slf4j.LoggerFactory

import Messages.ConnectSession
import Socket.AdapterSocket.AdapterSocket
import Socket.AdapterSocket.SpeedAdapter // Corrected import for SpeedAdapter
import RawSocket.RawSocketFactory // Assuming this is the correct import for RawSocketFactory


/**
 * Factory for creating [SpeedAdapter] instances.
 * A [SpeedAdapter] manages multiple underlying adapter sockets and potentially chooses
 * one based on speed or other criteria.
 */
open class SpeedAdapterFactory : AdapterFactory() {

    /**
     * List of underlying adapter factories and their associated delays (or other metrics).
     * This list is expected to be populated after the factory is initialized.
     * Format: Pair(AdapterFactory, Int representing delay/metric).
     */
    var adapterFactories: List<Pair<AdapterFactory, Int>>? = null // Was implicitly unwrapped in Swift

    /**
     * Default constructor.
     * The `adapterFactories` list should be set before calling `getAdapterFor`.
     */
    override constructor() : super()

    /**
     * Creates and returns a [SpeedAdapter].
     * The [SpeedAdapter] is configured with [AdapterSocket] instances created from the
     * `adapterFactories` list stored in this factory.
     *
     * @param session The connect session for which the adapter is being created.
     * @return A new [SpeedAdapter] instance.
     * @throws IllegalStateException if `adapterFactories` is not set or is empty.
     */
    override fun getAdapterFor(session: ConnectSession): AdapterSocket {
        val currentAdapterFactories = this.adapterFactories ?: throw IllegalStateException("SpeedAdapterFactory: adapterFactories list not set.")
        if (currentAdapterFactories.isEmpty()) {
            throw IllegalStateException("SpeedAdapterFactory: adapterFactories list is empty.")
        }

        val speedAdapter = SpeedAdapter() // Create the SpeedAdapter instance

        // Create actual AdapterSocket instances from the factories
        val concreteAdapters = currentAdapterFactories.map { (factory, delay) ->
            val adapterInstance = factory.getAdapterFor(session)
            // The Swift code: adapter.socket = RawSocketFactory.getRawSocket()
            // This line is problematic if `factory.getAdapterFor(session)` already returns a fully
            // configured adapter with its own socket (which my Kotlin adapter placeholders do).
            // Re-assigning `adapterInstance.rawSocket` here would overwrite the socket it just set up.
            // For now, I will assume that `factory.getAdapterFor(session)` returns a complete AdapterSocket.
            // If the intent was that SpeedAdapterFactory *always* assigns a new raw socket, then
            // AdapterSocket.rawSocket would need to be public var and this assignment would be:
            // adapterInstance.rawSocket = RawSocketFactory.getRawSocket()
            // This seems like it could break encapsulation of how individual adapters get their sockets.
            // TODO: Review if this reassignment of `rawSocket` is necessary or correct.
            // For now, I'm following the pattern that adapters handle their own socket creation.
            // The `adapter.socket = RawSocketFactory.getRawSocket()` line from Swift is thus omitted here,
            // assuming `factory.getAdapterFor(session)` provides a ready-to-use adapter.
            Pair(adapterInstance, delay)
        }
        speedAdapter.adapters = concreteAdapters
        return speedAdapter
    }
}
