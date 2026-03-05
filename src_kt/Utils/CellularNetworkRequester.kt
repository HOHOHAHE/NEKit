package com.example.nekit.Utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A highly independent utility that requests and manages Android's Cellular Network
 * entirely via reflection. This avoids any App-level initialization requirements.
 * 
 * Uses a singleton pattern for the NetworkCallback to prevent leaking callbacks 
 * and hitting Android's maximum NetworkRequest limit.
 */
@SuppressLint("NewApi", "PrivateApi")
object CellularNetworkRequester {

    private val logger = LoggerFactory.getLogger(CellularNetworkRequester::class.java)

    // Cached references
    @Volatile
    private var cachedContext: Context? = null
    @Volatile
    private var cachedConnectivityManager: ConnectivityManager? = null

    // State management for the single NetworkCallback
    private val isRequesting = AtomicBoolean(false)
    private var currentCellularNetwork: Network? = null
    
    // We use a CompletableDeferred to allow Coroutines to suspend and wait for the async callback
    private var networkDeferred = CompletableDeferred<Network?>()

    /**
     * Obtains the application Context via Reflection.
     */
    private fun getApplicationContext(): Context? {
        if (cachedContext != null) return cachedContext
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication")
            val context = currentApplicationMethod.invoke(null) as? Context
            cachedContext = context
            context
        } catch (e: Exception) {
            logger.warn("CellularNetworkRequester: Failed to get Application Context via reflection. Running on pure JVM?")
            null
        }
    }

    /**
     * Lazily initialize the ConnectivityManager.
     */
    private fun getConnectivityManager(): ConnectivityManager? {
        if (cachedConnectivityManager != null) return cachedConnectivityManager
        val context = getApplicationContext() ?: return null
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cachedConnectivityManager = cm
            cm
        } catch (e: Exception) {
            logger.error("Failed to get ConnectivityManager", e)
            null
        }
    }

    // A single globally held callback to prevent exhausting Android's Callback limit
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            logger.info("CellularNetworkRequester: Cellular network is now AVAILABLE: {}", network)
            currentCellularNetwork = network
            // If any coroutine is waiting for the network, complete it.
            if (!networkDeferred.isCompleted) {
                networkDeferred.complete(network)
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            logger.warn("CellularNetworkRequester: Cellular network LOST: {}", network)
            if (currentCellularNetwork == network) {
                currentCellularNetwork = null
            }
            // Reset the deferred so next requests will wait again
            if (networkDeferred.isCompleted) {
                networkDeferred = CompletableDeferred()
            }
        }
    }

    /**
     * Suspends the current coroutine until a Cellular Network is available, 
     * or returns null if it times out or is unsupported (e.g., macOS runLocal).
     * 
     * @param timeoutMillis Maximum time to wait for the cellular modem to wake up and assign an IP.
     */
    suspend fun requestAndGetCellularNetwork(timeoutMillis: Long = 4000L): Network? {
        // Fast path 1: Not running on Android
        if (!PlatformDetector.isAndroid) {
            return null
        }

        // Fast path 2: We already have an active cellular network
        val activeNet = currentCellularNetwork
        if (activeNet != null) {
            logger.trace("CellularNetworkRequester: Using cached cellular network: {}", activeNet)
            return activeNet
        }

        val cm = getConnectivityManager()
        if (cm == null) {
            logger.warn("CellularNetworkRequester: ConnectivityManager unavailable.")
            return null
        }

        // Register the callback ONCE to request the system to wake up the Cellular modem
        if (isRequesting.compareAndSet(false, true)) {
            logger.info("CellularNetworkRequester: Initiating NetworkRequest to trigger Cellular wake-up...")
            try {
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                
                // Note: We intentionally do not unregister this callback in this architecture.
                // We keep the cellular request alive so the modem doesn't sleep as long as the process runs.
                // If aggressive power saving is needed, we'd have to track active sockets and unregister when 0.
                cm.requestNetwork(request, networkCallback)
            } catch (e: Exception) {
                logger.error("CellularNetworkRequester: Failed to request cellular network: {}", e.message)
                isRequesting.set(false)
                return null
            }
        }

        // Suspend and wait for the `onAvailable` callback to fire
        logger.debug("CellularNetworkRequester: Waiting up to {}ms for Cellular Network to become available...", timeoutMillis)
        return withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(timeoutMillis) {
                    // This will suspend until networkDeferred.complete() is called in onAvailable
                    networkDeferred.await()
                }
            } catch (e: Exception) {
                logger.error("CellularNetworkRequester: Error while waiting for deferred: {}", e.message)
                null
            }
        }
    }
}
