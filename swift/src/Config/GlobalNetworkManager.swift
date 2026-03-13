import Foundation

public class GlobalNetworkManager {
    public static let shared = GlobalNetworkManager()
    
    public var activeInterface: NetworkInterfaceType = .default
    
    private init() {}
}
