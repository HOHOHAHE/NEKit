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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class Socks5ProxySocketTest {

    private lateinit var mockClientSocket: RawTcpSocket
    private val capturedWrites = mutableListOf<ByteArray>()

    @BeforeEach
    fun setUp() {
        mockClientSocket = mockk<RawTcpSocket>(relaxed = true)
        capturedWrites.clear()

        coEvery { mockClientSocket.isOpen } returns true
        coEvery { mockClientSocket.remoteAddress } returns IpAddress("1.2.3.4")
        coEvery { mockClientSocket.localAddress } returns IpAddress("127.0.0.1")
        coEvery { mockClientSocket.close() } just runs

        // Capture writes
        coEvery { mockClientSocket.write(any()) } coAnswers {
            val buffer = firstArg<ByteBuffer>()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            capturedWrites.add(bytes)
            bytes.size
        }
    }

    // Helper to simulate sequential reads from the client
    private fun prepareSocketReads(vararg byteArrays: ByteArray) {
        val readQueue = ArrayDeque(byteArrays.map { ByteBuffer.wrap(it) })
        coEvery { mockClientSocket.read(any()) } coAnswers {
            val buffer = firstArg<ByteBuffer>() // Target buffer from SOCKS5 socket
            if (readQueue.isEmpty()) return@coAnswers -1

            val currentSourceBuffer = readQueue.first()
            val bytesToCopy = minOf(buffer.remaining(), currentSourceBuffer.remaining())

            if (bytesToCopy > 0) {
                val temp = ByteArray(bytesToCopy)
                currentSourceBuffer.get(temp)
                buffer.put(temp)
            }

            if (!currentSourceBuffer.hasRemaining()) {
                readQueue.removeFirst()
            }
            bytesToCopy
        }
    }


    @Test
    fun `valid SOCKS5 handshake and IPv4 request`() = runTest {
        // Client: VER=5, NMETHODS=1, METHODS=[NO_AUTH]
        val clientHandshake = byteArrayOf(0x05, 0x01, 0x00)
        // Client: VER=5, CMD=CONNECT, RSV=0, ATYP=IPV4, DST.ADDR=1.2.3.4, DST.PORT=80
        val clientRequest = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x05, 0x01, 0x00, 0x01)) // VER, CMD, RSV, ATYP_IPV4
            write(byteArrayOf(1, 2, 3, 4))             // DST.ADDR (1.2.3.4)
            write(byteArrayOf(0x00, 0x50))             // DST.PORT (80)
        }.toByteArray()
        prepareSocketReads(clientHandshake, clientRequest)

        val socksSocket = Socks5ProxySocket(mockClientSocket)
        val job = launch { socksSocket.handleIncomingConnection() }
        val session = withTimeoutOrNull(1000) { socksSocket.getConnectSession().first() }
        job.join()

        assertNotNull(session)
        assertEquals("1.2.3.4", session!!.host)
        assertEquals(Port(80), session.port)

        assertEquals(1, capturedWrites.size, "Should have written 1 reply (handshake response)")
        assertArrayEquals(byteArrayOf(0x05, 0x00), capturedWrites[0], "Handshake response should select NO_AUTH")

        coVerify { socksSocket.respondToSuccess() wasNot Called } // respondToSuccess is called by Tunnel
    }

    @Test
    fun `valid SOCKS5 handshake and domain request`() = runTest {
        val clientHandshake = byteArrayOf(0x05, 0x01, 0x00)
        val domain = "example.com"
        val domainBytes = domain.toByteArray()
        val clientRequest = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x05, 0x01, 0x00, 0x03))      // VER, CMD, RSV, ATYP_DOMAIN
            write(domainBytes.size)                         // Domain length
            write(domainBytes)                              // Domain
            write(byteArrayOf(0x01, 0xBB.toByte()))         // DST.PORT (443)
        }.toByteArray()
        prepareSocketReads(clientHandshake, clientRequest)

        val socksSocket = Socks5ProxySocket(mockClientSocket)
         val job = launch { socksSocket.handleIncomingConnection() }
        val session = withTimeoutOrNull(1000) { socksSocket.getConnectSession().first() }
        job.join()

        assertNotNull(session)
        assertEquals("example.com", session!!.host)
        assertEquals(Port(443), session.port)
        assertEquals(1, capturedWrites.size)
        assertArrayEquals(byteArrayOf(0x05, 0x00), capturedWrites[0])
    }

    @Test
    fun `handshake fails if no acceptable auth method`() = runTest {
        // Client: VER=5, NMETHODS=1, METHODS=[USERNAME_PASSWORD (0x02)] - we don't support this
        val clientHandshake = byteArrayOf(0x05, 0x01, 0x02)
        prepareSocketReads(clientHandshake)

        val socksSocket = Socks5ProxySocket(mockClientSocket)
        var exceptionThrown = false
        val job = launch {
            try {
                socksSocket.handleIncomingConnection()
                socksSocket.getConnectSession().first() // Should not reach here
            } catch (e: Socks5ErrorReplyException) {
                assertEquals(0xFF.toByte(), e.replyCode)
                exceptionThrown = true
            } catch (e: Exception) {
                fail("Unexpected exception: $e")
            }
        }
        job.join()

        assertTrue(exceptionThrown, "Socks5ErrorReplyException should be thrown")
        assertEquals(1, capturedWrites.size, "Should have written 1 reply (handshake failure)")
        assertArrayEquals(byteArrayOf(0x05, 0xFF.toByte()), capturedWrites[0], "Handshake response should be NO_ACCEPTABLE_METHODS")
        coVerify { mockClientSocket.close() }
    }


    @Test
    fun `request fails for unsupported command`() = runTest {
        val clientHandshake = byteArrayOf(0x05, 0x01, 0x00) // Successful handshake
        // Client: VER=5, CMD=BIND (0x02), RSV=0, ATYP=IPV4, ...
        val clientRequest = byteArrayOf(0x05, 0x02, 0x00, 0x01, 1, 2, 3, 4, 0, 80)
        prepareSocketReads(clientHandshake, clientRequest)

        val socksSocket = Socks5ProxySocket(mockClientSocket)
        var exceptionThrown = false
        val job = launch {
            try {
                socksSocket.handleIncomingConnection()
                socksSocket.getConnectSession().first()
            } catch (e: Socks5ErrorReplyException) {
                 // Check for general failure or command not supported if we had such a code
                assertEquals(0x01.toByte(), e.replyCode) // General SOCKS server failure (or 0x07 for Command not supported)
                exceptionThrown = true
            } catch (e: Exception) {
                fail("Unexpected exception: $e")
            }
        }
        job.join()

        assertTrue(exceptionThrown, "Socks5ErrorReplyException for unsupported command should be thrown")
        // Handshake success (1st write) + error reply for request (2nd write)
        // The error reply for the request phase is sent by handleIncomingConnection's catch block
        // after performHandshake is done.
        // So, we expect two writes: 1 for handshake ack, 1 for cmd failure.
        // However, the current Socks5ErrorReplyException in readRequestAndParseSession
        // doesn't send the reply itself. The reply is sent by the catch block in handleIncomingConnection.
        // Let's check the captured writes.
        // 1. Handshake response: [0x05, 0x00]
        // 2. Failure response from handleIncomingConnection (after request parsing fails)
        assertEquals(2, capturedWrites.size)
        assertArrayEquals(byteArrayOf(0x05, 0x00), capturedWrites[0]) // Handshake OK

        val failureReply = capturedWrites[1]
        assertEquals(0x05.toByte(), failureReply[0], "Failure reply VER") // VER
        assertEquals(0x01.toByte(), failureReply[1], "Failure reply REP (General Failure)") // REP (General Failure)

        coVerify { mockClientSocket.close() }
    }


    @Test
    fun `respondToSuccess sends SOCKS5 success reply`() = runTest {
        val socksSocket = Socks5ProxySocket(mockClientSocket)
        // Simulate local bound address for reply
        coEvery { mockClientSocket.localAddress } returns IpAddress("192.168.1.100")
        // If localAddress is an InetSocketAddress, its port can be used too.
        // For simplicity, the mock uses IpAddress directly. We will assume Port(0) if not an InetSocketAddress.
        // Or better, let respondToSuccess take the bound address if known.
        // The current implementation uses localAddress of clientSocket.

        socksSocket.respondToSuccess()

        assertEquals(1, capturedWrites.size)
        val reply = capturedWrites[0]
        assertArrayEquals(
            byteArrayOf(
                0x05, // VER
                0x00, // REP_SUCCEEDED
                0x00, // RSV
                0x01, // ATYP_IPV4
                // IP address bytes for 192.168.1.100, as per mockClientSocket.localAddress
                192.toByte(), 168.toByte(), 1.toByte(), 100.toByte(), // BND.ADDR (192.168.1.100)
                0,0   // Simplified BND.PORT (0)
            ),
            // The actual reply might differ slightly if localAddress is more detailed in a real scenario.
            // The sendReply method defaults to 0.0.0.0 if it can't determine better.
            // Let's check the parts we're sure about: VER, REP, RSV, ATYP (assuming IPv4 default)
            byteArrayOf(reply[0], reply[1], reply[2], reply[3], reply[4],reply[5],reply[6],reply[7], reply[8], reply[9])
        )
         assertEquals(0x05.toByte(), reply[0]) // VER
         assertEquals(0x00.toByte(), reply[1]) // REP_SUCCEEDED
         assertEquals(0x00.toByte(), reply[2]) // RSV
         // ATYP and BND.ADDR/PORT depend on how sendReply resolves them.
         // Given the current mock, it will likely default to ATYP_IPV4 and 192.168.1.100:0
         assertEquals(0x01.toByte(), reply[3]) // ATYP_IPV4 (default)
         assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1.toByte(), 100.toByte()), reply.sliceArray(4..7)) // BND.ADDR (192.168.1.100)
         assertArrayEquals(byteArrayOf(0,0), reply.sliceArray(8..9))   // BND.PORT (0)
    }
}
