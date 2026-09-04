// swift-tools-version: 5.9
import PackageDescription

/// 앱 타깃에서 떼어낸 순수 Swift 계층. UIKit·AVFoundation·WebKit을 쓰지 않는다 —
/// macOS를 플랫폼에 넣어 두면 시뮬레이터 없이 `swift test` 한 줄로 CLI에서 검증이 끝난다
/// (안드로이드의 `./gradlew :app:testDebugUnitTest`가 JVM에서 도는 것과 같은 자리).
let package = Package(
    name: "AccenturyCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "AccenturyCore", targets: ["AccenturyCore"]),
    ],
    targets: [
        .target(name: "AccenturyCore"),
        .testTarget(name: "AccenturyCoreTests", dependencies: ["AccenturyCore"]),
    ]
)
