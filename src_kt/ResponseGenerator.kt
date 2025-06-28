// Assuming ConnectSession.kt from the Messages package is available and imported.
import Messages.ConnectSession

/**
 * Base class for generating responses, typically HTTP responses, based on a ConnectSession.
 *
 * Subclasses should override [generateResponse] to provide specific response content.
 */
open class ResponseGenerator(
    /**
     * The ConnectSession associated with the response to be generated.
     * Provides context like requested host, port, etc.
     */
    val session: ConnectSession
) {

    /**
     * Generates the response data.
     *
     * The base implementation returns an empty ByteArray. Subclasses should override this
     * to construct meaningful response data (e.g., a full HTTP response including
     * status line, headers, and body).
     *
     * @return A ByteArray containing the generated response.
     */
    open fun generateResponse(): ByteArray {
        // Base implementation returns an empty response.
        // Subclasses will provide actual HTTP response generation.
        // For example, an HTTP 403 Forbidden response:
        // val statusLine = "HTTP/1.1 403 Forbidden\r\n"
        // val headers = "Content-Type: text/html\r\nConnection: close\r\n\r\n"
        // val body = "<html><body><h1>403 Forbidden</h1></body></html>"
        // return (statusLine + headers + body).toByteArray(StandardCharsets.UTF_8)
        return ByteArray(0)
    }
}
