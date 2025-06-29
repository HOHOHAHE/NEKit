package com.example.nekit.ProxyServer
import kotlinx.coroutines.*
// import kotlinx.coroutines.sync.Mutex // No longer needed if Netty handles thread safety for start/stop
// import kotlinx.coroutines.sync.withLock // No longer needed

import org.slf4j.LoggerFactory
// Removed Java NIO imports as Netty will be used
// import java.nio.ByteBuffer
// import java.io.Closeable
// import java.net.InetSocketAddress
// import java.nio.channels.ServerSocketChannel
// import java.nio.channels.SocketChannel
// import java.nio.channels.Selector
// import java.nio.channels.SelectionKey


// Netty imports
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel as NettySocketChannel // Alias to avoid conflict with java.nio
import io.netty.channel.socket.nio.NioServerSocketChannel


// Assuming ProxyServer.kt, IPAddress.kt, Port.kt, QueueFactory.kt (placeholders) are available.
// Assuming RawTCPSocketProtocol.kt (from RawSocket module) is available for NettyAcceptedRawSocketAdapter.

import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port
import com.example.nekit.Tunnel.QueueFactory
import com.example.nekit.RawSocket.NettyAcceptedRawSocketAdapter
import com.example.nekit.RawSocket.RawTCPSocketProtocol
import com.example.nekit.Socket.SocketProtocol.KotlinAcceptedSocketInterface

/**
 * Base class for proxy servers that listen on a TCP port.
 * This version is refactored to use Netty for handling network connections.
 * Subclasses should override `handleNewAcceptedSocket` to process new connections.
 */
open class GCDProxyServer(
    address: IPAddress?,
    port: Port,
    // mainDispatcher might still be useful for dispatching CPU-bound tasks off Netty's IO threads,
    // but Netty's own event loops handle I/O events.
    private val mainDispatcher: CoroutineDispatcher = QueueFactory.executionScope.coroutineContext[CoroutineDispatcher] ?: Dispatchers.Default
) : ProxyServer(address, port) { // No longer implements KotlinServerSocketDelegate

    private val logger = LoggerFactory.getLogger(this::class.java)

    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var serverChannel: Channel? = null

    @Throws(Exception::class)
    override suspend fun start() {
        if (bossGroup != null || workerGroup != null || serverChannel != null) {
            logger.warn("Server already started or starting.")
            return
        }

        logger.info("Attempting to start Netty server...")
        bossGroup = NioEventLoopGroup(1)
        workerGroup = NioEventLoopGroup()

        try {
            val bootstrap = ServerBootstrap()
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(object : ChannelInitializer<NettySocketChannel>() {
                    override fun initChannel(ch: NettySocketChannel) {
                        // This is called on an IO thread from Netty.
                        // We need to dispatch the handling to a coroutine if handleNettyClientConnection is suspend
                        // or if handleNewAcceptedSocket (called by it) performs blocking operations or
                        // requires a specific dispatcher context (like mainDispatcher or tunnelScope).
                        // For now, direct call, assuming subsequent logic handles dispatching if needed.
                        handleNettyClientConnection(ch)
                    }
                })

            val bindAddress = address?.presentation ?: "0.0.0.0"
            val bindPort = port.hostOrderValue.toInt()

            logger.info("Binding Netty server to {}:{}", bindAddress, bindPort)
            val future: ChannelFuture = bootstrap.bind(bindAddress, bindPort).sync()

            if (future.isSuccess) {
                serverChannel = future.channel()
                super.start() // Call ProxyServer's start for its logic (e.g., observer signals)
                logger.info("Netty server successfully started and listening on {}:{}", bindAddress, bindPort)
            } else {
                logger.error("Failed to bind Netty server to {}:{}: {}", bindAddress, bindPort, future.cause().message, future.cause())
                workerGroup?.shutdownGracefully()?.sync()
                bossGroup?.shutdownGracefully()?.sync()
                bossGroup = null
                workerGroup = null
                throw IOException("Failed to bind Netty server", future.cause())
            }
        } catch (e: Exception) {
            logger.error("Netty server failed to start: {}", e.message, e)
            workerGroup?.shutdownGracefully()?.sync()
            bossGroup?.shutdownGracefully()?.sync()
            bossGroup = null
            workerGroup = null
            serverChannel = null
            throw e // Re-throw to indicate failure
        }
    }

    override suspend fun stop() {
        logger.info("Attempting to stop Netty server...")

        serverChannel?.close()?.syncUninterruptibly() // Wait for server socket to close
        logger.info("Netty server channel closed.")

        // Shutdown event loop groups
        // Using syncUninterruptibly to wait for completion. Consider timeout versions for production.
        bossGroup?.shutdownGracefully()?.syncUninterruptibly()
        workerGroup?.shutdownGracefully()?.syncUninterruptibly()
        logger.info("Netty boss and worker groups shut down.")

        bossGroup = null
        workerGroup = null
        serverChannel = null

        super.stop() // Call ProxyServer's stop for its logic
        logger.info("Netty server stopped.")
    }

    private fun handleNettyClientConnection(clientChannel: NettySocketChannel) {
        logger.info("Netty accepted new client connection: {}", clientChannel)
        val acceptedRawSocket = NettyAcceptedRawSocketAdapter(clientChannel)

        // The `handleNewAcceptedSocket` method is overridden by subclasses (GCDHTTPProxyServer, GCDSOCKS5ProxyServer)
        // to create their specific ProxySocket types (HTTPProxySocket, SOCKS5ProxySocket).
        // Those ProxySocket types now need to accept a RawTCPSocketProtocol (which NettyAcceptedRawSocketAdapter is).
        // This call dispatches to the appropriate overridden version.
        // It's important that `handleNewAcceptedSocket` and the ProxySocket constructors
        // correctly use the `mainDispatcher` or another appropriate context if they launch coroutines
        // or perform long-running tasks, to avoid blocking Netty's IO threads.
        // For example, ProxyServer.didAcceptNewSocket (called by handleNewAcceptedSocket's overrides)
        // is a suspend function and uses a Mutex, so it should be fine if called from here.
        // However, the methods within handleNewAcceptedSocket (HTTP/SOCKS5 specific parsing) should be non-blocking
        // or be dispatched. ProxySocket.openSocket() is called by those, which in turn starts reading.
        // The actual read/write operations in NettyAcceptedRawSocketAdapter will be async via Netty pipeline.
        handleNewAcceptedSocket(acceptedRawSocket)
    }

    /**
     * Handles a newly accepted socket from the listening socket.
     * Subclasses (e.g., HTTPProxyServer, SOCKS5ProxyServer) must override this method
     * to provide specific proxy logic for the accepted connection.
     *
     * @param acceptedRawSocket The newly accepted socket, adapted to RawTCPSocketProtocol.
     */
    protected open fun handleNewAcceptedSocket(acceptedRawSocket: RawTCPSocketProtocol) {
        // Base implementation (if called directly, which shouldn't happen if subclasses override)
        logger.warn("GCDProxyServer.handleNewAcceptedSocket (base) called with {}. This should be overridden. Closing socket.", acceptedRawSocket)
        // This implies that if a subclass doesn't override, the raw socket might not be properly closed
        // as RawTCPSocketProtocol doesn't have a simple close(). It has disconnect/forceDisconnect.
        // For now, let's assume subclasses *will* override and handle the socket.
        // If direct close is needed: (acceptedRawSocket as? Closeable)?.close()
        // Or better:
        acceptedRawSocket.forceDisconnect(IOException("Base handleNewAcceptedSocket called, unhandled connection."))
    }

    // Removed didAcceptNewSocket(acceptedSocket: KotlinAcceptedSocketInterface) as KotlinServerSocketDelegate is no longer implemented.
    // Netty's ChannelInitializer handles new connections.
}
