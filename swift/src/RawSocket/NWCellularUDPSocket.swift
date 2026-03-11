import Foundation
import NetworkExtension
import Network
import CocoaLumberjackSwift

/// The delegate protocol of `NWCellularUDPSocket`.
public protocol NWCellularUDPSocketDelegate: class {
    /**
     Socket did receive data from remote.
     
     - parameter data: The data.
     - parameter from: The socket the data is read from.
     */
    func didReceive(data: Data, from: NWCellularUDPSocket)
    
    func didCancel(socket: NWCellularUDPSocket)
}

/// The wrapper for NWConnection to force cellular connection for UDP.
///
/// - note: This class is thread-safe.
public class NWCellularUDPSocket: NSObject {
    private var connection: NWConnection?
    private var pendingWriteData: [Data] = []
    private var writing = false
    private let queue: DispatchQueue = QueueFactory.getQueue()
    private let timer: DispatchSourceTimer
    private let timeout: Int
    private var cancelled = false
    
    public let host: String
    public let port: Int
    
    
    /// The delegate instance.
    public weak var delegate: NWCellularUDPSocketDelegate?
    
    /// The time when the last activity happens.
    ///
    /// Since UDP do not have a "close" semantic, this can be an indicator of timeout.
    public var lastActive: Date = Date()
    
    /**
     Create a new UDP socket connecting to remote.
     
     - parameter host: The host.
     - parameter port: The port.
     */
    public init?(host: String, port: Int, timeout: Int = Opt.UDPSocketActiveTimeout) {
        self.host = host
        self.port = port
        self.timeout = timeout
        
        timer = DispatchSource.makeTimerSource(queue: queue)
        
        super.init()
        
        let nwEndpoint = NWEndpoint.hostPort(host: .name(host, nil), port: .init(integerLiteral: NWEndpoint.Port.IntegerLiteralType(port)))
        let parameters = NWParameters.udp
        
        // Force cellular interface
        parameters.requiredInterfaceType = .cellular
        
        let conn = NWConnection(to: nwEndpoint, using: parameters)
        self.connection = conn
        
        timer.schedule(deadline: DispatchTime.now(), repeating: DispatchTimeInterval.seconds(Opt.UDPSocketActiveCheckInterval), leeway: DispatchTimeInterval.seconds(Opt.UDPSocketActiveCheckInterval))
        timer.setEventHandler { [weak self] in
            self?.queueCall {
                self?.checkStatus()
            }
        }
        timer.resume()
        
        conn.stateUpdateHandler = { [weak self] state in
            guard let self = self else { return }
            self.queueCall {
                switch state {
                case .ready:
                    self.checkWrite()
                    self.readData()
                case .cancelled:
                    self.delegate?.didCancel(socket: self)
                case .failed(let error):
                    DDLogError("NWCellularUDPSocket failed with error: \(error)")
                    self.delegate?.didCancel(socket: self)
                    self.disconnect()
                default:
                    break
                }
            }
        }
        
        conn.start(queue: queue)
    }
    
    /**
     Send data to remote.
     
     - parameter data: The data to send.
     */
    public func write(data: Data) {
        queueCall {
            self.pendingWriteData.append(data)
            self.checkWrite()
        }
    }
    
    public func disconnect() {
        queueCall {
            guard !self.cancelled else { return }
            self.cancelled = true
            self.connection?.cancel()
            self.timer.cancel()
        }
    }
    
    private func readData() {
        guard !cancelled else { return }
        connection?.receiveMessage { [weak self] (data, context, isComplete, error) in
            guard let self = self else { return }
            self.queueCall {
                self.updateActivityTimer()
                
                if let error = error {
                    // Ignore transient POSIX errors for UDP
                    DDLogError("NWCellularUDPSocket receive error: \(error)")
                    // Do not disconnect immediately on UDP error since it's connectionless
                }
                
                if let data = data, !data.isEmpty {
                    self.delegate?.didReceive(data: data, from: self)
                }
                
                self.readData()
            }
        }
    }
    
    private func checkWrite() {
        updateActivityTimer()
        
        guard connection?.state == .ready else {
            return
        }
        
        guard !writing else {
            return
        }
        
        guard pendingWriteData.count > 0 else {
            return
        }
        
        writing = true
        let dataToSend = pendingWriteData.removeFirst()
        connection?.send(content: dataToSend, completion: .contentProcessed({ [weak self] error in
            guard let self = self else { return }
            self.queueCall {
                self.writing = false
                if let error = error {
                    DDLogError("NWCellularUDPSocket write error: \(error)")
                    self.disconnect()
                    return
                }
                self.checkWrite()
            }
        }))
    }
    
    private func updateActivityTimer() {
        lastActive = Date()
    }
    
    private func checkStatus() {
        if timeout > 0 && Date().timeIntervalSince(lastActive) > TimeInterval(timeout) {
            disconnect()
        }
    }
    
    private func queueCall(block: @escaping () -> Void) {
        queue.async {
            block()
        }
    }
    
    deinit {
        connection?.stateUpdateHandler = nil
        connection?.cancel()
    }
}
