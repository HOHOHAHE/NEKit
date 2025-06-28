package Messages

import java.net.MalformedURLException
import java.net.URI // Using URI for more robust path/host/port parsing than URL for arbitrary strings
import java.nio.charset.StandardCharsets

import Utils.HTTPURL // Corrected import

// --- HTTPHeaderException Definitions ---
sealed class HTTPHeaderParseException(message: String) : Exception(message) {
    object MalformedHeader : HTTPHeaderParseException("Malformed HTTP header.")
    object InvalidRequestLine : HTTPHeaderParseException("Invalid HTTP request line.")
    object InvalidHeaderField : HTTPHeaderParseException("Invalid HTTP header field.")
    object InvalidConnectURL : HTTPHeaderParseException("Invalid URL for CONNECT method.")
    object InvalidConnectPort : HTTPHeaderParseException("Invalid port in CONNECT URL.")
    object InvalidURL : HTTPHeaderParseException("Invalid URL in request path.")
    object MissingHostField : HTTPHeaderParseException("Host field is missing in headers and path is not absolute URL.")
    object InvalidHostField : HTTPHeaderParseException("Invalid Host header field format.")
    object InvalidHostPort : HTTPHeaderParseException("Invalid port in Host header field.")
    object InvalidContentLength : HTTPHeaderParseException("Invalid Content-Length header value.")
    object IllegalEncoding : HTTPHeaderParseException("Header data contains illegal encoding.")
}
// --- End HTTPHeaderException Definitions ---

open class HTTPHeader(
    var method: String,
    var path: String,
    var httpVersion: String,
    var host: String,
    var port: Int,
    var headers: MutableList<Pair<String, String>> = mutableListOf(),
    var contentLength: Int = 0,
    var isConnect: Boolean = false,
    var rawHeaderBytes: ByteArray? = null
) {
    // Store parsed URI/HTTPURL for reference if needed, similar to foundationURL/homemadeURL
    var parsedUri: URI? = null
    var parsedHttpUrl: HTTPURL? = null


    @Throws(HTTPHeaderParseException::class)
    constructor(headerString: String) : this("", "", "", "", 80) { // Initial dummy values
        val lines = headerString.split("\r\n")
        if (lines.size < 2) { // Minimum: Request-Line, one empty line for end of headers (or one header + empty line)
            // If strictly following Swift: lines.count >=3 (req, header, end) or (req, end, end for just req line)
            // A valid header block ends with an empty line. So, Request-Line + Empty-Line = 2 lines.
            // If headers exist: Request-Line + Header1 + ... + Empty-Line.
            // Swift's lines[1..<lines.count-2] implies at least one header line and two trailing \r\n.
            // Let's adjust to: must have request line, and must end with an empty line.
            // The last line will be empty if headerString ends with "\r\n\r\n".
            // If headerString is "GET / HTTP/1.1\r\n\r\n", lines = ["GET / HTTP/1.1", "", ""], count = 3. lines.count-2 is 1. 1..<1 is empty. OK.
            // If "GET / HTTP/1.1\r\nHost: foo\r\n\r\n", lines = ["GET / HTTP/1.1", "Host: foo", "", ""], count = 4. lines.count-2 is 2. 1..<2 processes "Host: foo". OK.
             if (lines.size < 2 || lines.last().isNotEmpty() || (lines.size > 1 && lines[lines.size-2].isNotEmpty() && lines.last().isEmpty() )) {
                 // This check is tricky. A minimal valid might be "GET / HTTP/1.1\r\n\r\n"
                 // split gives ["GET / HTTP/1.1", ""]. Size 2. lines.count-2 = 0. 1..<0 is empty.
                 // The original Swift `lines[1..<lines.count-2]` was for header lines *between* request and final empty lines.
                 // A simpler check:
                 if (!headerString.endsWith("\r\n\r\n") || lines.firstOrNull()?.isEmpty() == true) {
                    //throw HTTPHeaderParseException.MalformedHeader // TODO: Refine this check based on actual min valid header.
                 }
             }
        }


        val requestParts = lines[0].split(" ", limit = 3)
        if (requestParts.size != 3) throw HTTPHeaderParseException.InvalidRequestLine
        this.method = requestParts[0]
        this.path = requestParts[1]
        this.httpVersion = requestParts[2]

        // Headers are from the second line up to the line before the final empty line.
        // If headerString = "REQ\r\nHDR1\r\nHDR2\r\n\r\n", lines = ["REQ", "HDR1", "HDR2", ""]. Loop 1 until lines.size-1.
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break // End of headers

            val headerParts = line.split(":", limit = 2)
            if (headerParts.size != 2) throw HTTPHeaderParseException.InvalidHeaderField
            this.headers.add(Pair(headerParts[0].trim(), headerParts[1].trim()))
        }

        if (this.method.equals("CONNECT", ignoreCase = true)) {
            this.isConnect = true
            val urlInfo = this.path.split(":", limit = 2)
            if (urlInfo.size != 2) throw HTTPHeaderParseException.InvalidConnectURL
            this.host = urlInfo[0]
            this.port = urlInfo[1].toIntOrNull() ?: throw HTTPHeaderParseException.InvalidConnectPort
            this.contentLength = 0
        } else {
            this.isConnect = false
            var hostResolved = false

            // Try parsing path as absolute URI
            try {
                // Using java.net.URI as it's better for parsing URLs that might not have scheme
                // or might be just paths. For absolute URLs, it can give host/port.
                val uri = URI(this.path)
                if (uri.isAbsolute && uri.host != null) {
                    this.parsedUri = uri
                    this.host = uri.host
                    this.port = if (uri.port != -1) uri.port else (if (uri.scheme == "https") 443 else 80)
                    hostResolved = true
                }
            } catch (e: Exception) { // MalformedURLException or URISyntaxException
                // Ignore, try HTTPURL or Host header
            }

            if (!hostResolved) {
                // Try custom HTTPURL parser if path is not a full URI recognized by java.net.URI
                // This was the fallback in Swift if Foundation.URL failed.
                val httpUrl = HTTPURL.parse(this.path) // Assuming HTTPURL.kt is available
                if (httpUrl?.host != null) {
                    this.parsedHttpUrl = httpUrl
                    this.host = httpUrl.host!!
                    this.port = httpUrl.port ?: 80
                    hostResolved = true
                }
            }

            if (!hostResolved) {
                // Try Host header
                var hostHeaderValue = ""
                for ((key, value) in this.headers) {
                    if (key.equals("Host", ignoreCase = true)) {
                        hostHeaderValue = value
                        break
                    }
                }
                if (hostHeaderValue.isEmpty()) throw HTTPHeaderParseException.MissingHostField

                val urlInfo = hostHeaderValue.split(":", limit = 2)
                if (urlInfo.size == 2) {
                    this.host = urlInfo[0]
                    this.port = urlInfo[1].toIntOrNull() ?: throw HTTPHeaderParseException.InvalidHostPort
                } else if (urlInfo.size == 1) {
                    this.host = urlInfo[0]
                    this.port = 80 // Default port
                } else {
                     throw HTTPHeaderParseException.InvalidHostField
                }
            }

            // Parse Content-Length
            this.contentLength = 0 // Default
            for ((key, value) in this.headers) {
                if (key.equals("Content-Length", ignoreCase = true)) {
                    this.contentLength = value.toIntOrNull() ?: throw HTTPHeaderParseException.InvalidContentLength
                    break
                }
            }
        }
    }

    @Throws(HTTPHeaderParseException::class)
    constructor(headerData: ByteArray) : this(
        try {
            String(headerData, StandardCharsets.UTF_8) // Or ASCII, but UTF-8 is more common for headers too
        } catch (e: Exception) {
            throw HTTPHeaderParseException.IllegalEncoding
        }
    ) {
        this.rawHeaderBytes = headerData
    }

    operator fun get(key: String): String? {
        return headers.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second
    }

    fun toByteArray(): ByteArray {
        return toString().toByteArray(StandardCharsets.UTF_8) // Standard for HTTP is often US-ASCII for headers, UTF-8 for body
                                                            // For headers, UTF-8 is generally safe if server supports it.
    }

    override fun toString(): String {
        val builder = StringBuilder()
        builder.append("$method $path $httpVersion\r\n")
        for ((key, value) in headers) {
            builder.append("$key: $value\r\n")
        }
        builder.append("\r\n")
        return builder.toString()
    }

    fun addHeader(key: String, value: String) {
        headers.add(Pair(key, value))
    }

    fun rewriteToRelativePath() {
        // Only rewrite if path does not start with "/" (i.e., it's an absolute URL)
        if (path.isNotEmpty() && path[0] != '/') {
            // Try parsing with java.net.URI first to get path component
            try {
                val uri = URI(path)
                var newPath = uri.rawPath // Use rawPath to preserve encoding if any
                if (uri.rawQuery != null) newPath += "?" + uri.rawQuery
                if (uri.rawFragment != null) newPath += "#" + uri.rawFragment

                if (newPath.isNotEmpty()) {
                    this.path = newPath
                } else if (path.contains("/")) { // Fallback for simple "http://host/" case where path might be empty
                    this.path = "/"
                }
                // Else, if no path component, it might be problematic, leave as is or set to "/"
            } catch (e: Exception) {
                // Fallback to regex if URI parsing fails (e.g. malformed URL but regex might still find a path part)
                val matched = URLMatcher.matchRelativePath(path)
                if (matched != null) {
                    this.path = matched
                }
                // If still no change, path remains absolute. Consider logging.
            }
        }
    }


    fun removeHeader(keyToRemove: String): String? {
        val iterator = headers.iterator()
        while (iterator.hasNext()) {
            val header = iterator.next()
            if (header.first.equals(keyToRemove, ignoreCase = true)) {
                iterator.remove()
                return header.second
            }
        }
        return null
    }

    fun removeProxyHeaders() { // Renamed from removeProxyHeader for clarity
        val proxyHeaderKeys = listOf("Proxy-Authenticate", "Proxy-Authorization", "Proxy-Connection")
        // Iterate carefully if removing while iterating, or use removeIf
        headers.removeAll { headerPair ->
            proxyHeaderKeys.any { it.equals(headerPair.first, ignoreCase = true) }
        }
    }

    private object URLMatcher { // Was nested struct URL in Swift
        // Original Regex: "http.?:\\/\\/.*?(\\/.*)"
        // This regex captures the first slash and everything after it, from an absolute URL.
        private val relativePathRegex = Regex("https?://[^/]+(/[^?]*)(\\?.*)?", RegexOption.IGNORE_CASE)
        // Improved regex: Captures path part more reliably, excluding query.
        // Group 1: Path (e.g., "/index.html")
        // Group 2 (optional): Query (e.g., "?foo=bar")

        fun matchRelativePath(url: String): String? {
            return relativePathRegex.find(url)?.let { matchResult ->
                val pathPart = matchResult.groups[1]?.value
                // val queryPart = matchResult.groups[2]?.value // If you need query too
                pathPart // Return only the path part
            }
        }
    }
}
