import Foundation
#if canImport(Clibsodium)
import Clibsodium
#endif
import Sodium

open class Libsodium {
    /// This must be accessed at least once before Libsodium is used.
    public static let initialized: Bool = {
        // this is loaded lasily and also thread-safe
        _ = sodium_init()
        return true
    }()
}
