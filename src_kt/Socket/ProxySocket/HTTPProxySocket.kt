package com.example.nekit.Socket.ProxySocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Socket.AdapterSocket.AdapterSocket
import com.example.nekit.Utils.HTTPStreamScanner
import com.example.nekit.Utils.ProcessedData
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException

class HTTPProxySocket(
    rawSocket: RawTCPSocketProtocol,
    session: ConnectSession
) : ProxySocket(rawSocket) {

    override var session: ConnectSession? = session

    private val logger = LoggerFactory.getLogger(HTTPProxySocket::class.java)
    private val scanner = HTTPStreamScanner()

    override fun openSocket() {
        super.openSocket()
        // Start reading the HTTP request from the client
        GlobalScope.launch { readData() }
    }

    override fun didRead(data: ByteArray, from: RawTCPSocketProtocol) {
        val result = scanner.input(data)
        when (result) {
            is ProcessedData.Header -> {
                val requestSession = ConnectSession(
                    host = result.httpHeader.host,
                    port = result.httpHeader.port,
                    isTLS = result.httpHeader.isConnect
                )
                this.session = requestSession
                delegate?.get()?.didReceive(requestSession, this)
            }
            is ProcessedData.Content -> {
                // Forward body data to the adapter socket
                delegate?.get()?.didRead(result.data, this)
            }

            else -> {
                // Still parsing, wait for more data
            }
        }
    }

    override fun respondTo(adapter: AdapterSocket) {
        super.respondTo(adapter)
        val response = "HTTP/1.1 200 Connection Established\r\n\r\n"
        GlobalScope.launch { write(response.toByteArray()) }
        delegate?.get()?.didBecomeReadyToForward(this)
    }
}
