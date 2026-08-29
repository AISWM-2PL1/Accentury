import Foundation

/// 웹이 문항 하나에 대해 돌려받는 전부 (KAN-89 계약).
///
/// 원본 PCM은 이 타입에 실을 수 없다 — `[Int16]`/`Data` 계열 필드를 두지 않는 것이 계약의
/// 핵심이다. 음성은 업로드 경로로만 서버에 가고 JS 브리지는 경유하지 않는다 (FR-DP-02).
/// 필드를 늘릴 일이 생기면 이 5개가 계약이라는 점을 먼저 확인할 것.
public struct ItemResult: Codable, Equatable, Sendable {
    public let itemId: String
    public let attemptId: String
    public let analysisJobId: String
    public let durationMs: Int64
    public let qualityStatus: QualityStatus

    public init(
        itemId: String,
        attemptId: String,
        analysisJobId: String,
        durationMs: Int64,
        qualityStatus: QualityStatus
    ) {
        self.itemId = itemId
        self.attemptId = attemptId
        self.analysisJobId = analysisJobId
        self.durationMs = durationMs
        self.qualityStatus = qualityStatus
    }

    /// 브리지가 JS로 넘길 payload. enum은 안드로이드와 같은 이름 문자열("NORMAL")로 나간다.
    ///
    /// 키 순서는 계약이 아니다 — 웹은 `JSON.parse` 결과의 필드를 이름으로 읽는다. 다만 같은
    /// 입력이 늘 같은 문자열을 내도록 정렬 옵션을 켠다(주입 JS 회귀를 눈으로 비교할 수 있다).
    public func toJson() -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        // 5필드 전부 인코딩 가능한 값이라 실패할 수 없다.
        guard let data = try? encoder.encode(self), let json = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return json
    }
}

/// 조립에 필요한 녹음 쪽 메타. 여기에도 PCM은 담지 않는다 (FR-DP-02).
/// 업로드가 끝날 때까지 하네스가 attemptId별로 들고 있는 값이다.
public struct ItemAttempt: Equatable, Sendable {
    public let itemId: String
    public let attemptId: String
    public let durationMs: Int64
    public let quality: QualityStatus

    public init(itemId: String, attemptId: String, durationMs: Int64, quality: QualityStatus) {
        self.itemId = itemId
        self.attemptId = attemptId
        self.durationMs = durationMs
        self.quality = quality
    }
}

/// 해당 시도의 업로드가 ``UploadState/done(analysisJobId:)``일 때만 ``ItemResult``를 만든다.
/// 진행 중·실패·이미 폐기된(맵에 없는) 키는 아직 웹에 줄 게 없다는 뜻이라 nil이다.
public func assembleItemResult(meta: ItemAttempt, uploads: [String: UploadState]) -> ItemResult? {
    guard case .done(let analysisJobId)? = uploads[meta.attemptId] else { return nil }
    return ItemResult(
        itemId: meta.itemId,
        attemptId: meta.attemptId,
        analysisJobId: analysisJobId,
        durationMs: meta.durationMs,
        qualityStatus: meta.quality
    )
}
