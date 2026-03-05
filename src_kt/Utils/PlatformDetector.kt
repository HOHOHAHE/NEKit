package nekit.Utils

/**
 * Runtime platform detection utility.
 * Determines whether the code is running on Android (device/emulator)
 * or on a JVM host (e.g., macOS via `runLocal`).
 */
object PlatformDetector {
    /**
     * True when running on Android (Dalvik/ART VM), false on desktop JVM.
     */
    val isAndroid: Boolean = try {
        Class.forName("android.os.Build")
        true
    } catch (e: ClassNotFoundException) {
        false
    }
}
