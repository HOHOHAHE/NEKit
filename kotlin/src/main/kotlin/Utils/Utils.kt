package nekit.Utils

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Utils {
    fun getInternalStateForDebug(): String = "" // Placeholder
}

interface Opt {
    companion object {
        val MAX_SCAN_LENGTH: Int = 0 // Placeholder
    }
}



object Base64 {
    fun decode(data: String): ByteArray = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
    fun encode(data: ByteArray): String = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
}

interface ZERO