package com.example.nekit.Utils

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
    fun decode(data: String): ByteArray = java.util.Base64.getDecoder().decode(data)
    fun encode(data: ByteArray): String = java.util.Base64.getEncoder().encodeToString(data)
}

interface ZERO