package Utils

import org.slf4j.LoggerFactory
import Messages.HTTPHeader // Corrected import
import Messages.HTTPHeaderParseException // Corrected import
import Opts.Opt // Corrected import


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
                        logger.warn("Host changed in stream from {} to {}. Throwing UnsupportedStreamTypeException.", currentHeader?.host, newHeader.host)
                        throw UnsupportedStreamTypeException("Host changed in stream from ${currentHeader?.host} to ${newHeader.host}")
                    }
                } catch (e: HTTPHeaderParseException) { // Catch specific parse exception
                    logger.error("Error parsing HTTP header: {}", e.message, e)
                    nextAction = ReadAction.Stop
                    throw e // Re-throw specific exception
                } catch (e: Exception) { // Catch other potential exceptions during header processing
                    logger.error("Unexpected error processing HTTP header: {}", e.message, e)
                    nextAction = ReadAction.Stop
                    throw Exception("Failed to process HTTP header", e) // Generic wrapper
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
                else -> ReadAction.ReadContent(minOf(remainContentLength, Opt.MAX_HTTP_CONTENT_BLOCK_LENGTH))
            }
        }
        if (previousNextAction != nextAction) {
            logger.debug("setNextAction: Transitioned from $previousNextAction to $nextAction")
        }
    }
}
