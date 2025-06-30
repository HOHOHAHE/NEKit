package com.example.nekit.Socket.AdapterSocket

import com.example.nekit.Messages.ConnectSession
import org.slf4j.LoggerFactory
import java.io.IOException

class RejectAdapter(private val delay: Int) : AdapterSocket() {

    private val logger = LoggerFactory.getLogger(RejectAdapter::class.java)

    override fun openSocketWith(session: ConnectSession) {
        super.openSocketWith(session)
        logger.info("Rejecting connection for session: ${session.host}:${session.port} with delay: $delay ms")
        
        // Immediately force disconnect the session.
        // The error message indicates that the connection was rejected by a rule.
        forceDisconnect(IOException("Connection rejected by rule."))
    }
}