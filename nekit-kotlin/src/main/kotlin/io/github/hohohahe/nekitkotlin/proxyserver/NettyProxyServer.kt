package io.github.hohohahe.nekitkotlin.proxyserver

import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.rule.RuleManager
import io.github.hohohahe.nekitkotlin.socket.proxy.HttpProxySocket
import io.github.hohohahe.nekitkotlin.socket.proxy.ProxySocket
import io.github.hohohahe.nekitkotlin.socket.proxy.Socks5ProxySocket
import io.github.hohohahe.nekitkotlin.socket.raw.NettyRawTcpSocket
import io.github.hohohahe.nekitkotlin.tunnel.Tunnel
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

enum class ProxyType {
    HTTP,
    SOCKS5
    // AUTO // Future: sniff first few bytes to determine type
}

class NettyProxyServer(
    override val port: Port,
    private val proxyType: ProxyType, // For now, server handles one type per instance
    private val ruleManager: RuleManager,
    private val bossGroup: EventLoopGroup = NioEventLoopGroup(1), // For accepting connections
    private val workerGroup: EventLoopGroup = NioEventLoopGroup()  // For handling I/O of accepted connections
) : ProxyServer {

    private var serverChannel: Channel? = null
    private val serverScope = CoroutineScope(Dispatchers.Default + SupervisorJob()) // Scope for tunnels
    private val activeTunnels = ConcurrentHashMap<ChannelId, Tunnel>()


    override suspend fun start() {
        if (isRunning()) {
            logger.warn { "NettyProxyServer on port ${port.value} is already running." }
            return
        }

        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    logger.info { "Accepted new connection from ${ch.remoteAddress()} on port ${port.value}" }

                    // Create a RawTcpSocket wrapper around the Netty SocketChannel
                    // This NettyRawTcpSocket needs to be initialized with an existing Channel
                    // instead of creating a new connection.
                    // This requires a modification to NettyRawTcpSocket or a new constructor/method.
                    // For now, let's assume NettyRawTcpSocket can be adapted or we make a simple one here.

                    val acceptedNettyChannel = ch
                    val rawTcpSocketForClient = NettyRawTcpSocket(acceptedNettyChannel, workerGroup)


                    val clientProxySocket: ProxySocket = when (proxyType) {
                        ProxyType.HTTP -> HttpProxySocket(rawTcpSocketForClient)
                        ProxyType.SOCKS5 -> Socks5ProxySocket(rawTcpSocketForClient)
                    }

                    // Launch a coroutine to handle the proxying logic for this client
                    serverScope.launch(CoroutineName("client-${ch.remoteAddress()}")) {
                        val tunnel = Tunnel(this, clientProxySocket, ruleManager)
                        activeTunnels[ch.id()] = tunnel
                        try {
                            // HttpProxySocket and Socks5ProxySocket need to be triggered to parse
                            when (clientProxySocket) {
                                is HttpProxySocket -> clientProxySocket.handleIncomingConnection()
                                is Socks5ProxySocket -> clientProxySocket.handleIncomingConnection()
                            }
                            // If parsing was successful and connectSession is available, openAndRelay
                            // The current getConnectSession() returns a Flow, which Tunnel collects.
                            // The handleIncomingConnection should populate that flow.
                            tunnel.openAndRelay()
                        } catch (e: Exception) {
                            if (e is CancellationException) {
                                 logger.info {"Tunnel for ${ch.remoteAddress()} cancelled during setup."}
                            } else {
                                logger.error(e) { "Error setting up tunnel for ${ch.remoteAddress()}: ${e.message}" }
                            }
                            // Ensure resources are cleaned up if setup fails
                            clientProxySocket.close() // This should close rawTcpSocketForClient too
                        } finally {
                             activeTunnels.remove(ch.id())
                             logger.debug("Tunnel for ${ch.remoteAddress()} removed. Active tunnels: ${activeTunnels.size}")
                        }
                    }
                }
            })
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, true)

        try {
            val channelFuture = bootstrap.bind(port.value).sync()
            serverChannel = channelFuture.channel()
            logger.info { "$proxyType Proxy Server started on port ${port.value}" }

            // To keep start() suspending until server is stopped, uncomment:
            // serverChannel?.closeFuture()?.sync()
            // However, typically start() returns after binding, and stop() is called separately.

        } catch (e: Exception) {
            logger.error(e) { "Failed to start $proxyType Proxy Server on port ${port.value}" }
            // Ensure groups are shut down if bind fails and they are not shared
            // stop() // Call stop to clean up resources
            throw e // Re-throw to signal failure to start
        }
    }

    override fun stop() {
        logger.info { "Stopping $proxyType Proxy Server on port ${port.value}..." }

        // Close all active tunnels
        activeTunnels.values.forEach { tunnel ->
            try {
                tunnel.close()
            } catch (e: Exception) {
                logger.warn(e) { "Exception while closing an active tunnel." }
            }
        }
        activeTunnels.clear()

        // Cancel the server scope to ensure all tunnel coroutines are stopped
        serverScope.cancel("Proxy server stopping")

        // Close the server channel
        serverChannel?.close()?.awaitUninterruptibly()
        serverChannel = null

        // Shut down event loop groups if they are not shared externally.
        // For this example, assuming they are managed by this server instance.
        // If bossGroup and workerGroup are passed in, the caller should manage their lifecycle.
        // bossGroup.shutdownGracefully().awaitUninterruptibly()
        // workerGroup.shutdownGracefully().awaitUninterruptibly()
        logger.info { "$proxyType Proxy Server on port ${port.value} stopped." }
    }

    override fun isRunning(): Boolean {
        return serverChannel?.isOpen == true && serverChannel?.isActive == true
    }
}
