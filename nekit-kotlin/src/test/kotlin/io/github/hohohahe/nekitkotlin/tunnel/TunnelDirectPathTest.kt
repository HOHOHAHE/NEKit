package io.github.hohohahe.nekitkotlin.tunnel

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.core.IpAddress
import io.github.hohohahe.nekitkotlin.core.Port
// Import RuleManager and related classes
import io.github.hohohahe.nekitkotlin.rule.DirectRule
import io.github.hohohahe.nekitkotlin.rule.RuleManager
import io.github.hohohahe.nekitkotlin.socket.adapter.AdapterSocket // Keep this for type casting if needed
import io.github.hohohahe.nekitkotlin.socket.adapter.DirectAdapterSocket
import io.github.hohohahe.nekitkotlin.socket.proxy.DummyProxySocket
import io.github.hohohahe.nekitkotlin.socket.raw.NettyRawTcpSocket
import io.github.hohohahe.nekitkotlin.socket.raw.RawTcpSocket // For mock
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import mu.KotlinLogging
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.IOException // Import IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration // For timeout

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TunnelDirectPathTest {

    private lateinit var serverEventLoopGroup: NioEventLoopGroup
    private lateinit var clientEventLoopGroup: NioEventLoopGroup
    private var serverChannel: Channel? = null
    private val echoServerPort = Port(12346)
    private val serverReceivedMessages = ArrayBlockingQueue<String>(10)

    @BeforeAll
    fun startEchoServer() {
        serverEventLoopGroup = NioEventLoopGroup(1)
        val bootstrap = ServerBootstrap()
            .group(serverEventLoopGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(StringDecoder(StandardCharsets.UTF_8))
                    ch.pipeline().addLast(StringEncoder(StandardCharsets.UTF_8))
                    ch.pipeline().addLast(object : SimpleChannelInboundHandler<String>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, msg: String) {
                            logger.debug { "TestEchoServer received: $msg" }
                            serverReceivedMessages.offer(msg)
                            ctx.writeAndFlush(msg)
                        }
                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            logger.error(cause) { "TestEchoServer error" }
                            ctx.close()
                        }
                    })
                }
            })
        serverChannel = bootstrap.bind(echoServerPort.value).syncUninterruptibly().channel()
        logger.info { "TestEchoServer started on port ${echoServerPort.value}" }
    }

    @AfterAll
    fun stopEchoServer() {
        serverChannel?.close()?.syncUninterruptibly()
        serverEventLoopGroup.shutdownGracefully().syncUninterruptibly()
        logger.info { "TestEchoServer stopped." }
    }

    @BeforeEach
    fun setupClientGroup() {
         clientEventLoopGroup = NioEventLoopGroup(1)
    }

    @AfterEach
    fun tearDownClientGroup() {
        clientEventLoopGroup.shutdownGracefully().syncUninterruptibly()
    }

    @Test
    fun `tunnel relays data correctly using RuleManager for direct mode`() = runTest(timeout = Duration.INFINITE) {
        val targetSession = ConnectSession("localhost", echoServerPort)

        val clientToServerChannel = kotlinx.coroutines.channels.Channel<ByteBuffer>(10)
        val serverToClientChannel = kotlinx.coroutines.channels.Channel<ByteBuffer>(10)

        val mockClientRawSocket = object : RawTcpSocket {
            @Volatile override var isOpen: Boolean = true
                private set
            override val localAddress: IpAddress? = IpAddress("127.0.0.1")
            override val remoteAddress: IpAddress? = IpAddress("1.2.3.4")
            override suspend fun connect(host: String, port: Port) {}
            override suspend fun read(buffer: ByteBuffer): Int {
                if (!isOpen && clientToServerChannel.isEmpty) return -1
                return try {
                    val data = clientToServerChannel.receive()
                    val len = data.remaining()
                    buffer.put(data)
                    len
                } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) { -1 }
                  catch (e: CancellationException) { -1 }
            }
            override suspend fun write(buffer: ByteBuffer): Int {
                if (!isOpen) throw IOException("Socket closed")
                val len = buffer.remaining()
                val copy = ByteBuffer.allocate(len)
                copy.put(buffer)
                copy.flip()
                serverToClientChannel.send(copy)
                return len
            }
            override fun close() {
                isOpen = false
                clientToServerChannel.close()
                serverToClientChannel.close()
                logger.debug { "MockClientRawSocket closed" }
            }
        }

        val proxySocket = DummyProxySocket(targetSession, mockClientRawSocket)

        // Setup RuleManager with a DirectRule
        val ruleManager = RuleManager(listOf(DirectRule()))

        val tunnelScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        // AdapterSocket will now be created by the Tunnel via RuleManager
        val tunnel = Tunnel(tunnelScope, proxySocket, ruleManager)

        val tunnelJob = launch {
            tunnel.openAndRelay()
        }

        delay(1000) // Allow time for connections to establish

        // Check if the adapter socket inside the tunnel (if accessible) is ready
        // This requires adapterSocket to be accessible or use other means to check readiness.
        // For this test, we assume if no exceptions by now, it's likely progressing.
        // A more robust test would involve inspecting Tunnel's internal adapter state or events.


        val messageToEcho = "Hello RuleManager Tunnel!"
        val clientMessageBuffer = ByteBuffer.wrap(messageToEcho.toByteArray(StandardCharsets.UTF_8))

        logger.debug { "Test: Sending message '$messageToEcho' from mock client to tunnel." }
        clientToServerChannel.send(clientMessageBuffer)

        val receivedFromServerBuffer = serverToClientChannel.receive()
        val receivedMessage = StandardCharsets.UTF_8.decode(receivedFromServerBuffer).toString()
        logger.debug { "Test: Received message '$receivedMessage' from tunnel (echoed by server)." }

        assertEquals(messageToEcho, receivedMessage, "Message echoed through tunnel should match")

        val serverReceived = serverReceivedMessages.poll(5, TimeUnit.SECONDS)
        assertEquals(messageToEcho, serverReceived, "Echo server should have received the message")

        logger.debug { "Test: Closing tunnel." }
        tunnel.close() // Should trigger cancellation and cleanup
        withTimeoutOrNull(2000) { // Wait for tunnel job to complete
            tunnelJob.join()
        }

        tunnelScope.cancel() // Cancel the scope to clean up any remaining coroutines

        // Assertions to ensure sockets are closed (might need small delay for all cleanup)
        delay(100) // give time for close propagation
        assertTrue(!mockClientRawSocket.isOpen, "Mock client RawTcpSocket should be closed by ProxySocket's close")
        // Verifying internal adapter socket state is harder without exposing it.
        // We rely on the tunnel's close() to have handled it.
    }
}
