import org.slf4j.LoggerFactory

// Assuming HTTPHeader.kt and Opt.kt will be available in the same package or imported.

// Placeholder for HTTPHeader.swift - Will be properly translated later
// This is a simplified version based on usage in HTTPStreamScanner
data class HTTPHeader(
    val host: String? = null,
    val contentLength: Int = 0,
    val isConnect: Boolean = false,
    // other fields like method, path, version, headers map etc. would be here
) {
    // This constructor is assumed by HTTPStreamScanner
    // The actual parsing logic from ByteArray to HTTPHeader will be complex.
    constructor(headerData: ByteArray) : this(
        // Dummy parsing logic based on what HTTPStreamScanner might expect.
        // A real parser would analyze the byte array.
        host = 호출자_정의_헤더_파싱_로직_필요_host(headerData), // Placeholder for actual parsing
        contentLength = 호출자_정의_헤더_파싱_로직_필요_contentLength(headerData), // Placeholder
        isConnect = 호출자_정의_헤더_파싱_로직_필요_isConnect(headerData) // Placeholder
    ) {
        if (headerData.isEmpty()) throw IllegalArgumentException("Header data cannot be empty")
        // In a real scenario, this constructor would parse the raw headerData
        // to populate the fields of HTTPHeader.
    }
    companion object {
        // No logger here as it's a data class, logging would be in parsing logic if complex
    }
}

// Placeholder functions for parsing logic - these would be part of HTTPHeader proper parsing
// If these were complex, they might have their own logging.
private fun 호출자_정의_헤더_파싱_로직_필요_host(headerData: ByteArray): String? = "example.com"
private fun 호출자_정의_헤더_파싱_로직_필요_contentLength(headerData: ByteArray): Int = if (호출자_정의_헤더_파싱_로직_필요_isConnect(headerData)) 0 else headerData.size // Example
private fun 호출자_정의_헤더_파싱_로직_필요_isConnect(headerData: ByteArray): Boolean = false // Example


// Placeholder for Opt constants - Will be properly translated later
object Opt { // Opt might have its own logger if it had complex static init blocks
    const val MAXHTTPContentBlockLength: Int = 8192 // A common default value
}


sealed class ReadAction {
    object ReadHeader : ReadAction()
    data class ReadContent(val length: Int) : ReadAction() // length is max bytes to read for next block, -1 for indefinite (CONNECT)
    object Stop : ReadAction()
}

sealed class ProcessedData { // Renamed from Result to avoid conflict with Kotlin.Result
    data class Header(val httpHeader: HTTPHeader) : ProcessedData()
    data class Content(val data: ByteArray) : ProcessedData() {
        override fun equals(other: Any?): Boolean { // For easier testing if needed
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Content
            return data.contentEquals(other.data)
        }
        override fun hashCode(): Int = data.contentHashCode()
    }
}

// Custom Exception Classes
class ContentIsTooLongException(message: String = "Content is too long") : Exception(message)
class ScannerIsStoppedException(message: String = "Scanner is stopped") : Exception(message)
class UnsupportedStreamTypeException(message: String = "Unsupported stream type") : Exception(message)

class HTTPStreamScanner {
    companion object {
        private val logger = LoggerFactory.getLogger(HTTPStreamScanner::class.java)
    }

    var nextAction: ReadAction = ReadAction.ReadHeader
    var remainContentLength: Int = 0
    var currentHeader: HTTPHeader? = null // Initialized to null
    var isConnect: Boolean = false

    fun input(data: ByteArray): ProcessedData {
        logger.debug("input called with data size: ${data.size}, current nextAction: $nextAction")
        when (val currentAction = nextAction) {
            is ReadAction.ReadHeader -> {
                val newHeader: HTTPHeader
                try {
                    // Assumes `data` is a complete header block
                    newHeader = HTTPHeader(data)
                    logger.debug("Parsed new HTTPHeader: host=${newHeader.host}, contentLength=${newHeader.contentLength}, isConnect=${newHeader.isConnect}")

                    // "To temporarily solve a bug in firefox for mac"
                    if (currentHeader != null && newHeader.host != null && newHeader.host != currentHeader?.host) {
                        logger.warn("Host changed in stream from ${currentHeader?.host} to ${newHeader.host}. Throwing UnsupportedStreamTypeException.")
                        throw UnsupportedStreamTypeException("Host changed in stream")
                    }
                } catch (e: Exception) {
                    logger.error("Error parsing HTTP header: ${e.message}", e)
                    nextAction = ReadAction.Stop
                    when(e) {
                        is UnsupportedStreamTypeException -> throw e
                        is IllegalArgumentException -> throw e // from HTTPHeader constructor
                        else -> throw Exception("Failed to parse HTTP header", e) // Generic wrapper
                    }
                }

                if (currentHeader == null) { // First header
                    isConnect = newHeader.isConnect
                    remainContentLength = if (newHeader.isConnect) {
                        -1 // For CONNECT, content length is indefinite until socket closes
                    } else {
                        newHeader.contentLength
                    }
                    logger.info("First header processed. isConnect: $isConnect, remainContentLength: $remainContentLength")
                } else { // Subsequent headers
                    remainContentLength = newHeader.contentLength
                    logger.info("Subsequent header processed. remainContentLength updated to: $remainContentLength")
                }

                currentHeader = newHeader
                setNextAction()
                return ProcessedData.Header(newHeader)
            }
            is ReadAction.ReadContent -> {
                if (!isConnect && remainContentLength >= 0) {
                    remainContentLength -= data.size
                }
                logger.debug("Processed content data. New remainContentLength: $remainContentLength (isConnect: $isConnect)")

                if (!isConnect && remainContentLength < 0) {
                    logger.error("ContentIsTooLongException: Received more content than specified by Content-Length. remainContentLength: $remainContentLength")
                    nextAction = ReadAction.Stop
                    throw ContentIsTooLongException("Received more content than specified by Content-Length")
                }

                setNextAction()
                return ProcessedData.Content(data)
            }
            is ReadAction.Stop -> {
                logger.warn("Input called on a stopped scanner.")
                throw ScannerIsStoppedException("Input called on a stopped scanner")
            }
        }
    }

    private fun setNextAction() {
        val previousNextAction = nextAction
        if (isConnect) {
            nextAction = ReadAction.ReadContent(-1)
        } else {
            nextAction = when {
                remainContentLength == 0 -> ReadAction.ReadHeader
                remainContentLength < 0 -> ReadAction.Stop // Error state
                else -> ReadAction.ReadContent(minOf(remainContentLength, Opt.MAXHTTPContentBlockLength))
            }
        }
        if (previousNextAction != nextAction) {
            logger.debug("setNextAction: Transitioned from $previousNextAction to $nextAction")
        }
    }
}
