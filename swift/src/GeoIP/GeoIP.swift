import Foundation
import MMDB
open class GeoIP {
    public static let database = MMDB(Bundle.main.path(forResource: "GeoLite2-Country", ofType: "mmdb") ?? "")!
    public static func LookUp(_ ipAddress: String) -> MMDBCountry? {
        return GeoIP.database.lookup(ipAddress)
    }
}
