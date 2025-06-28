package RawSocket

import io.netty.channel.socket.SocketChannel
import io.netty.buffer.Unpooled
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.slf4j.LoggerFactory
import java.lang.ref.WeakReference
import java.io.IOException
import java.net.InetSocketAddress

import Utils.IPAddress // Corrected import
import Utils.Port // Corrected import


/**
 * Adapts a Netty `SocketChannel` (representing an accepted client connection)
 * to the `RawTCPSocketProtocol` interface expected by the rest of the system.
 *
 * This is a placeholder implementation. Full Netty pipeline integration (handlers for read,
 * disconnect, error events) is required to make this fully functional.
 */
class NettyAcceptedRawSocketAdapter(
    private val channel: SocketChannel
) : RawTCPSocketProtocol {

    private val logger = LoggerFactory.getLogger(NettyAcceptedRawSocketAdapter::class.java)
    override var delegate: WeakReference<RawTCPSocketDelegate?>? = null

    private inner class ClientHandler : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val byteBuf = msg as ByteBuf
            try {
                val byteArray = ByteArray(byteBuf.readableBytes())
                byteBuf.readBytes(byteArray)
                logger.trace("Channel {} read {} bytes.", ctx.channel(), byteArray.size)
                this@NettyAcceptedRawSocketAdapter.delegate?.get()?.didRead(byteArray, this@NettyAcceptedRawSocketAdapter)
            } finally {
                byteBuf.release()
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            logger.info("Channel {} inactive.", ctx.channel())
            this@NettyAcceptedRawSocketAdapter.delegate?.get()?.didDisconnect(this@NettyAcceptedRawSocketAdapter)
            super.channelInactive(ctx)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            logger.error("Exception caught on channel {}: {}", ctx.channel(), cause.message, cause)
            this@NettyAcceptedRawSocketAdapter.delegate?.get()?.didErrorOccur(cause, this@NettyAcceptedRawSocketAdapter)
            ctx.close() // Close the connection on error
        }
    }

    init {
        logger.info("NettyAcceptedRawSocketAdapter created for Netty channel: {}", channel)
        channel.pipeline().addLast(ClientHandler())
        logger.debug("ClientHandler added to pipeline for channel {}", channel)
    }

    override val isConnected: Boolean
        get() = channel.isActive

    // For an accepted socket, source/destination are relative to the server.
    // Netty's channel.localAddress() is server's listening address.
    // Netty's channel.remoteAddress() is client's address.
    // RawTCPSocketProtocol's source/dest might be interpreted differently by consumers (e.g., ProxySocket).
    // ProxySocket expects sourceIP/Port to be the client, and destIP/Port to be the original target.
    // This adapter, wrapping an accepted socket, *is* the client connection from server's view.
    // So, its "source" is the client (remoteAddress) and "destination" is the server itself (localAddress).
    // This might need careful mapping if ProxySocket uses these for creating ConnectSession.
    // For now, providing them as Netty sees them.

    override val sourceIPAddress: IPAddress?
        get() = (channel.remoteAddress() as? InetSocketAddress)?.address?.hostAddress?.let {
            // Simplified IPAddress creation. Real one might need more robust parsing/byte handling.
            IPAddress(it)
        }

    override val sourcePort: Port?
        get() = (channel.remoteAddress() as? InetSocketAddress)?.port?.toUShort()?.let { Port(it) }

    override val destinationIPAddress: IPAddress?
        get() = (channel.localAddress() as? InetSocketAddress)?.address?.hostAddress?.let {
            IPAddress(it)
        }

    override val destinationPort: Port?
        get() = (channel.localAddress() as? InetSocketAddress)?.port?.toUShort()?.let { Port(it) }


    override fun connectTo(host: String, port: Int, enableTLS: Boolean, tlsSettings: Map<String, Any>?) {
        // This method is for initiating an outbound connection.
        // An accepted socket is already connected. Calling this is likely an error.
        logger.error("connectTo called on an already accepted Netty socket. This is unexpected.")
        delegate?.get()?.didErrorOccur(IllegalStateException("connectTo cannot be called on an accepted socket."), this)
        // Optionally, close the channel if this indicates a severe logic error.
        // forceDisconnect()
    }

    override fun write(data: ByteArray) {
        if (!channel.isActive) {
            logger.warn("write called on inactive Netty channel. Data not sent.")
            // Delegate could be notified of an error or disconnect here.
            // For now, just log. This might lead to data loss if not handled by caller.
            return
        }
        logger.debug("Writing {} bytes to Netty channel: {}", data.size, channel)
        channel.writeAndFlush(Unpooled.wrappedBuffer(data)).addListener { future ->
            if (future.isSuccess) {
                logger.trace("Successfully wrote {} bytes to Netty channel {}", data.size, channel)
                delegate?.get()?.didWrite(data, this)
            } else {
                logger.error("Failed to write {} bytes to Netty channel {}: {}", data.size, channel, future.cause().message, future.cause())
                delegate?.get()?.didErrorOccur(future.cause(), this)
                // Consider closing the channel on write failure depending on policy
                // forceDisconnect(future.cause())
            }
        }
    }

    override fun readData() {
        // Netty is event-driven. Reads are initiated by remote sending data, which triggers
        // channelRead in the pipeline's handler. That handler should call delegate.didRead.
        // So, this method might be a no-op or could be used to manage backpressure if needed.
        logger.debug("readData() called on NettyAcceptedRawSocketAdapter. Netty reads are event-driven. Ensure pipeline is configured.")
        // If using auto-read=false, channel.read() would be needed here to request more data.
        // For now, assuming auto-read=true.
    }

    override fun readDataTo(delimiter: ByteArray, maxLength: Int) {
        logger.warn("readDataTo(delimiter) not yet fully implemented for NettyAcceptedRawSocketAdapter. Relies on pipeline processing.")
        // TODO: Implement this using Netty's DelimiterBasedFrameDecoder or custom logic in pipeline.
        // For now, acts like readData() - relies on handler to push data.
        readData()
    }

    override fun readDataTo(length: Int) {
        logger.warn("readDataTo(length) not yet fully implemented for NettyAcceptedRawSocketAdapter. Relies on pipeline processing.")
        // TODO: Implement this using Netty's FixedLengthFrameDecoder or custom logic in pipeline.
        // For now, acts like readData() - relies on handler to push data.
        readData()
    }

    override fun disconnect() {
        logger.info("disconnect() called for Netty channel: {}. Closing channel gracefully.", channel)
        if (channel.isOpen) {
            // Graceful shutdown: waits for pending writes to flush before closing.
            channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
        }
    }

    override fun forceDisconnect() {
        logger.info("forceDisconnect() called for Netty channel: {}. Closing channel immediately.", channel)
        if (channel.isOpen) {
            channel.close() // Immediate close
        }
    }
}
