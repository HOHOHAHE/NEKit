package ProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers // For a default dispatcher if QueueFactory not fully implemented

import org.slf4j.LoggerFactory

// Assuming Port.kt, IPAddress.kt (Utils), Observer.kt, ProxyServerEvent.kt, ObserverFactory.kt (Event)
// GlobalInitializer.kt are available.

// --- Placeholders for dependencies ---

// Placeholder for Tunnel.kt
interface TunnelDelegate {
    fun tunnelDidClose(tunnel: Tunnel)
}

// Making Tunnel a data class for basic equality, or override equals/hashCode if custom logic needed.
// If Tunnel has complex state/identity, default reference equality might be okay if list removal relies on that.
// Swift's `indexOf` on array of class instances uses reference equality (`===`).
open class Tunnel(val proxySocket: ProxySocketInterface) { // Assuming ProxySocketInterface
    private val logger = LoggerFactory.getLogger(Tunnel::class.java)
    var delegate: TunnelDelegate? = null
    open fun forceClose() { logger.info("forceClose() called on {} (TODO: Implement)", this) }
    open fun openTunnel() { logger.info("openTunnel() called on {} (TODO: Implement)", this) }

    // For tunnels.indexOf(tunnel) to work like Swift's reference check for classes
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        // if (javaClass != other?.javaClass) return false // Not needed if only reference check
        return false // Default to reference for non-identical objects
    }
     override fun hashCode(): Int = System.identityHashCode(this)
}

// Placeholder for ProxySocket (interface and a concrete example)
interface ProxySocketInterface {
    // Define methods needed for interaction, e.g., close(), getClientAddress(), etc.
    // For now, it's opaque.
    override fun toString(): String
}
class ConcreteProxySocket(val id: String = "Socket" + kotlin.random.Random.nextInt()) : ProxySocketInterface {
    override fun toString(): String = "ConcreteProxySocket($id)"
}


// Placeholder for QueueFactory (from earlier contexts, adapt if necessary)
object QueueFactory {
    // For executeOnQueueSynchronizedly, we'll use a Mutex in ProxyServer.
    // For other async tasks, a scope can be provided.
    val executionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default) // Example scope
}

// --- End Placeholders ---


/**
 * The base proxy server class.
 * This proxy itself does not listen on any port but manages Tunnels.
 * Subclasses are expected to handle actual network listening and socket acceptance.
 */
open class ProxyServer(
    val address: IPAddress?, // Nullable if it can listen on all interfaces
    val port: Port
) : TunnelDelegate {

    private val logger = LoggerFactory.getLogger(this::class.java) // Logger for the specific subclass instance
    // The type of the proxy server, dynamically set to the simple class name.
    val type: String = this::class.simpleName ?: "ProxyServer"

    // Observer for server events.
    var observer: Observer<ProxyServerEvent>? = null
        private set // Can be set by ObserverFactory or tests, but not freely from outside

    // List of active tunnels. Access must be synchronized.
    protected val tunnels: MutableList<Tunnel> = mutableListOf()
    protected val tunnelsMutex = Mutex() // To protect 'tunnels' list and related state.

    // Description matching Swift's format.
    override fun toString(): String { // Changed from description property to toString override
        return "<$type address:$address port:$port>"
    }

    init {
        // Initialize observer using the factory
        // Assuming ObserverFactory.kt and ProxyServerEvent.kt are available.
        this.observer = ObserverFactory.currentFactory?.getObserverForProxyServer(this)
    }

    /**
     * Starts the proxy server.
     * Concrete implementations should override this to start listening on sockets.
     * Base implementation initializes global components and signals 'started' event.
     */
    @Throws(Exception::class) // Can throw if GlobalInitializer or observer fails
    open suspend fun start() { // Made suspend fun for mutex and potential async init
        tunnelsMutex.withLock { // Mimics executeOnQueueSynchronizedly for state consistency
            GlobalInitializer.initialize() // Ensure global components are up
            observer?.signal(ProxyServerEvent.Started(this))
            logger.info("Started on {}:{}", address, port)
        }
    }

    /**
     * Stops the proxy server.
     * Closes all active tunnels and signals 'stopped' event.
     */
    open suspend fun stop() { // Made suspend fun for mutex and potential async cleanup
        logger.info("Stopping...")
        val tunnelsToClose: List<Tunnel>
        tunnelsMutex.withLock {
            tunnelsToClose = ArrayList(tunnels) // Copy to avoid CME if forceClose modifies list via delegate
            tunnels.clear() // Clear original list under lock
        }

        // Close tunnels outside the lock to avoid potential deadlocks if tunnel.forceClose() is complex
        for (tunnel in tunnelsToClose) {
            try {
                tunnel.forceClose()
            } catch (e: Exception) {
                logger.error("Error force closing tunnel {}: {}", tunnel, e.message, e)
            }
        }

        // Signal stopped event, potentially under lock if observer interaction needs sync,
        // but usually event signaling can be outside main lock.
        // For consistency with Swift's sync block for this:
        tunnelsMutex.withLock {
            observer?.signal(ProxyServerEvent.Stopped(this))
        }
        logger.info("Stopped.")
    }

    /**
     * Called by concrete server implementations when a new client socket is accepted.
     * Wraps the socket in a Tunnel and starts it.
     *
     * @param socket The accepted proxy socket.
     */
    protected open suspend fun didAcceptNewSocket(socket: ProxySocketInterface) { // Made suspend for mutex
        logger.info("Accepted new socket: {}", socket)
        observer?.signal(ProxyServerEvent.NewSocketAccepted(socket, this)) // Assuming ProxySocketEvent takes ProxySocketInterface

        val tunnel = Tunnel(socket) // Create new Tunnel
        tunnel.delegate = this    // This ProxyServer handles tunnel's delegate callbacks

        tunnelsMutex.withLock {
            tunnels.add(tunnel)
        }

        try {
            tunnel.openTunnel() // This might be a suspending call or launch its own async work
        } catch (e: Exception) {
            logger.error("Error opening tunnel for {}: {}", socket, e.message, e)
            // If openTunnel fails, remove it from the list and clean up
            tunnelsMutex.withLock {
                tunnels.remove(tunnel)
            }
            // Optionally, close the socket if tunnel setup failed critically
            // (socket as? Closable)?.close()
        }
    }

    // MARK: TunnelDelegate implementation

    /**
     * Called when a tunnel closes. Removes the tunnel from the active list.
     *
     * @param tunnel The tunnel that closed.
     */
    override fun tunnelDidClose(tunnel: Tunnel) {
        logger.info("Tunnel closed: {}", tunnel)
        observer?.signal(ProxyServerEvent.TunnelClosed(tunnel, this))

        // Launch removal in a coroutine to use Mutex
        QueueFactory.executionScope.launch { // Or a dedicated scope for this class
            tunnelsMutex.withLock {
                // Swift's `tunnels.indexOf(tunnel)` relies on object identity for classes.
                // remove() on MutableList uses equals(). If Tunnel is data class, content equality.
                // If Tunnel is class and equals is not overridden, it's reference equality.
                // The placeholder Tunnel overrides equals for reference equality.
                val removed = tunnels.remove(tunnel)
                if (!removed) {
                    logger.warn("Attempted to remove unknown tunnel: {}", tunnel)
                }
            }
        }
    }
}
