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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class Socks5AdapterSocketTest {
    private lateinit var mockRawSocket: RawTcpSocket
    private val proxyHost = "socks.example.com"
    private val proxyPort = Port(1080)
    private val capturedWrites = mutableListOf<ByteArray>()


    @BeforeEach
    fun setUp() {
        mockRawSocket = mockk<RawTcpSocket>(relaxed = true)
        capturedWrites.clear()
        // coEvery { mockRawSocket.isOpen } returns true // REMOVED - To be set by each test
        coEvery { mockRawSocket.connect(proxyHost, proxyPort) } just Runs // General case, can be overridden
        coEvery { mockRawSocket.write(any()) } coAnswers {
            val buffer = firstArg<ByteBuffer>()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            capturedWrites.add(bytes)
            bytes.size
        }
        coEvery { mockRawSocket.close() } just runs
    }

    private fun prepareReadSequence(vararg byteArrays: ByteArray) {
        val readQueue = ArrayDeque(byteArrays.map { ByteBuffer.wrap(it) })
        coEvery { mockRawSocket.read(any()) } coAnswers {
            val buffer = firstArg<ByteBuffer>() // Target buffer
            if (readQueue.isEmpty()) -1 else {
                val currentSource = readQueue.first()
                val bytesToCopy = minOf(buffer.remaining(), currentSource.remaining())
                if (bytesToCopy > 0) {
                    val temp = ByteArray(bytesToCopy)
                    currentSource.get(temp)
                    buffer.put(temp)
                }
                if (!currentSource.hasRemaining()) readQueue.removeFirst()
                bytesToCopy
            }
        }
    }


    @Test
    fun `openSocket successfully handshakes and connects (IPv4)`() = runTest {
        coEvery { mockRawSocket.isOpen } returns false // Initial state for this test
        coEvery { mockRawSocket.connect(proxyHost, proxyPort) } coAnswers {
            coEvery { mockRawSocket.isOpen } returns true // State after successful connect
        }

        val adapter = Socks5AdapterSocket(proxyHost, proxyPort, mockRawSocket)
        val targetSession = ConnectSession("1.2.3.4", Port(80)) // Target is IPv4

        // Proxy handshake response: VER=5, METHOD=NO_AUTH
        val proxyHandshakeResponse = byteArrayOf(0x05, 0x00)
        // Proxy connect response: VER=5, REP=SUCCEEDED, RSV=0, ATYP=IPV4, BND.ADDR=0.0.0.0, BND.PORT=0
        val proxyConnectResponse = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
        prepareReadSequence(proxyHandshakeResponse, proxyConnectResponse)

        adapter.openSocket(targetSession)
        assertTrue(adapter.isReady.value)

        assertEquals(2, capturedWrites.size)
        // Client handshake: VER=5, NMETHODS=1, METHODS=[NO_AUTH]
        assertArrayEquals(byteArrayOf(0x05, 0x01, 0x00), capturedWrites[0])
        // Client connect request for 1.2.3.4:80
        val expectedConnectRequest = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x05, 0x01, 0x00, 0x01)) // VER, CMD, RSV, ATYP_IPV4
            write(byteArrayOf(1,2,3,4)) // DST.ADDR
            write(byteArrayOf(0, 80))   // DST.PORT
        }.toByteArray()
        assertArrayEquals(expectedConnectRequest, capturedWrites[1])
    }

    @Test
    fun `openSocket successfully handshakes and connects (Domain)`() = runTest {
        coEvery { mockRawSocket.isOpen } returns false // Initial state for this test
        coEvery { mockRawSocket.connect(proxyHost, proxyPort) } coAnswers {
            coEvery { mockRawSocket.isOpen } returns true // State after successful connect
        }

        val adapter = Socks5AdapterSocket(proxyHost, proxyPort, mockRawSocket)
        val targetHost = "domain.example"
        val targetPort = Port(443)
        val targetSession = ConnectSession(targetHost, targetPort)

        val proxyHandshakeResponse = byteArrayOf(0x05, 0x00)
        val proxyConnectResponse = byteArrayOf(0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0) // BND.ADDR can be anything
        prepareReadSequence(proxyHandshakeResponse, proxyConnectResponse)

        adapter.openSocket(targetSession)
        assertTrue(adapter.isReady.value)

        assertEquals(2, capturedWrites.size)
        assertArrayEquals(byteArrayOf(0x05, 0x01, 0x00), capturedWrites[0]) // Handshake

        val domainBytes = targetHost.toByteArray()
        val expectedConnectRequest = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x05, 0x01, 0x00, 0x03)) // VER, CMD, RSV, ATYP_DOMAIN
            write(domainBytes.size)
            write(domainBytes)
            writeShort(targetPort.value)
        }.toByteArray()
        assertArrayEquals(expectedConnectRequest, capturedWrites[1]) // Connect request
    }


    @Test
    fun `openSocket throws if handshake fails (no acceptable methods)`() = runTest {
        coEvery { mockRawSocket.isOpen } returns false // Initial state for this test
        coEvery { mockRawSocket.connect(proxyHost, proxyPort) } coAnswers {
            coEvery { mockRawSocket.isOpen } returns true // Connects, but handshake will fail
        }

        val adapter = Socks5AdapterSocket(proxyHost, proxyPort, mockRawSocket)
        val targetSession = ConnectSession("target.example.com", Port(443))
        // Proxy handshake response: VER=5, METHOD=NO_ACCEPTABLE_METHODS
        val proxyHandshakeResponse = byteArrayOf(0x05, 0xFF.toByte())
        prepareReadSequence(proxyHandshakeResponse)

        assertThrows(IOException::class.java) {
            runBlocking { adapter.openSocket(targetSession) }
        }
        assertFalse(adapter.isReady.value)
        assertEquals(1, capturedWrites.size) // Only client handshake sent
        coVerify { mockRawSocket.close() }
    }

    @Test
    fun `openSocket throws if connect reply is failure`() = runTest {
        coEvery { mockRawSocket.isOpen } returns false // Initial state for this test
        coEvery { mockRawSocket.connect(proxyHost, proxyPort) } coAnswers {
            coEvery { mockRawSocket.isOpen } returns true // Connects, but SOCKS reply will be failure
        }

        val adapter = Socks5AdapterSocket(proxyHost, proxyPort, mockRawSocket)
        val targetSession = ConnectSession("target.example.com", Port(443))

        val proxyHandshakeResponse = byteArrayOf(0x05, 0x00) // Handshake OK
        // Proxy connect response: VER=5, REP=HOST_UNREACHABLE (0x04), ...
        val proxyConnectResponse = byteArrayOf(0x05, 0x04, 0x00, 0x01, 0,0,0,0, 0,0)
        prepareReadSequence(proxyHandshakeResponse, proxyConnectResponse)

        assertThrows(IOException::class.java) {
             runBlocking { adapter.openSocket(targetSession) }
        }
        assertFalse(adapter.isReady.value)
        assertEquals(2, capturedWrites.size) // Handshake + Connect request
        coVerify { mockRawSocket.close() }
    }
     // Helper to write short value to ByteArrayOutputStream
    private fun ByteArrayOutputStream.writeShort(value: Int) {
        this.write((value ushr 8) and 0xFF)
        this.write(value and 0xFF)
    }
}
