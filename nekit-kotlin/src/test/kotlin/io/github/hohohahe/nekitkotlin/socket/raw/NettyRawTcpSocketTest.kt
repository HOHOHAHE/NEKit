package io.github.hohohahe.nekitkotlin.socket.raw

import io.github.hohohahe.nekitkotlin.core.Port
import io.netty.channel.nio.NioEventLoopGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay // For small delays
import kotlinx.coroutines.test.runTest // Use runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail // Direct import for fail
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalCoroutinesApi::class) // Required for runTest
@Disabled("Requires a live echo server on localhost:12345")
class NettyRawTcpSocketTest {

    private lateinit var eventLoopGroup: NioEventLoopGroup

    @BeforeEach
    fun setUp() {
        eventLoopGroup = NioEventLoopGroup(1)
    }

    @AfterEach
    fun tearDown() {
        eventLoopGroup.shutdownGracefully().syncUninterruptibly()
    }

    @Test
    fun `connect, write, read, and close`() = runTest { // Use runTest
        val socket = NettyRawTcpSocket(eventLoopGroup)
        val testHost = "localhost"
        val testPort = Port(12345)

        val result = withTimeoutOrNull<Unit>(5000) { // Explicit type for withTimeoutOrNull
            try {
                socket.connect(testHost, testPort)
                assertTrue(socket.isOpen, "Socket should be open after connect")
                assertNotNull(socket.localAddress, "Local address should be set")
                assertNotNull(socket.remoteAddress, "Remote address should be set")

                val message = "Hello, Echo!"
                val writeBuffer = ByteBuffer.wrap(message.toByteArray(StandardCharsets.UTF_8))
                val bytesWritten = socket.write(writeBuffer)
                assertEquals(message.length, bytesWritten, "Should write all bytes")

                val readBuffer = ByteBuffer.allocate(1024)
                // Delay slightly for the echo server to respond.
                // In a real-world scenario, a more robust polling/waiting mechanism might be needed.
                delay(200) // Increased delay slightly

                val bytesRead = socket.read(readBuffer)
                assertTrue(bytesRead > 0, "Should read some bytes from echo server. Read: $bytesRead. Buffer content: ${readBuffer.array().decodeToString(0, if(bytesRead > 0) bytesRead else 0)}")

                readBuffer.flip()
                val receivedMessage = StandardCharsets.UTF_8.decode(readBuffer).toString().substring(0, bytesRead)
                assertEquals(message, receivedMessage, "Received message should match sent message")

            } catch (e: Exception) {
                if (e.message?.contains("Connection refused") == true) {
                    fail("Connection refused. Ensure an echo server is running on $testHost:$testPort.", e)
                }
                // Fail the test if any other exception occurs within the try block
                fail("Test threw an unexpected exception: ${e.message}", e)
            } finally {
                socket.close() // Ensure socket is closed
                assertFalse(socket.isOpen, "Socket should be closed")
            }
        }
        if (result == null) {
            fail("Test timed out")
        }
    }

    @Test
    fun `read returns -1 on EOF`() = runTest { // Use runTest
        val socket = NettyRawTcpSocket(eventLoopGroup)
        val testHost = "localhost"
        val testPort = Port(12345)

        val result = withTimeoutOrNull<Unit>(5000) { // Explicit type
            try {
                socket.connect(testHost, testPort)
                val writeBuffer = ByteBuffer.wrap("bye".toByteArray(StandardCharsets.UTF_8))
                socket.write(writeBuffer)

                val tempReadBuffer = ByteBuffer.allocate(32)
                // Attempt to read the echo. It might be empty if the server closes fast.
                socket.read(tempReadBuffer)

                // Close the socket from our side to ensure EOF on subsequent reads
                socket.close()
                assertTrue(!socket.isOpen, "Socket should be closed before final read.")

                val readBuffer = ByteBuffer.allocate(1024)
                val bytesRead = socket.read(readBuffer)
                assertEquals(-1, bytesRead, "Read should return -1 after socket is closed (EOF)")

            } catch (e: Exception) {
                if (e.message?.contains("Connection refused") == true) {
                    fail("Connection refused for EOF test. Ensure an echo server is running on $testHost:$testPort.", e)
                }
                 fail("Test threw an unexpected exception: ${e.message}", e)
            } finally {
                socket.close() // Ensure socket is closed
            }
        }
        if (result == null) {
            fail("Test timed out for EOF test")
        }
    }
}
