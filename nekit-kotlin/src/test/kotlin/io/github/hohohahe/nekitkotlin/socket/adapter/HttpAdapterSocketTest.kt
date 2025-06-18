package io.github.hohohahe.nekitkotlin.socket.adapter

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.core.IpAddress
import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.socket.raw.RawTcpSocket
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalCoroutinesApi::class)
class HttpAdapterSocketTest {
    private lateinit var mockRawSocket: RawTcpSocket
    private val proxyHost = "proxy.example.com"
    private val proxyPort = Port(8080)

    @BeforeEach
    fun setUp() {
        mockRawSocket = mockk<RawTcpSocket>(relaxed = true) // Keep it relaxed
        // General mocks, will be overridden by tests if specific behavior is needed for isOpen or connect
        coEvery { mockRawSocket.write(any()) } returns 0
        coEvery { mockRawSocket.close() } just Runs
    }

    private fun prepareReadResponse(response: String) {
        val byteBuffer = ByteBuffer.wrap(response.toByteArray(StandardCharsets.US_ASCII))
        coEvery { mockRawSocket.read(any()) } coAnswers {
            val buffer = firstArg<ByteBuffer>()
            if (!byteBuffer.hasRemaining()) -1 else {
                val len = minOf(buffer.remaining(), byteBuffer.remaining())
                val temp = ByteArray(len)
                byteBuffer.get(temp)
                buffer.put(temp)
                len
            }
        }
    }

    private fun prepareReadResponseChunks(chunks: List<String>) {
        val chunkQueue = ArrayDeque(chunks.map { ByteBuffer.wrap(it.toByteArray(StandardCharsets.US_ASCII)) })
        coEvery { mockRawSocket.read(any()) } coAnswers {
            val buffer = firstArg<ByteBuffer>()
            if (chunkQueue.isEmpty()) -1 else {
                val currentChunk = chunkQueue.first()
                val bytesToRead = minOf(buffer.remaining(), currentChunk.remaining())
                if (bytesToRead > 0) {
                    val temp = ByteArray(bytesToRead)
                    currentChunk.get(temp)
                    buffer.put(temp)
                }
                if (!currentChunk.hasRemaining()) chunkQueue.removeFirst()
                bytesToRead
            }
        }
    }


    @Test
    fun `openSocket successfully connects and handshakes`() = runTest {
        coEvery { mockRawSocket.isOpen } returns false // Initial state: not open
        coEvery { mockRawSocket.connect(proxyHost, proxyPort) } coAnswers {
            coEvery { mockRawSocket.isOpen } returns true // After connect, it's open
        }

        val adapter = HttpAdapterSocket(proxyHost, proxyPort, mockRawSocket)
        val targetSession = ConnectSession("target.example.com", Port(443))

        val proxyResponse = "HTTP/1.1 200 Connection Established\r\n\r\n"
        prepareReadResponse(proxyResponse)

        coEvery { mockRawSocket.write(any()) } coAnswers {
            val buffer = firstArg<ByteBuffer>()
            val requestString = StandardCharsets.US_ASCII.decode(buffer).toString()
            assertTrue(requestString.startsWith("CONNECT target.example.com:443 HTTP/1.1"))
            assertTrue(requestString.contains("Host: target.example.com:443"))
            buffer.position(buffer.limit()) // Simulate all bytes written by advancing position
            requestString.length
        }

        adapter.openSocket(targetSession)
        assertTrue(adapter.isReady.value)
        coVerify { mockRawSocket.connect(proxyHost, proxyPort) }
    }

    @Test
    fun `openSocket handles multi-chunk proxy response`() = runTest {
        coEvery { mockRawSocket.isOpen } returns false // Initial state
        coEvery { mockRawSocket.connect(proxyHost, proxyPort) } coAnswers {
            coEvery { mockRawSocket.isOpen } returns true // After connect
        }

        val adapter = HttpAdapterSocket(proxyHost, proxyPort, mockRawSocket)
        val targetSession = ConnectSession("target.example.com", Port(80))
        prepareReadResponseChunks(listOf("HTTP/1.1 200 OK\r\n", "Proxy-Agent: TestProxy\r\n", "\r\n"))
        coEvery { mockRawSocket.write(any()) } coAnswers { firstArg<ByteBuffer>().limit() }


        adapter.openSocket(targetSession)
        assertTrue(adapter.isReady.value)
    }


    @Test
    fun `openSocket throws if proxy returns non-200 status`() = runTest {
        coEvery { mockRawSocket.isOpen } returns false // Initial state
        coEvery { mockRawSocket.connect(proxyHost, proxyPort) } coAnswers {
            coEvery { mockRawSocket.isOpen } returns true // After connect
        }
        coEvery { mockRawSocket.write(any()) } coAnswers { firstArg<ByteBuffer>().limit() }


        val adapter = HttpAdapterSocket(proxyHost, proxyPort, mockRawSocket)
        val targetSession = ConnectSession("target.example.com", Port(443))

        val proxyResponse = "HTTP/1.1 503 Service Unavailable\r\n\r\n"
        prepareReadResponse(proxyResponse)

        assertThrows(IOException::class.java) {
            runBlocking {
                 adapter.openSocket(targetSession)
            }
        }
        assertFalse(adapter.isReady.value)
        coVerify { mockRawSocket.close() }
    }

    @Test
    fun `openSocket throws on connection failure to proxy`() = runTest {
        coEvery { mockRawSocket.isOpen } returns false // Initial state
        coEvery { mockRawSocket.connect(proxyHost, proxyPort) } throws IOException("Connection refused")
        // isOpen remains false because connect failed

        val adapter = HttpAdapterSocket(proxyHost, proxyPort, mockRawSocket)
        val targetSession = ConnectSession("target.example.com", Port(443))

         assertThrows(IOException::class.java) {
            runBlocking {
                adapter.openSocket(targetSession)
            }
        }
        assertFalse(adapter.isReady.value)
        // Depending on HttpAdapterSocket's error handling, close might be called on the raw socket
        // In the current implementation of HttpAdapterSocket, close() is called in the catch block.
        coVerify { mockRawSocket.close() }
    }
}
