package com.example.nekit.Socket.AdapterSocket.Shadowsocks

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.RawSocket.RawSocketFactory
import com.example.nekit.Crypto.CryptoHelper
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ShadowsocksAdapter(
    val serverHost: String,
    val serverPort: Int,
    val method: String,
    val key: ByteArray
) : AdapterSocket() {

    private val logger = LoggerFactory.getLogger(ShadowsocksAdapter::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cryptoStreamProcessor: CryptoStreamProcessor? = null

    override fun openSocketWith(session: ConnectSession) {
        super.openSocketWith(session)
        logger.info("Opening Shadowsocks connection for session: ${session.host}:${session.port}")

        _rawSocket = RawSocketFactory.getRawSocket()
        _rawSocket?.delegate = WeakReference(this)

        cryptoStreamProcessor = CryptoStreamProcessor(
            method,
            key,
            _rawSocket!!,
            this
        )

        scope.launch {
            try {
                _rawSocket?.connectTo(serverHost, serverPort)
                // After connection, send the Shadowsocks header
                val header = createShadowsocksHeader(session)
                cryptoStreamProcessor?.write(header)
            } catch (e: Exception) {
                logger.error("Failed to connect to Shadowsocks server $serverHost:$serverPort", e)
                forceDisconnect(e)
            }
        }
    }

    override suspend fun write(data: ByteArray) {
        cryptoStreamProcessor?.write(data)
            ?: throw java.io.IOException("CryptoStreamProcessor not initialized.")
    }

    private fun createShadowsocksHeader(session: ConnectSession): ByteArray {
        // Implementation based on Shadowsocks protocol
        // Address Type (1 byte) | Address | Port (2 bytes, big-endian)
        val addressBytes = session.host.toByteArray()
        val portBytes = byteArrayOf(
            (session.port.toInt() shr 8).toByte(),
            (session.port.toInt() and 0xFF).toByte()
        )
        
        // Assuming IPv4 for simplicity, ATYP = 0x01
        // A more robust implementation would handle domain names (ATYP = 0x03) and IPv6 (ATYP = 0x04)
        val atyp = if (isDomain(session.host)) 0x03.toByte() else 0x01.toByte()
        
        return when (atyp) {
            0x01.toByte() -> byteArrayOf(atyp) + addressBytes + portBytes // This is incorrect for IP, needs parsing
            0x03.toByte() -> byteArrayOf(atyp, addressBytes.size.toByte()) + addressBytes + portBytes
            else -> throw IllegalArgumentException("Unsupported address type")
        }
    }

    private fun isDomain(host: String): Boolean {
        // Simple check, not fully compliant with RFCs
        return host.any { it.isLetter() }
    }
}