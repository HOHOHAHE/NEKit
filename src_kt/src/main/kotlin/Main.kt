
package com.example.nekit.main

import com.example.nekit.ProxyServer.GCDSOCKS5ProxyServer
import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port
import com.example.nekit.Rule.RuleManager
import com.example.nekit.Rule.DirectRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

fun main(args: Array<String>) = runBlocking {
    val ipAddress = IPAddress.parse("127.0.0.1")
    if (ipAddress == null) {
        println("Failed to parse IP address")
        return@runBlocking
    }

    // Set up the rule manager
    val rules = listOf(DirectRule())
    RuleManager.currentManager = RuleManager(rules, true)

    val server = GCDSOCKS5ProxyServer(ipAddress, Port(1080))
    try {
        println("Starting SOCKS5 proxy server on 127.0.0.1:1080")
        server.start()
        println("SOCKS5 proxy server started. Press Ctrl+C to stop.")
        // Keep the server running indefinitely
        while (true) {
            delay(Long.MAX_VALUE)
        }
    } catch (e: Exception) {
        println("Failed to start SOCKS5 proxy server: ${e.message}")
        e.printStackTrace()
    } finally {
        println("SOCKS5 proxy server stopped.")
    }
}
