package nekit.IPStack.Native

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Memory
import org.slf4j.LoggerFactory

// Assumes TunDeviceInterface.kt is in the same package or imported correctly.

/**
 * JNA interface mapping for native C library functions related to TUN/TAP devices.
 * The actual function names and library name ("c" is a placeholder for libc)
 * may vary significantly based on the OS and any helper libraries used.
 * These are hypothetical common names.
 */
interface CLibraryTun : Library {
    companion object {
        // Placeholder library name. For Linux, TUN/TAP might be via libc's ioctl.
        // For macOS/BSD, specific system calls or a helper library might be needed.
        // For Windows, it's entirely different (e.g., tap-windows driver API).
        // Ideally, a small C wrapper library would provide a consistent API across platforms.
        private const val PLATFORM_LIB_NAME = "c" // Default to libc, may need to change
        val INSTANCE: CLibraryTun by lazy {
            try {
                Native.load(PLATFORM_LIB_NAME, CLibraryTun::class.java) as CLibraryTun
            } catch (e: UnsatisfiedLinkError) {
                // Fallback or alternative library names can be tried here if needed
                LoggerFactory.getLogger(CLibraryTun::class.java)
                    .error("Failed to load native library '{}'. TUN/TAP functionality will not work. Error: {}", PLATFORM_LIB_NAME, e.message)
                // Return a dummy implementation or rethrow, depending on desired error handling
                // For now, if it fails, subsequent calls will fail.
                throw e // Or handle more gracefully by returning a non-functional instance
            }
        }

        // Hypothetical ioctl requests for MTU (Linux specific example)
        // const val SIOCGIFMTU: Int = 0x8921 // From <sys/ioctl.h> on Linux
        // const val IFNAMSIZ: Int = 16 // From <net/if.h>
    }

    // Hypothetical C function signatures
    fun open_tun(dev_name: String?): Int // Returns fd or error
    fun read_tun(fd: Int, buf: Pointer?, count: Int): Int // Pointer to buffer
    fun write_tun(fd: Int, buf: Pointer?, count: Int): Int // Pointer to buffer
    fun close_tun(fd: Int): Int
    fun get_tun_name_if(fd: Int, name_buf: Pointer?, len: Int): Int // Writes name to name_buf, returns 0 or error
    fun get_mtu_if(if_name: String?): Int // Hypothetical direct MTU getter by name

    // Generic ioctl for more complex operations (OS-specific structs would be needed)
    // fun ioctl(fd: Int, request: Int, argp: Pointer?): Int
}

/**
 * JNA-based implementation of the [TunDeviceInterface].
 * This class uses the JNA mappings defined in [CLibraryTun] to interact with
 * native TUN device functions.
 */
class JnaTunDevice : TunDeviceInterface {
    private val logger = LoggerFactory.getLogger(JnaTunDevice::class.java)
    private val nativeLib = CLibraryTun.INSTANCE // Get instance of mapped library

    override fun openTun(name: String?): Int {
        logger.debug("Attempting to open TUN device (name: {})", name ?: "default")
        try {
            val fd = nativeLib.open_tun(name)
            if (fd < 0) {
                logger.error("Failed to open TUN device (name: {}). Native function returned error code: {}", name ?: "default", fd)
                // TODO: Map errno or native error to a more specific exception or return code
            } else {
                logger.info("Successfully opened TUN device (name: {}). File descriptor: {}", name ?: "default", fd)
            }
            return fd
        } catch (e: UnsatisfiedLinkError) {
            logger.error("UnsatisfiedLinkError calling open_tun. Ensure native library is available and correctly mapped.", e)
            return -1 // Or throw custom exception
        } catch (e: Exception) {
            logger.error("Exception calling open_tun: {}", e.message, e)
            return -1
        }
    }

    override fun closeTun(fd: Int): Int {
        logger.debug("Closing TUN device with fd: {}", fd)
        try {
            val result = nativeLib.close_tun(fd)
            if (result != 0) {
                logger.error("Failed to close TUN device with fd: {}. Native function returned: {}", fd, result)
            } else {
                logger.info("Successfully closed TUN device with fd: {}", fd)
            }
            return result
        } catch (e: UnsatisfiedLinkError) {
            logger.error("UnsatisfiedLinkError calling close_tun. Ensure native library is available.", e)
            return -1
        } catch (e: Exception) {
            logger.error("Exception calling close_tun for fd {}: {}", fd, e.message, e)
            return -1
        }
    }

    override fun readPacket(fd: Int, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        if (bufferOffset < 0 || length <= 0 || bufferOffset + length > buffer.size) {
            logger.error("Invalid buffer parameters for readPacket: offset={}, length={}, buffer size={}", bufferOffset, length, buffer.size)
            return -1 // Or throw IllegalArgumentException
        }
        // Using Memory for the buffer passed to native code
        val nativeBuffer = Memory(length.toLong())
        logger.trace("Reading packet from TUN fd: {}, requested length: {}", fd, length)
        try {
            val bytesRead = nativeLib.read_tun(fd, nativeBuffer, length)
            if (bytesRead > 0) {
                nativeBuffer.read(0, buffer, bufferOffset, bytesRead)
                logger.debug("Read {} bytes from TUN fd: {}", bytesRead, fd)
            } else if (bytesRead == 0) {
                logger.trace("Read 0 bytes from TUN fd: {} (non-blocking, no packet available or EOF)", fd)
            } else { // bytesRead < 0
                logger.warn("Error reading from TUN fd: {}. Native read returned: {}", fd, bytesRead)
                // TODO: Check errno for EAGAIN/EWOULDBLOCK if non-blocking, or map other errors
            }
            return bytesRead
        } catch (e: UnsatisfiedLinkError) {
            logger.error("UnsatisfiedLinkError calling read_tun. Ensure native library is available.", e)
            return -1
        } catch (e: Exception) {
            logger.error("Exception calling read_tun for fd {}: {}", fd, e.message, e)
            return -1
        }
    }

    override fun writePacket(fd: Int, buffer: ByteArray, bufferOffset: Int, length: Int): Int {
        if (bufferOffset < 0 || length <= 0 || bufferOffset + length > buffer.size) {
            logger.error("Invalid buffer parameters for writePacket: offset={}, length={}, buffer size={}", bufferOffset, length, buffer.size)
            return -1 // Or throw IllegalArgumentException
        }
        val nativeBuffer = Memory(length.toLong())
        nativeBuffer.write(0, buffer, bufferOffset, length)
        logger.trace("Writing packet of {} bytes to TUN fd: {}", length, fd)
        try {
            val bytesWritten = nativeLib.write_tun(fd, nativeBuffer, length)
            if (bytesWritten < 0) {
                logger.error("Error writing to TUN fd: {}. Native write returned: {}", fd, bytesWritten)
            } else if (bytesWritten != length) {
                logger.warn("Partial write to TUN fd: {}. Expected to write {} bytes, but wrote {} bytes.", fd, length, bytesWritten)
            } else {
                logger.debug("Wrote {} bytes to TUN fd: {}", bytesWritten, fd)
            }
            return bytesWritten
        } catch (e: UnsatisfiedLinkError) {
            logger.error("UnsatisfiedLinkError calling write_tun. Ensure native library is available.", e)
            return -1
        } catch (e: Exception) {
            logger.error("Exception calling write_tun for fd {}: {}", fd, e.message, e)
            return -1
        }
    }

    override fun getTunName(fd: Int): String? {
        // Assuming the native function writes a null-terminated string into the buffer.
        val nameBufferLen = 256 // IFNAMSIZ is often 16, but provide more for safety/flexibility
        val nameMemory = Memory(nameBufferLen.toLong())
        logger.debug("Attempting to get TUN name for fd: {}", fd)
        try {
            // Hypothetical get_tun_name_if(fd, buffer, buffer_len) returns 0 on success
            val result = nativeLib.get_tun_name_if(fd, nameMemory, nameBufferLen)
            if (result == 0) {
                val tunName = nameMemory.getString(0) // Read as null-terminated C string
                logger.info("Retrieved TUN name for fd {}: {}", fd, tunName)
                return tunName.ifEmpty { null }
            } else {
                logger.error("Failed to get TUN name for fd {}. Native function returned: {}", fd, result)
                return null
            }
        } catch (e: UnsatisfiedLinkError) {
            logger.error("UnsatisfiedLinkError calling get_tun_name_if. Ensure native library is available.", e)
            return null
        } catch (e: Exception) {
            logger.error("Exception calling get_tun_name_if for fd {}: {}", fd, e.message, e)
            return null
        }
    }

    override fun getMTU(interfaceName: String): Int {
        logger.debug("Attempting to get MTU for interface: {}", interfaceName)
        try {
            // This is a placeholder for a more complex, OS-specific implementation.
            // A direct ioctl call via JNA for SIOCGIFMTU would require defining 'ifreq' struct
            // and knowing the correct ioctl number for the target OS.
            // Alternatively, a native helper function `get_mtu_if(name)` would be simpler to call.
            val mtu = nativeLib.get_mtu_if(interfaceName)
            if (mtu <= 0) { // Assuming native returns <=0 on error
                logger.warn("Failed to get MTU for interface {} or MTU is invalid: {}. Returning default 1500.", interfaceName, mtu)
                return 1500 // Default MTU
            }
            logger.info("Retrieved MTU for interface {}: {}", interfaceName, mtu)
            return mtu
        } catch (e: UnsatisfiedLinkError) {
            logger.error("UnsatisfiedLinkError calling get_mtu_if. MTU retrieval will use default. Error: {}", e.message, e)
            // Fallback to default if native function is missing
            return 1500
        } catch (e: Exception) {
            logger.error("Exception calling get_mtu_if for interface {}: {}. Using default 1500.", interfaceName, e.message, e)
            return 1500
        }
    }
}
