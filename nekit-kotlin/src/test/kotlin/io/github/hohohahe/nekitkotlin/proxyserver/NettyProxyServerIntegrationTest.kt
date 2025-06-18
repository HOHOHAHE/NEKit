package io.github.hohohahe.nekitkotlin.proxyserver

import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.rule.DirectRule
import io.github.hohohahe.nekitkotlin.rule.RuleManager
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
// Correct Netty HTTP imports
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.junit.jupiter.api.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlin.time.Duration // Import Duration
import kotlin.time.Duration.Companion.seconds // Import seconds extension

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // For @BeforeAll @AfterAll
class NettyProxyServerIntegrationTest {

    private lateinit var testTargetServer: Channel
    private val targetServerPort = Port(18088) // Different from proxy port
    private lateinit var targetServerBossGroup: NioEventLoopGroup
    private lateinit var targetServerWorkerGroup: NioEventLoopGroup

    private var proxyServer: NettyProxyServer? = null
    private val proxyPort = Port(18089)
    private lateinit var proxyBossGroup: NioEventLoopGroup
    private lateinit var proxyWorkerGroup: NioEventLoopGroup


    @BeforeAll
    fun startTestTargetServer() {
        targetServerBossGroup = NioEventLoopGroup(1)
        targetServerWorkerGroup = NioEventLoopGroup()
        val bootstrap = ServerBootstrap()
            .group(targetServerBossGroup, targetServerWorkerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(HttpServerCodec())
                    ch.pipeline().addLast(HttpObjectAggregator(65536))
                    ch.pipeline().addLast(object : SimpleChannelInboundHandler<FullHttpRequest>() {
                        override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
                            logger.info { "TestTargetServer received: ${request.uri()}" }
                            val response = DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_1,
                                HttpResponseStatus.OK,
                                ctx.alloc().buffer().writeBytes("Hello from target server!".toByteArray())
                            )
                            response.headers().set("Content-Type", "text/plain")
                            response.headers().set("Content-Length", response.content().readableBytes())
                            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
                        }
                    })
                }
            })
        testTargetServer = bootstrap.bind(targetServerPort.value).syncUninterruptibly().channel()
        logger.info { "TestTargetServer started on port ${targetServerPort.value}" }
    }

    @AfterAll
    fun stopTestTargetServer() {
        testTargetServer.close().syncUninterruptibly()
        targetServerBossGroup.shutdownGracefully().syncUninterruptibly()
        targetServerWorkerGroup.shutdownGracefully().syncUninterruptibly()
        logger.info { "TestTargetServer stopped." }
    }

    @BeforeEach
    fun setupProxyGroups() {
        proxyBossGroup = NioEventLoopGroup(1)
        proxyWorkerGroup = NioEventLoopGroup()
    }

    @AfterEach
    fun stopProxyServerInstance() {
        proxyServer?.stop()
        proxyBossGroup.shutdownGracefully().syncUninterruptibly() // Ensure these are fresh for each test
        proxyWorkerGroup.shutdownGracefully().syncUninterruptibly()
        proxyServer = null
        runBlocking { delay(100) } // Give some time for ports to free up
    }


    @Test
    @Disabled("This test uses actual HTTP client and might be flaky or require specific setup")
    fun `HTTP proxy successfully relays GET request via curl or Java HTTP client`() = runTest(timeout = 20.seconds) {
        val ruleManager = RuleManager(listOf(DirectRule())) // All traffic direct
        proxyServer = NettyProxyServer(proxyPort, ProxyType.HTTP, ruleManager, proxyBossGroup, proxyWorkerGroup)

        launch { proxyServer!!.start() } // Start server in a separate coroutine

        // Wait for server to start - needs a better mechanism
        var attempts = 0
        while (attempts < 100 && proxyServer?.isRunning() != true) {
            delay(100) // Wait 100ms
            attempts++
        }
        Assertions.assertTrue(proxyServer!!.isRunning(), "Proxy server should be running")

        val targetUrl = "http://localhost:${targetServerPort.value}/test"
        var responseContent: String? = null
        var statusCode: Int = -1

        logger.info { "Test: Making HTTP GET request to $targetUrl via proxy localhost:${proxyPort.value}" }

        try {
            // Using Java's HttpURLConnection to test proxy
            val url = URL(targetUrl)
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("localhost", proxyPort.value))
            val connection = withContext(Dispatchers.IO) {
                url.openConnection(proxy)
            } as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            withContext(Dispatchers.IO) {
                statusCode = connection.responseCode
                if (statusCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    responseContent = reader.readText()
                    reader.close()
                } else {
                    val errorStream = connection.errorStream
                    if (errorStream != null) {
                        val reader = BufferedReader(InputStreamReader(errorStream))
                        responseContent = "Error: " + reader.readText()
                        reader.close()
                    } else {
                         responseContent = "Error: No error stream, status $statusCode"
                    }
                }
            }
            logger.info { "Test: Received status $statusCode, content: '$responseContent'" }

        } catch (e: Exception) {
            logger.error(e) { "Exception during HTTP client request via proxy" }
            Assertions.fail("HTTP client request via proxy failed: ${e.message}", e)
        }

        Assertions.assertEquals(HttpURLConnection.HTTP_OK, statusCode, "Status code should be 200 OK. Response: $responseContent")
        Assertions.assertEquals("Hello from target server!", responseContent, "Response content should match target server's response.")
    }
}
