package Utils

import java.util.Base64
import java.nio.charset.StandardCharsets
import org.slf4j.LoggerFactory // Moved import to top of file

/**
 * Helper class for HTTP basic authentication credentials.
 */
data class HTTPAuthentication(
    /**
     * The username of the credential.
     */
    val username: String,
    /**
     * The password of the credential.
     */
    val password: String
) {

    companion object { // Companion object for the logger
        private val logger = LoggerFactory.getLogger(HTTPAuthentication::class.java)
    }

    /**
     * Encodes the credential as "username:password" in Base64.
     *
     * @return The Base64 encoded string, or null if encoding to UTF-8 fails (highly unlikely for typical strings).
     */
    fun encode(): String? {
        val authString = "$username:$password"
        return try {
            // Standard Base64 encoding for HTTP headers does not include line feeds.
            Base64.getEncoder().encodeToString(authString.toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            // In practice, UTF-8 encoding of a string like "$username:$password" should not fail
            // for typical credential characters, but good to log if it does.
            logger.error("Error encoding HTTP authentication credentials to Base64: {}", e.message, e)
            null
        }
    }

    /**
     * Returns the full header field value for an HTTP `Authorization` header
     * using Basic authentication.
     *
     * Example: "Basic dXNlcjpwYXNzd29yZA=="
     *
     * @return The formatted authentication string for the header.
     * @throws NullPointerException if the Base64 encoding fails (which is highly unlikely).
     */
    fun toAuthHeaderValue(): String {
        val encodedCredentials = encode()
            ?: throw IllegalArgumentException("Failed to encode HTTP authentication credentials; username or password might contain invalid characters for UTF-8 or Base64 encoding.")
        return "Basic $encodedCredentials"
    }
}
