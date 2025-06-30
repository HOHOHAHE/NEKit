package com.example.nekit.Socket.AdapterSocket

import com.example.nekit.Messages.ConnectSession
import com.example.nekit.RawSocket.RawSocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference

class DirectAdapter : AdapterSocket() {

    private val logger = LoggerFactory.getLogger(DirectAdapter::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun openSocketWith(session: ConnectSession) {
        logger.info("Opening direct connection for session: ${session.host}:${session.port}")
        
        // Create a new raw socket for the direct connection.
        _rawSocket = RawSocketFactory.getRawSocket(session)
        // The delegate is set to this AdapterSocket instance to receive callbacks from the raw socket.
        _rawSocket?.delegate = WeakReference(this)

        // First, call the superclass implementation to set up the session and observer.
        super.openSocketWith(session)

        // Launch a coroutine to handle the network connection asynchronously.
        scope.launch {
            try {
                // Initiate the connection. This is a suspend function.
                // Assuming ConnectSession has an isTLS property.
                val enableTLS = session.isTLS
                _rawSocket?.connectTo(session.host, session.port.toInt(), enableTLS)
            } catch (e: Exception) {
                logger.error("Failed to connect directly to ${session.host}:${session.port}", e)
                // If connection fails, call forceDisconnect from the parent AdapterSocket.
                forceDisconnect(e)
            }
        }
    }
}