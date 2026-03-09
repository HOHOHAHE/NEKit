// swift-tools-version:5.5
import PackageDescription

let package = Package(
    name: "NEKit",
    platforms: [
        .iOS(.v12), .macOS(.v10_14)
    ],
    products: [
        .library(name: "NEKit", targets: ["NEKit"]),
        .executable(name: "NEKitLocalRun", targets: ["NEKitLocalRun"])
    ],
    dependencies: [
        .package(url: "https://github.com/robbiehanson/CocoaAsyncSocket.git", from: "7.5.1"),
        .package(url: "https://github.com/CocoaLumberjack/CocoaLumberjack.git", from: "3.0.0"),
        .package(path: "Vendor/MMDB-Swift"),
        .package(url: "https://github.com/jedisct1/swift-sodium.git", branch: "master"),
        .package(url: "https://github.com/apple/swift-numerics", from: "1.0.0"),
        .package(url: "https://github.com/krzyzanowskim/CryptoSwift.git", .upToNextMinor(from: "1.8.0")),
        .package(url: "https://github.com/behrang/YamlSwift.git", from: "3.4.4"),
        .package(url: "https://github.com/zhuhaow/Resolver.git", branch: "master"),
        .package(url: "https://github.com/Quick/Quick.git", from: "2.2.0"),
        .package(url: "https://github.com/Quick/Nimble.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "NEKit",
            dependencies: [
                "CocoaAsyncSocket",
                "CocoaLumberjack",
                .product(name: "CocoaLumberjackSwift", package: "CocoaLumberjack"),
                .product(name: "MMDB", package: "MMDB-Swift"),
                .product(name: "Sodium", package: "swift-sodium"),
                .product(name: "Yaml", package: "YamlSwift"),
                .product(name: "Numerics", package: "swift-numerics"),
                .product(name: "CryptoSwift", package: "CryptoSwift"),
                "Resolver"
            ],
            path: "src"
        ),
        .executableTarget(
            name: "NEKitLocalRun",
            dependencies: ["NEKit"],
            path: "Sources/NEKitLocalRun"
        ),
        .testTarget(
            name: "NEKitTests",
            dependencies: [
                "NEKit",
                "Quick",
                "Nimble"
            ],
            path: "test" // the test directory from Carthage if applicable
        )
    ]
)
