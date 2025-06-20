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
}

// Placeholder functions for parsing logic - these would be part of HTTPHeader proper parsing
private fun 호출자_정의_헤더_파싱_로직_필요_host(headerData: ByteArray): String? = "example.com"
private fun 호출자_정의_헤더_파싱_로직_필요_contentLength(headerData: ByteArray): Int = if (호출자_정의_헤더_파싱_로직_필요_isConnect(headerData)) 0 else headerData.size // Example
private fun 호출자_정의_헤더_파싱_로직_필요_isConnect(headerData: ByteArray): Boolean = false // Example


// Placeholder for Opt constants - Will be properly translated later
object Opt {
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
    var nextAction: ReadAction = ReadAction.ReadHeader
    var remainContentLength: Int = 0
    var currentHeader: HTTPHeader? = null // Initialized to null
    var isConnect: Boolean = false

    fun input(data: ByteArray): ProcessedData {
        when (val currentAction = nextAction) {
            is ReadAction.ReadHeader -> {
                val newHeader: HTTPHeader
                try {
                    // Assumes `data` is a complete header block
                    newHeader = HTTPHeader(data)

                    // "To temporarily solve a bug in firefox for mac"
                    // This logic seems to suggest that if a currentHeader already exists,
                    // and the new header's host is different, it's an error.
                    // This might be specific to a particular proxy or tunneling scenario.
                    if (currentHeader != null && newHeader.host != null && newHeader.host != currentHeader?.host) {
                        throw UnsupportedStreamTypeException("Host changed in stream")
                    }
                } catch (e: Exception) {
                    nextAction = ReadAction.Stop
                    // Propagate original error or a specific scanner error
                    when(e) {
                        is UnsupportedStreamTypeException -> throw e
                        is IllegalArgumentException -> throw e // from HTTPHeader constructor
                        else -> throw Exception("Failed to parse HTTP header", e)
                    }
                }

                if (currentHeader == null) { // First header
                    isConnect = newHeader.isConnect
                    remainContentLength = if (newHeader.isConnect) {
                        -1 // For CONNECT, content length is indefinite until socket closes
                    } else {
                        newHeader.contentLength
                    }
                } else { // Subsequent headers (e.g. trailers, though less common, or new req in keep-alive)
                    // If it's a new request on a keep-alive connection, currentHeader would be from previous.
                    // The logic seems to imply we are processing one logical stream.
                    // If currentHeader is not null, it means we are likely processing parts of the *same* message
                    // or there's a specific context (like proxying) that this scanner is designed for.
                    // The Swift code just updates remainContentLength based on the new header's content length.
                    remainContentLength = newHeader.contentLength
                }

                currentHeader = newHeader
                setNextAction()
                return ProcessedData.Header(newHeader)
            }
            is ReadAction.ReadContent -> {
                if (!isConnect && remainContentLength >= 0) { // only deduct if not CONNECT and not already error
                    remainContentLength -= data.size
                }

                if (!isConnect && remainContentLength < 0) {
                    nextAction = ReadAction.Stop
                    throw ContentIsTooLongException("Received more content than specified by Content-Length")
                }

                setNextAction()
                return ProcessedData.Content(data)
            }
            is ReadAction.Stop -> {
                throw ScannerIsStoppedException("Input called on a stopped scanner")
            }
        }
    }

    private fun setNextAction() {
        if (isConnect) { // For CONNECT, we just keep reading content until external signal (socket close)
            nextAction = ReadAction.ReadContent(-1)
            return
        }

        nextAction = when {
            remainContentLength == 0 -> ReadAction.ReadHeader // Expect next header or end of stream
            remainContentLength < 0 -> ReadAction.Stop // Error state or CONNECT handled above
            else -> ReadAction.ReadContent(minOf(remainContentLength, Opt.MAXHTTPContentBlockLength))
        }
    }
}
