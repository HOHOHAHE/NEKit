package nekit.Config

import java.util.concurrent.atomic.AtomicReference

/**
 * Singleton object to manage global network state, such as the current active
 * network interface that NEKit proxies should bind to for outbound connections.
 * 
 * Apps can modify `currentActiveInterface` based on platform-specific monitors 
 * (e.g., Android's ConnectivityManager or iOS's NWPathMonitor) when detecting 
 * that the default Wi-Fi connection is unavailable.
 */
object GlobalNetworkManager {
    // Thread-safe reference to the active network interface preference.
    private val activeInterfaceRef = AtomicReference(NetworkInterfaceType.DEFAULT)

    var activeInterface: NetworkInterfaceType
        get() = activeInterfaceRef.get()
        set(value) { activeInterfaceRef.set(value) }
}
