import org.slf4j.LoggerFactory
import Tunnel.QueueFactory // Corrected import

// This object acts as a holder for the primary queue/dispatcher used by the Resolver concept in the original Swift.
// In Kotlin, the QueueFactory.getProcessingDispatcher() returns a CoroutineDispatcher.
// The 'Resolver' here is a conceptual bridge, not a direct mapping to DNSServer.
object Resolver {
    var queue: kotlinx.coroutines.CoroutineDispatcher? = null // Specific type from QueueFactory
}

/**
 * Handles one-time global initialization tasks.
 *
 * The primary initialization logic is executed when the `initialized` property
 * is first accessed, thanks to Kotlin's `lazy` delegate.
 */
import java.security.Security // Added for Security.addProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider // Added for BouncyCastleProvider

object GlobalInitializer {
    private val logger = LoggerFactory.getLogger(GlobalInitializer::class.java)

    /**
     * A flag that becomes true after the first-time initialization logic is executed.
     * Accessing this property triggers the initialization block if it hasn't run yet.
     */
    private val initialized: Boolean by lazy {
        logger.info("Performing one-time initialization...")
        var success = true
        try {
            // Register BouncyCastle provider
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
                logger.info("BouncyCastle provider registered successfully.")
            } else {
                logger.info("BouncyCastle provider already registered.")
            }
        } catch (e: Exception) {
            logger.error("Failed to register BouncyCastle provider: {}", e.message, e)
            success = false // Mark initialization as failed if BC provider registration fails
        }

        // Original Swift code: Resolver.queue = QueueFactory.getQueue()
        try {
            // Original Swift code: Resolver.queue = QueueFactory.getQueue()
            Resolver.queue = QueueFactory.getProcessingDispatcher()
            logger.info("Resolver.queue has been set.")
        } catch (e: Exception) {
            logger.error("Failed during Resolver.queue initialization: {}", e.message, e)
            success = false // Mark initialization as failed
        }

        success // Return overall success status
    }

    /**
     * Explicitly triggers the one-time global initialization block if it hasn't run yet.
     * Call this method early in the application lifecycle if explicit initialization timing is needed.
     *
     * @return True if initialization was successful (or already completed successfully),
     *         false if initialization failed.
     */
    fun initialize(): Boolean {
        // Accessing the 'initialized' property triggers the lazy block.
        // The return value of this function will be the result of the lazy block (true/false).
        return initialized
    }

    /**
     * Checks if the global initialization has been performed.
     * Note: This will also trigger initialization if it hasn't happened yet.
     *
     * @return True if initialization was successful, false otherwise or if it failed.
     */
    fun isInitialized(): Boolean {
        return initialized
    }
}
