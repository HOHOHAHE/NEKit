package io.github.hohohahe.nekitkotlin.socket.proxy

import io.github.hohohahe.nekitkotlin.core.ConnectSession
import io.github.hohohahe.nekitkotlin.core.IpAddress
import io.github.hohohahe.nekitkotlin.core.Port
import io.github.hohohahe.nekitkotlin.socket.raw.RawTcpSocket
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalCoroutinesApi::class)
class HttpProxySocketTest {

    private lateinit var mockClientSocket: RawTcpSocket

    @BeforeEach
    fun setUp() {
        mockClientSocket = mockk<RawTcpSocket>(relaxed = true)
        // Default mock behaviors
        coEvery { mockClientSocket.isOpen } returns true
        coEvery { mockClientSocket.remoteAddress } returns IpAddress("1.2.3.4") // Dummy client IP
        coEvery { mockClientSocket.localAddress } returns IpAddress("127.0.0.1")
        coEvery { mockClientSocket.close() } just runs
    }

    private fun prepareSocketRead(data: String) {
        val byteBuffer = ByteBuffer.wrap(data.toByteArray(StandardCharsets.US_ASCII))
        // Simulate read: fill the passed buffer and return its size
                coEvery { mockClientSocket.read(any()) } coAnswers {
                    val buffer = firstArg<ByteBuffer>()
            if (!byteBuffer.hasRemaining()) return@coAnswers -1 // EOF if source is empty
            val len = minOf(buffer.remaining(), byteBuffer.remaining())
            val temp = ByteArray(len)
            byteBuffer.get(temp)
            buffer.put(temp)
            len
        }
    }
    
    private fun prepareSocketReadChunks(chunks: List<String>) {
        val chunkQueue = ArrayDeque(chunks.map { ByteBuffer.wrap(it.toByteArray(StandardCharsets.US_ASCII)) })
        
        coEvery { mockClientSocket.read(any()) } coAnswers {
            val buffer = firstArg<ByteBuffer>() // The buffer provided by HttpProxySocket
            if (chunkQueue.isEmpty()) return@coAnswers -1 // EOF if no more chunks

            val currentChunk = chunkQueue.first() // Peek the current chunk
            
            val bytesToRead = minOf(buffer.remaining(), currentChunk.remaining())
            if (bytesToRead == 0 && currentChunk.hasRemaining()) { // Buffer full but chunk still has data
                return@coAnswers 0 // Indicate buffer is full, can't read more now
            }
            if (bytesToRead == 0 && !currentChunk.hasRemaining()) { // Chunk empty, try next
                 chunkQueue.removeFirst() // Consume empty chunk
                 if (chunkQueue.isEmpty()) return@coAnswers -1
                 // This recursive call is tricky with coAnswers. Better to signal 0 and let caller retry.
                 // For simplicity, assume caller handles 0 correctly or we ensure chunks are non-empty if possible.
                 return@coAnswers 0 // Let caller try again
            }

            val temp = ByteArray(bytesToRead)
            currentChunk.get(temp)
            buffer.put(temp)

            if (!currentChunk.hasRemaining()) {
                chunkQueue.removeFirst() // Consume the chunk if fully read
            }
            bytesToRead
        }
    }


    @Test
    fun `getConnectSession parses valid HTTP CONNECT request`() = runTest {
        val request = "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\n\r\n"
        prepareSocketRead(request)

        val httpProxySocket = HttpProxySocket(mockClientSocket)
        // Launch the handler, as it's now a suspend fun
        val job = launch { httpProxySocket.handleIncomingConnection() }

        val session = withTimeoutOrNull(1000) { httpProxySocket.getConnectSession().first() }
        
        assertNotNull(session)
        assertEquals("example.com", session!!.host)
        assertEquals(Port(443), session.port)
        
        job.join() // Ensure handler completes
        coVerify { mockClientSocket.close() wasNot Called } // Should not close on success yet
    }
    
    @Test
    fun `getConnectSession parses valid HTTP CONNECT request in chunks`() = runTest {
        val chunks = listOf(
            "CONN",
            "ECT example.com:80 HTTP/1.1\r\n",
            "User-Agent: test\r\n",
            "Host: example.com\r\n\r\n"
        )
        prepareSocketReadChunks(chunks)

        val httpProxySocket = HttpProxySocket(mockClientSocket)
        val job = launch { httpProxySocket.handleIncomingConnection() }
        val session = withTimeoutOrNull(1000) { httpProxySocket.getConnectSession().first() }
        
        assertNotNull(session)
        assertEquals("example.com", session!!.host)
        assertEquals(Port(80), session.port)
        job.join()
    }


    @Test
    fun `handleIncomingConnection throws for non-CONNECT method`() = runTest {
        val request = "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n"
        prepareSocketRead(request)
        val httpProxySocket = HttpProxySocket(mockClientSocket)

        var exceptionThrown = false
        val job = launch {
            try {
                httpProxySocket.handleIncomingConnection()
                // We expect an exception, so flow should not emit normally
                httpProxySocket.getConnectSession().first() 
            } catch (e: IOException) {
                if (e.message?.contains("Unsupported HTTP method") == true) {
                    exceptionThrown = true
                } else {
                    throw e // Re-throw if not the expected exception
                }
            }
        }
        job.join() // Wait for handleIncomingConnection to complete

        assertTrue(exceptionThrown, "IOException for unsupported method should be thrown")
        coVerify { mockClientSocket.write(match { byteBuffer ->
            String(byteBuffer.array(), byteBuffer.arrayOffset(), byteBuffer.remaining(), StandardCharsets.US_ASCII).startsWith("HTTP/1.1 405 Method Not Allowed")
        }) }
        coVerify { mockClientSocket.close() } // Should close after sending error
    }
    
    @Test
    fun `handleIncomingConnection throws for malformed CONNECT target`() = runTest {
        val request = "CONNECT example.com HTTP/1.1\r\nHost: example.com\r\n\r\n" // Missing port
        prepareSocketRead(request)
        val httpProxySocket = HttpProxySocket(mockClientSocket)

        var exceptionThrown = false
         val job = launch {
            try {
                httpProxySocket.handleIncomingConnection()
                httpProxySocket.getConnectSession().first()
            } catch (e: IOException) {
                if (e.message?.contains("Malformed CONNECT target") == true) {
                    exceptionThrown = true
                } else { throw e }
            }
        }
        job.join()
        
        assertTrue(exceptionThrown, "IOException for malformed CONNECT target should be thrown")
        coVerify { mockClientSocket.write(match { byteBuffer -> // Should send a 502 or similar
            val responseString = String(byteBuffer.array(), byteBuffer.arrayOffset(), byteBuffer.remaining(), StandardCharsets.US_ASCII)
            responseString.startsWith("HTTP/1.1 502 Bad Gateway") || responseString.startsWith("HTTP/1.1 400 Bad Request") // Or whatever error it sends for this
        }) }
        coVerify { mockClientSocket.close() }
    }


    @Test
    fun `respondToSuccess sends HTTP 200`() = runTest {
        val httpProxySocket = HttpProxySocket(mockClientSocket)
        httpProxySocket.respondToSuccess()

        coVerify { mockClientSocket.write(match { byteBuffer ->
            val response = String(byteBuffer.array(), byteBuffer.arrayOffset(), byteBuffer.remaining(), StandardCharsets.US_ASCII)
            response == "HTTP/1.1 200 Connection Established\r\n\r\n"
        })}
    }

    @Test
    fun `respondToFailure sends HTTP 502`() = runTest {
        val httpProxySocket = HttpProxySocket(mockClientSocket)
        httpProxySocket.respondToFailure("test failure")

        coVerify { mockClientSocket.write(match { byteBuffer ->
             String(byteBuffer.array(), byteBuffer.arrayOffset(), byteBuffer.remaining(), StandardCharsets.US_ASCII).startsWith("HTTP/1.1 502 Bad Gateway")
        })}
         coVerify { mockClientSocket.close() } // Failure response should also close
    }
    
    @Test
    fun `closes socket if read fails during header parsing`() = runTest {
        coEvery { mockClientSocket.read(any()) } returns -1 // Simulate immediate EOF

        val httpProxySocket = HttpProxySocket(mockClientSocket)
        var exceptionThrown = false
        val job = launch {
            try {
                httpProxySocket.handleIncomingConnection()
                httpProxySocket.getConnectSession().first() 
            } catch (e: IOException) {
                 if (e.message?.contains("Connection closed by client") == true) {
                    exceptionThrown = true
                } else { throw e }
            }
        }
        job.join()
        assertTrue(exceptionThrown)
        coVerify { mockClientSocket.close() }
    }
}
