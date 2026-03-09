import Foundation

public class GlobalNetworkManager {
    public static let shared = GlobalNetworkManager()
    
    public var currentActiveInterface: NetworkInterfaceType = .default
    
    private init() {}
}
