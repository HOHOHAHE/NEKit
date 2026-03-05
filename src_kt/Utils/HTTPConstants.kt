package nekit.Utils

import java.nio.charset.StandardCharsets



/**
 * Object holding common HTTP constants.
 */
object HTTPConstants {
    val CRLF = "\r\n"
    val DOUBLE_CRLF: ByteArray = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    val HTTP_1_1 = "HTTP/1.1"
}