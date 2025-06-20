import java.util.Base64
import java.nio.charset.StandardCharsets

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

    /**
     * Encodes the credential as "username:password" in Base64.
     *
     * @return The Base64 encoded string, or null if encoding to UTF-8 fails (highly unlikely for typical strings).
     */
    fun encode(): String? {
        val authString = "$username:$password"
        return try {
            // Standard Base64 encoding for HTTP headers does not include line feeds.
            // Swift's `endLineWithLineFeed` option is unusual for typical HTTP Basic Auth.
            // Using standard Base64 encoding.
            Base64.getEncoder().encodeToString(authString.toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            // In practice, UTF-8 encoding of a string like "$username:$password" should not fail.
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
        // If encode() can return null, decide on behavior: throw exception or return placeholder?
        // For HTTP Basic Auth, a missing token is problematic. Throwing or ensuring encode() doesn't fail is better.
        // Given typical username/password, encode() should succeed.
        return "Basic ${encodedCredentials!!}"
    }
}
