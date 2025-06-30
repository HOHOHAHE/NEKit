package com.example.nekit.RawSocket

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel as NettySocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

import com.example.nekit.Utils.IPAddress
import com.example.nekit.Utils.Port

/**
 * Netty-based implementation of RawTCPSocketProtocol for client-side TCP connections.
 */
class NettyRawTCPClientSocket(
    // Consider making workerGroup shared/passed in for better resource management in a larger app.
    // For now, each instance creates its own, but it's shut down on disconnect.
    private val workerGroup: EventLoopGroup = NioEventLoopGroup()
) : RawTCPSocketProtocol {

    private val logger = LoggerFactory.getLogger(NettyRawTCPClientSocket::class.java)
    private var channel: Channel? = null
    override var delegate: WeakReference<RawTCPSocketDelegate?>? = null

    // Store connection parameters for IP/Port properties
    private var connectedHost: String? = null
    private var connectedPort: Int = 0

    // Default connect timeout
    private var connectTimeoutMillis: Int = 5000


    private inner class ClientSocketHandler : ChannelInboundHandlerAdapter() {
        override fun channelActive(ctx: ChannelHandlerContext) {
            logger.trace("Netty channel active: {}", ctx.channel())
            // This is where the successful connection is confirmed *after* the connect future succeeds.
            // The connectTo method's listener handles the initial delegate.didConnect.
            // This can be used if further "ready" signaling is needed, but usually not for didConnect itself.
            // For this adapter, didConnect is signaled by the connect future listener.
            // However, if we want to signal readyForForward after TLS (if implemented), this is a place.
            // For now, let's assume didConnect from connectTo's listener is sufficient.
            // If TLS were added, it would be:
            // if (isTLSEnabled && ctx.pipeline().get(SslHandler::class.java).handshakeFuture().isSuccess) {
            //    delegate?.get()?.didConnect(this@NettyRawTCPClientSocket) // Or a specific TLS ready event
            // } else if (!isTLSEnabled) {
            //    // delegate?.get()?.didConnect(this@NettyRawTCPClientSocket) // Already handled by connect future
            // }
            super.channelActive(ctx)
        }


        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val byteBuf = msg as ByteBuf
            try {
                val byteArray = ByteArray(byteBuf.readableBytes())
                byteBuf.readBytes(byteArray)
                logger.trace("Netty channel {} read {} bytes.", ctx.channel(), byteArray.size)
                delegate?.get()?.didRead(byteArray, this@NettyRawTCPClientSocket)
            } catch (e: Exception) {
                logger.error("Error processing channelRead on {}: {}", ctx.channel(), e.message, e)
                delegate?.get()?.didErrorOccur(e, this@NettyRawTCPClientSocket)
                ctx.close() // Close on error
            } finally {
                byteBuf.release()
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            logger.info("Netty channel {} inactive.", ctx.channel())
            delegate?.get()?.didDisconnect(this@NettyRawTCPClientSocket)
            shutdownEventLoopGroup() // Shutdown group when channel is closed
            super.channelInactive(ctx)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            logger.error("Exception caught on Netty channel {}: {}", ctx.channel(), cause.message, cause)
            delegate?.get()?.didErrorOccur(cause, this@NettyRawTCPClientSocket)
            ctx.close() // Close the connection on error
            // Group shutdown is handled by channelInactive
        }
    }

    private fun shutdownEventLoopGroup() {
        if (!workerGroup.isShuttingDown && !workerGroup.isShutdown) {
            logger.debug("Shutting down Netty worker group for client socket {}", this)
            workerGroup.shutdownGracefully()
        }
    }

    @Throws(Exception::class)
    override suspend fun connectTo(host: String, port: Int, enableTLS: Boolean, tlsSettings: Map<String, Any>?) {
        if (channel?.isActive == true) {
            logger.warn("connectTo called on an already connected socket. Disconnecting first.")
            forceDisconnect() // Or throw IllegalStateException
        }

        this.connectedHost = host
        this.connectedPort = port
        // this.connectTimeoutMillis = timeoutMillis // If timeout is passed as param

        val bootstrap = Bootstrap()
        bootstrap.group(workerGroup)
            .channel(NioSocketChannel::class.java)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
            .handler(object : ChannelInitializer<NettySocketChannel>() {
                override fun initChannel(ch: NettySocketChannel) {
                    // TODO: Add TLS handler here if enableTLS is true
                    //  val sslCtx = SslContextBuilder.forClient().trustManager(...).build()
                    //  ch.pipeline().addLast(sslCtx.newHandler(ch.alloc(), host, port))
                    if (enableTLS) {
                        logger.warn("TLS not yet implemented in NettyRawTCPClientSocket for {}:{}", host, port)
                        // throw NotImplementedError("TLS support not implemented for NettyRawTCPClientSocket")
                    }
                    ch.pipeline().addLast(ClientSocketHandler())
                    logger.debug("Netty client pipeline initialized for {}:{}", host, port)
                }
            })

        logger.info("Attempting to connect to {}:{} with timeout {}ms", host, port, connectTimeoutMillis)

        return suspendCancellableCoroutine { continuation ->
            val future = bootstrap.connect(host, port)
            continuation.invokeOnCancellation {
                logger.debug("Connection attempt to {}:{} cancelled.", host, port)
                future.cancel(false)
                shutdownEventLoopGroup()
            }
            future.addListener { f ->
                if (f.isSuccess) {
                    this.channel = future.channel()
                    logger.info("Successfully connected to {}:{}", host, port)
                    delegate?.get()?.didConnect(this) // Notify delegate
                    continuation.resume(Unit)
                } else {
                    logger.error("Failed to connect to {}:{}: {}", host, port, f.cause().message, f.cause())
                    delegate?.get()?.didErrorOccur(f.cause(), this)
                    shutdownEventLoopGroup() // Ensure group is shut down on connect failure
                    continuation.resumeWithException(f.cause() ?: IOException("Unknown connection error"))
                }
            }
        }
    }

    @Throws(Exception::class)
    override suspend fun write(data: ByteArray) {
        val currentChannel = channel
        if (currentChannel == null || !currentChannel.isActive) {
            logger.warn("write called on inactive or null Netty channel. Data not sent.")
            throw IOException("Socket not connected or channel is null.")
        }

        return suspendCancellableCoroutine { continuation ->
            logger.debug("Writing {} bytes to Netty channel: {}", data.size, currentChannel)
            val future = currentChannel.writeAndFlush(Unpooled.wrappedBuffer(data))
            continuation.invokeOnCancellation {
                 logger.debug("Write operation cancelled for channel {}", currentChannel)
                 // Netty doesn't directly support cancelling a write future easily,
                 // but the underlying connection might be closed which would trigger failure.
            }
            future.addListener { f ->
                if (f.isSuccess) {
                    logger.trace("Successfully wrote {} bytes to Netty channel {}", data.size, currentChannel)
                    delegate?.get()?.didWrite(data, this)
                    continuation.resume(Unit)
                } else {
                    logger.error("Failed to write {} bytes to Netty channel {}: {}", data.size, currentChannel, f.cause().message, f.cause())
                    delegate?.get()?.didErrorOccur(f.cause(), this)
                    // Consider closing channel on write failure depending on policy by calling forceDisconnect
                    continuation.resumeWithException(f.cause() ?: IOException("Unknown write error"))
                }
            }
        }
    }


    override suspend fun readData() {
        logger.warn("readData() called on NettyRawTCPClientSocket. Netty reads are event-driven. This method should suspend until data is available.")
        // For now, it will indefinitely suspend. Proper implementation would involve a CompletableDeferred
        // or similar mechanism to be resumed by channelRead.
        return suspendCancellableCoroutine { } // Never resumes, effectively blocks
    }

    override suspend fun readDataTo(delimiter: ByteArray) {
        logger.warn("readDataTo(delimiter) not yet fully implemented for NettyRawTCPClientSocket. Relies on pipeline processing.")
        // TODO: Implement this using Netty's DelimiterBasedFrameDecoder or custom logic in pipeline.
        // For now, just a placeholder.
        return suspendCancellableCoroutine { } // Never resumes, effectively blocks
    }

    override suspend fun readDataTo(length: Int) {
        logger.warn("readDataTo(length) not yet fully implemented for NettyRawTCPClientSocket. Relies on pipeline processing.")
        // TODO: Implement this using Netty's FixedLengthFrameDecoder or custom logic in pipeline.
        // For now, just a placeholder.
        return suspendCancellableCoroutine { } // Never resumes, effectively blocks
    }

    override suspend fun readDataTo(delimiter: ByteArray, maxLength: Int) {
        logger.warn("readDataTo(delimiter, maxLength) not yet fully implemented for NettyRawTCPClientSocket. Relies on pipeline processing.")
        // TODO: Implement this using Netty's DelimiterBasedFrameDecoder or custom logic in pipeline.
        // For now, just a placeholder.
        return suspendCancellableCoroutine { } // Never resumes, effectively blocks
    }

    override fun disconnect(becauseOf: Throwable?) {
        logger.info("disconnect() called for Netty channel: {}. Closing gracefully. Cause: {}", channel, becauseOf?.message)
        if (channel?.isOpen == true) {
            channel?.writeAndFlush(Unpooled.EMPTY_BUFFER)?.addListener(ChannelFutureListener.CLOSE)
        } else {
            shutdownEventLoopGroup() // If channel already closed/null, ensure group is cleaned up
        }
    }

    override fun forceDisconnect(becauseOf: Throwable?) {
        logger.info("forceDisconnect() called for Netty channel: {}. Closing immediately. Cause: {}", channel, becauseOf?.message)
        if (channel?.isOpen == true) {
            channel?.close()
        } else {
            shutdownEventLoopGroup() // If channel already closed/null, ensure group is cleaned up
        }
        // channelInactive in ClientSocketHandler will handle delegate notification and group shutdown.
    }

    override val isConnected: Boolean
        get() = channel?.isActive ?: false

    override val sourceIPAddress: IPAddress?
        get() = (channel?.localAddress() as? InetSocketAddress)?.address?.hostAddress?.let { IPAddress.parse(it) }

    override val sourcePort: Port?
        get() = (channel?.localAddress() as? InetSocketAddress)?.port?.toUShort()?.let { Port(it.toInt()) }

    override val destinationIPAddress: IPAddress?
        get() = connectedHost?.let { IPAddress.parse(it) } // Return host passed to connectTo

    override val destinationPort: Port?
        get() = if (connectedPort != 0) Port(connectedPort.toUShort().toInt()) else null
}
