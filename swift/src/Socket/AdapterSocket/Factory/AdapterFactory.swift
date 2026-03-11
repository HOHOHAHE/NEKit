import Foundation

/// The base class of adapter factory.
open class AdapterFactory {
    public init() {}
    
    /**
     Build an adapter.

     - parameter session: The connect session.

     - returns: The built adapter.
     */
    open func getAdapterFor(session: ConnectSession) -> AdapterSocket {
        return getDirectAdapter(session: session)
    }

    /**
     Helper method to get a `DirectAdapter`.

     - returns: A direct adapter.
     */
    public func getDirectAdapter(session: ConnectSession? = nil) -> AdapterSocket {
        let adapter = DirectAdapter()
        adapter.socket = RawSocketFactory.getRawTCPSocket(requestedInterface: session?.interfaceType)
        return adapter
    }
}

/// Factory building direct adapters.
///
/// - note: This is needed since we need to identify direct adapter factory.
public class DirectAdapterFactory: AdapterFactory {
    public override init() {}
}
