package com.example.nekit.GeoIP

// TODO: Add dependency for MaxMind GeoIP2 Java API (e.g., com.maxmind.geoip2:geoip2:version)
import com.maxmind.geoip2.DatabaseReader
import com.maxmind.geoip2.exception.GeoIp2Exception
// import com.maxmind.geoip2.model.CountryResponse // Not directly needed if only getting isoCode
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import org.slf4j.LoggerFactory

// Placeholder GeoIPDatabaseReader interface and GeoIPCountry data class are removed.

object GeoIP {

    private val logger = LoggerFactory.getLogger(GeoIP::class.java)
    private var database: DatabaseReader? = null

    /**
     * Initializes the GeoIP database from the given file path.
     * This method should be called once during application startup.
     *
     * @param databasePath The path to the GeoIP2 database file (e.g., GeoLite2-Country.mmdb).
     */
    fun initialize(databasePath: String) {
        if (database != null) {
            logger.info("GeoIP database already initialized. Skipping re-initialization.")
            return
        }
        try {
            val dbFile = File(databasePath)
            if (!dbFile.exists()) {
                logger.error("GeoIP database file not found at: {}", dbFile.absolutePath)
                throw IOException("GeoIP database file not found at ${dbFile.absolutePath}")
            }
            database = DatabaseReader.Builder(dbFile).build()
            logger.info("GeoIP database loaded successfully from: {}", dbFile.absolutePath)
        } catch (e: IOException) {
            logger.error("Failed to load GeoIP database (IOException): {}", e.message, e)
            database = null // Ensure database is null on failure
        } catch (e: GeoIp2Exception) {
            logger.error("Failed to initialize GeoIP database reader (GeoIp2Exception): {}", e.message, e)
            database = null // Ensure database is null on failure
        } catch (e: Exception) { // Catch any other unexpected exceptions during init
            logger.error("An unexpected error occurred during GeoIP database initialization: {}", e.message, e)
            database = null
        }
    }

    /**
     * Looks up the country ISO code for a given IP address.
     *
     * @param ipAddress The IP address string to look up (e.g., "8.8.8.8" or "2001:4860:4860::8888").
     * @return A String containing the country ISO code (e.g., "US", "GB") if found, or null otherwise.
     *         Returns null if the IP address string is invalid, not found in the database,
     *         or if the database is not initialized.
     */
    fun lookUp(ipAddress: String): String? {
        if (database == null) {
            logger.warn("GeoIP database not initialized. Cannot perform lookup for IP '{}'.", ipAddress)
            return null
        }
        if (ipAddress.isBlank()) {
            logger.debug("GeoIP lookup called with blank IP address.")
            return null
        }

        val inetAddress: InetAddress = try {
            InetAddress.getByName(ipAddress)
        } catch (e: UnknownHostException) {
            logger.warn("GeoIP lookup: Could not convert IP string '{}' to InetAddress: {}", ipAddress, e.message)
            return null
        }

        return try {
            val response = database?.country(inetAddress) // This is CountryResponse
            response?.country?.isoCode // Returns String?
        } catch (e: GeoIp2Exception) {
            logger.warn("GeoIP lookup failed for IP '{}' ({}): {}", ipAddress, inetAddress.hostAddress, e.message)
            null
        } catch (e: IOException) { // DatabaseReader.country() can throw IOException
            logger.error("GeoIP lookup I/O error for IP '{}': {}", ipAddress, e.message, e)
            null
        } catch (e: Exception) { // Catch any other unexpected runtime errors from the lookup
            logger.error("Unexpected error during GeoIP lookup for IP '{}': {}", ipAddress, e.message, e)
            null
        }
    }
}
