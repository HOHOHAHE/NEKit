package RawSocket

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.InetSocketAddress

class NettyRawUDPSocket(
    // Consider making workerGroup shared/passed in for better resource management.
    private var workerGroup: EventLoopGroup = NioEventLoopGroup(1) // Single thread might be enough for many UDP client cases
) : RawUDPSocketProtocol {

    private val logger = LoggerFactory.getLogger(NettyRawUDPSocket::class.java)
    private var channel: Channel? = null
    override var delegate: WeakReference<RawUDPSocketDelegate?>? = null

    override val localAddress: InetSocketAddress?
        get() = channel?.localAddress() as? InetSocketAddress

    private inner class UDPSocketHandler : SimpleChannelInboundHandler<DatagramPacket>() {
        override fun channelRead0(ctx: ChannelHandlerContext, packet: DatagramPacket) {
            val content = packet.content()
            val byteArray = ByteArray(content.readableBytes())
            content.readBytes(byteArray)
            // Not releasing DatagramPacket content here as SimpleChannelInboundHandler should handle it for DatagramPacket.
            // If it were just ByteBuf, manual release is often needed.

            val sender = packet.sender() // InetSocketAddress
            val fromHost = sender.address.hostAddress ?: ""
            val fromPort = sender.port
            logger.trace("UDP socket {} received {} bytes from {}:{}", channel?.localAddress(), byteArray.size, fromHost, fromPort)
            delegate?.get()?.didReceive(byteArray, fromHost, fromPort, this@NettyRawUDPSocket)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            logger.error("Exception caught in Netty UDP pipeline on channel {}: {}", ctx.channel(), cause.message, cause)
            delegate?.get()?.didErrorOccur(cause, this@NettyRawUDPSocket)
            // Depending on the error, the channel might be closed by Netty.
            // If not, consider closing it: ctx.close()
        }

        override fun channelInactive(ctx: ChannelHandlerContext?) {
            logger.info("Netty UDP channel {} became inactive.", channelIdToString(ctx))
            // Delegate is not informed of disconnect for UDP typically, as it's connectionless.
            // However, if the socket is explicitly closed by disconnect(), this path might be hit.
            // Or if a severe error caused channel closure.
            // delegate?.get()?.didErrorOccur(IOException("UDP Channel inactive"), this@NettyRawUDPSocket)
            super.channelInactive(ctx)
        }

        private fun channelIdToString(ctx: ChannelHandlerContext?): String {
            return ctx?.channel()?.id()?.asShortText() ?: "unknown"
        }
    }

    @Throws(Exception::class)
    override fun bind(host: String?, port: Int) {
        if (channel?.isActive == true) {
            logger.warn("Socket already bound and active at {}. Disconnecting before re-binding.", localAddress)
            disconnect() // Disconnect existing before binding anew
        }
        // Re-initialize workerGroup if it was shut down
        if (workerGroup.isShuttingDown || workerGroup.isShutdown) {
            workerGroup = NioEventLoopGroup(1)
        }

        val bootstrap = Bootstrap()
        bootstrap.group(workerGroup)
            .channel(NioDatagramChannel::class.java)
            .option(ChannelOption.SO_BROADCAST, false) // Typically false for DNS client sockets
            .handler(UDPSocketHandler())

        val bindAddress = host ?: "0.0.0.0"
        logger.info("Binding Netty UDP socket to {}:{}", bindAddress, port)

        try {
            val future = bootstrap.bind(bindAddress, port).sync()
            if (future.isSuccess) {
                channel = future.channel()
                logger.info("Netty UDP socket bound successfully to {}", channel?.localAddress())
            } else {
                logger.error("Failed to bind Netty UDP socket to {}:{}: {}", bindAddress, port, future.cause().message, future.cause())
                workerGroup.shutdownGracefully().syncUninterruptibly() // Clean up if bind fails
                throw IOException("Failed to bind UDP socket", future.cause())
            }
        } catch (e: Exception) {
            logger.error("Exception during Netty UDP socket bind to {}:{}: {}", bindAddress, port, e.message, e)
            workerGroup.shutdownGracefully().syncUninterruptibly()
            throw e
        }
    }

    @Throws(Exception::class)
    override fun send(data: ByteArray, destinationHost: String, destinationPort: Int) {
        val currentChannel = channel
        if (currentChannel == null || !currentChannel.isActive) {
            logger.error("UDP socket not bound or not active. Cannot send data to {}:{}.", destinationHost, destinationPort)
            throw IOException("UDP Socket not bound or not active.")
        }

        val remoteAddress = InetSocketAddress(destinationHost, destinationPort)
        val datagramPacket = DatagramPacket(Unpooled.wrappedBuffer(data), remoteAddress)

        logger.debug("Sending {} UDP bytes to {} via {}", data.size, remoteAddress, currentChannel.localAddress())
        currentChannel.writeAndFlush(datagramPacket).addListener { future ->
            if (!future.isSuccess) {
                logger.error("Failed to send UDP packet to {}:{}: {}", destinationHost, destinationPort, future.cause().message, future.cause())
                // Notify delegate of send error. This is not a standard RawUDPSocketDelegate method,
                // but can be useful. For now, just logging.
                // delegate?.get()?.didErrorOccur(IOException("Failed to send UDP data", future.cause()), this)
            } else {
                logger.trace("Successfully sent {} UDP bytes to {}:{}", data.size, destinationHost, destinationPort)
            }
        }
    }

    override fun disconnect() {
        logger.info("Disconnecting Netty UDP socket {}.", localAddress)
        channel?.close()?.syncUninterruptibly() // Wait for channel to close
        if (!workerGroup.isShuttingDown && !workerGroup.isShutdown) {
            logger.debug("Shutting down Netty worker group for UDP socket {}", this)
            workerGroup.shutdownGracefully().syncUninterruptibly()
        }
        channel = null
        logger.info("Netty UDP socket {} disconnected and worker group shut down.", localAddress)
    }
}
