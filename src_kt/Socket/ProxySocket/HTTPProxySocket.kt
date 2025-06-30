package com.example.nekit.Socket.ProxySocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Utils.HTTPStreamScanner
import org.slf4j.LoggerFactory
import java.io.IOException

class HTTPProxySocket(
    rawSocket: RawTCPSocketProtocol,
    private val session: ConnectSession
) : ProxySocket(rawSocket) {

    private val logger = LoggerFactory.getLogger(HTTPProxySocket::class.java)
    private val scanner = HTTPStreamScanner()

    override fun openSocket() {
        super.openSocket()
        // Start reading the HTTP request from the client
        readData()
    }

    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        val result = scanner.input(data)
        when (result) {
            is HTTPStreamScanner.Result.Header -> {
                val requestSession = ConnectSession(
                    host = result.header.host,
                    port = result.header.port.toShort(),
                    isTLS = result.header.isConnect
                )
                this.session = requestSession
                delegate?.get()?.didReceive(requestSession, this)
            }
            is HTTPStreamScanner.Result.Body -> {
                // Forward body data to the adapter socket
                delegate?.get()?.didRead(result.body, this)
            }
            is HTTPStreamScanner.Result.Error -> {
                logger.error("HTTP parsing error: ${result.error}")
                forceDisconnect(IOException("HTTP parsing error"))
            }
            else -> {
                // Still parsing, wait for more data
            }
        }
    }

    override fun respondTo(adapter: AdapterSocket) {
        super.respondTo(adapter)
        val response = "HTTP/1.1 200 Connection Established\r\n\r\n"
        write(response.toByteArray())
        delegate?.get()?.didBecomeReadyToForward(this)
    }
}
