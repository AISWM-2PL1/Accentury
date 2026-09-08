import XCTest
@testable import AccenturyCore

/// 발행본 가이드 곡선 전수 검사 (KAN-194). 안드로이드 `bridge/PublishedGuideF0Test.kt`의
/// 1:1 이식본 (7개).
///
/// 픽스처 한 문항(``GuideF0Fixture``)이 대표라면 이 파일은 표본이 아니라 전수다 — 정본 발행본
/// `gn-2026.09.1`의 음성 145문항을 하나씩 브리지 파싱과 곡선 계산에 태워, 실제로 사용자에게
/// 내려가는 데이터에서 곡선이 버려지는 문항(AC1)과 사용자 창이 폴백으로 주저앉는 문항(AC2)이
/// 0건임을 확인한다. 곡선 규칙 자체는 ``GuideCurveTests``·``UserCurveTests``가 덮으므로, 여기서
/// 보는 것은 "그 규칙이 발행본 형태를 견디는가" 하나다.
///
/// 입력은 문항 JSON을 그대로 쓰지 않고 **웹이 보내는 payload 문자열로 조립해** 브리지에
/// 넣는다. 앱이 실제로 받는 것은 정의가 아니라 payload라, 정의를 직접 읽어 검사하면
/// `parseVoiceItemStart`의 좁히기 단계를 건너뛰게 된다 — 곡선을 버리는 사고는 바로 그
/// 단계에서 일어난다. guideF0는 **SQL 원문 조각을 잘라 그대로 싣는다**. `JSONSerialization`으로
/// 다시 직렬화하면 숫자 표기가 우리 손을 타므로(`4.0`→`4`, `-0.0`→`-0`), 발행본이 실은 바로 그
/// 바이트가 파싱을 통과하는지를 보려면 원문이어야 한다.
///
/// 입력 파일은 백엔드 마이그레이션 SQL을 직접 읽는다. iOS 쪽에 정의 사본을 떠 두면 발행본이
/// 바뀔 때 사본만 낡아 초록불이 거짓말을 하므로, 정본 파일을 그때그때 읽는 편이 맞다.
///
/// **파일이 없으면 skip이 아니라 실패다.** 발행본이 이 검사의 정본이라, 못 읽었으면 검사가
/// 안 돌았다는 뜻이지 통과했다는 뜻이 아니다. 조용히 건너뛰면 마이그레이션 파일이 사라지거나
/// 이름이 바뀐 날 아무도 모르게 커버리지가 0이 된다. (같은 레포 파일을 읽어도 `FilePcmSourceTests`의
/// 가짜 마이크는 없으면 건너뛴다 — 그쪽은 디버그 자산이라 없을 수 있는 반면 발행본은 정본이다.)
final class PublishedGuideF0Tests: XCTestCase {

    /// 발행본 음성 문항 하나. 구조로 읽은 guideF0와, 원문 조각으로 조립한 브리지 payload를
    /// 함께 들고 있다 — 앞은 발행본이 무엇을 담았는지 보고, 뒤는 그것이 파싱을 통과하는지 본다
    private struct VoiceItem {
        let itemId: String
        let scriptKey: String
        let guideF0: [String: Any]
        /// SQL에서 잘라낸 guideF0 원문을 그대로 실은 브리지 payload
        let payload: String

        /// 실패 메시지에서 어느 문항인지 바로 보이게 하는 꼬리표
        var tag: String { "\(itemId)(\(scriptKey))" }

        var rawFrameIntervalMs: Int { (guideF0["frameIntervalMs"] as? NSNumber)?.intValue ?? -1 }
        var rawValueCount: Int { (guideF0["values"] as? [Any])?.count ?? -1 }
    }

    // MARK: - 전수 검사

    /// `음성 문항이 145개다`
    func testPublishedDefinitionHas145VoiceItems() {
        XCTAssertEqual(145, voiceItems().count)
    }

    /// `전 문항이 곡선을 버리지 않고 파싱된다 - 구제 경로를 타는 문항이 없다 (AC1)`
    func testEveryItemKeepsItsCurveThroughTheBridge() {
        // guideF0가 nil이면 파싱이 실패해 곡선을 떼어낸 구제 경로(decodeStart)를 탔다는 뜻이다.
        // 녹음은 그대로 진행되지만 가이드 레인이 비므로, 발행본에서는 0건이어야 한다.
        XCTAssertEqual([], violations { item in
            guard let start = parseVoiceItemStart(item.payload) else { return "payload 자체가 거부됐다" }
            guard let guide = start.guideF0 else { return "guideF0가 버려졌다 (구제 경로)" }
            if guide.frameIntervalMs != item.rawFrameIntervalMs {
                return "frameIntervalMs \(guide.frameIntervalMs) != 원문 \(item.rawFrameIntervalMs)"
            }
            if guide.values.count != item.rawValueCount {
                return "values \(guide.values.count)개 != 원문 \(item.rawValueCount)개"
            }
            return nil
        })
    }

    /// `전 문항에서 곡선이 그려진다 - 빈 곡선이 없다 (AC1)`
    func testEveryItemProducesCurvePoints() {
        XCTAssertEqual([], violations { item in
            guard let values = parseVoiceItemStart(item.payload)?.guideF0?.values else {
                return "guideF0가 없어 곡선을 그릴 수 없다"
            }
            if guideCurveDisplayPoints(values).isEmpty {
                return "값 \(values.count)개인데 그릴 점이 0개다"
            }
            return nil
        })
    }

    /// `전 문항의 사용자 창이 가이드 길이에서 나오고 폴백으로 떨어지지 않는다 (AC2)`
    func testEveryItemWindowComesFromTheGuideAndNeverFallsBack() {
        XCTAssertEqual([], violations { item in
            guard let guide = parseVoiceItemStart(item.payload)?.guideF0 else {
                return "guideF0가 없어 창을 가이드에서 뽑을 수 없다"
            }
            let actual = userCurveWindowMs(frameIntervalMs: guide.frameIntervalMs, valueCount: guide.values.count)
            let expected = Int64(
                (userCurveWindowScale * Double(guide.frameIntervalMs) * Double(guide.values.count - 1)).rounded()
            )
            if actual != expected { return "창 \(actual)ms != 가이드에서 나온 \(expected)ms" }
            if actual == Self.fallbackWindowMs { return "창이 폴백 \(Self.fallbackWindowMs)ms와 같다" }
            return nil
        })
    }

    /// `단위는 semitone이고 허용 밴드는 없다 - KAN-17 1안은 중앙선만 낸다`
    func testUnitIsSemitoneAndNoToleranceBandIsPublished() {
        XCTAssertEqual([], violations { item in
            let unit = item.guideF0["unit"] as? String
            if unit != "semitone" { return "unit이 \(unit ?? "없다") 다" }
            if item.guideF0["bandLow"] != nil || item.guideF0["bandHigh"] != nil {
                return "허용 밴드 키가 있다: \(item.guideF0.keys.sorted())"
            }
            return nil
        })
    }

    /// `값 개수는 20의 배수이고 160~320점이다 - 어절 20등분 격자`
    func testValueCountIsAMultipleOf20InTheExpectedRange() {
        XCTAssertEqual([], violations { item in
            let count = item.rawValueCount
            return (count % 20 == 0 && (160...320).contains(count)) ? nil : "값이 \(count)개다"
        })
    }

    /// `frameIntervalMs가 실수면 같은 문항이라도 곡선을 버린다 - 반올림 발행이 필요한 이유`
    func testFractionalFrameIntervalDropsTheCurveEvenForAPublishedItem() {
        // 대조군. `GuideF0.frameIntervalMs`가 Int라 실수 표기는 파싱이 안 되고, 구제 경로가
        // 곡선만 떼어낸 채 문항을 통과시킨다. 발행 파이프라인이 산출물의 실수를 반올림해
        // 내보내는 결정(2026-09-04)이 없었다면 145문항이 통째로 이 상태였을 것이다.
        // (장난감 값 `10.5`로 같은 경로를 보는 케이스는 `VoiceItemStartTests`에 있다.)
        guard let item = voiceItems().first(where: { $0.itemId == GuideF0Fixture.itemId }) else {
            return XCTFail("발행본에 \(GuideF0Fixture.itemId) 문항이 없다")
        }
        var broken = item.payload
        guard let range = broken.range(of: "\"frameIntervalMs\"\\s*:\\s*16", options: .regularExpression) else {
            return XCTFail("전제: \(item.tag)의 frameIntervalMs는 16이어야 한다")
        }
        broken.replaceSubrange(range, with: "\"frameIntervalMs\": 16.4")

        let start = parseVoiceItemStart(broken)
        XCTAssertEqual(GuideF0Fixture.itemId, start?.itemId, "문항 자체는 받아야 한다")
        XCTAssertNil(start?.guideF0, "실수 frameIntervalMs면 곡선은 버려진다")

        // 원본(정수)은 같은 payload에서 곡선을 지킨다 — 대조가 성립함을 함께 못박는다.
        XCTAssertNotNil(parseVoiceItemStart(item.payload)?.guideF0)
    }

    // MARK: - 발행본 읽기

    /// 가이드를 쓸 수 없을 때의 창 길이. 어느 문항도 여기로 떨어지면 안 된다
    private static let fallbackWindowMs = userCurveWindowMs(frameIntervalMs: nil, valueCount: nil)

    /// 정본 발행본이 담긴 마이그레이션. 레포 루트 기준 경로다
    private static let migrationRelativePath =
        "backend/src/main/resources/db/migration/V6__gn_2026_09_1_content.sql"

    /// 정의 JSON을 감싼 PostgreSQL 달러 인용 구분자
    private static let delimiter = "$definition$"

    /// 파일 읽기와 파싱은 한 번이면 된다 — 145문항 payload 조립을 테스트마다 다시 하지 않는다
    private static let loaded = Result { try loadPublishedVoiceItems() }

    /// 실패 메시지를 테스트별로 보이게 하려고 여기서 푼다 — 전역 초기화에서 터지면 이유가 묻힌다
    private func voiceItems(file: StaticString = #filePath, line: UInt = #line) -> [VoiceItem] {
        switch Self.loaded {
        case .success(let items):
            return items
        case .failure(let error):
            XCTFail("발행본을 읽지 못했다: \(error)", file: file, line: line)
            return []
        }
    }

    /// 조건을 어긴 문항만 추린다. 개수만 세면 "몇 개 깨졌다"까지만 나오지만, 어긴 문항을
    /// 모아 비교하면 실패 메시지에 itemId·scriptKey와 어긋난 값이 그대로 찍힌다.
    private func violations(
        file: StaticString = #filePath,
        line: UInt = #line,
        _ inspect: (VoiceItem) -> String?
    ) -> [String] {
        voiceItems(file: file, line: line).compactMap { item in
            inspect(item).map { "\(item.tag): \($0)" }
        }
    }

    private enum LoadFailure: Error, CustomStringConvertible {
        case migrationNotFound([String])
        case malformed(String)

        var description: String {
            switch self {
            case .migrationNotFound(let tried):
                return "발행본 마이그레이션을 찾지 못했다. 찾아본 경로:\n" + tried.joined(separator: "\n")
                    + "\n이 검사의 입력은 정본 발행본이라, 파일이 없으면 건너뛰는 게 아니라 실패다."
            case .malformed(let reason):
                return reason
            }
        }
    }

    /// 마이그레이션 파일을 찾는다. `swift test`의 작업 디렉터리는 어디서 돌리느냐에 따라 달라지므로
    /// 이 소스 파일 위치(`#filePath`)에서 레포 루트까지 거슬러 올라간다 — `FilePcmSourceTests`가
    /// 안드로이드 쪽 가짜 마이크 WAV를 찾는 방식과 같다.
    private static func findMigration() throws -> URL {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        var tried = [String]()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent(migrationRelativePath)
            tried.append(candidate.path)
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        throw LoadFailure.migrationNotFound(tried)
    }

    private static func loadPublishedVoiceItems() throws -> [VoiceItem] {
        let sql = try String(contentsOf: try findMigration(), encoding: .utf8)
        guard let open = sql.range(of: delimiter),
              let close = sql.range(of: delimiter, range: open.upperBound..<sql.endIndex) else {
            throw LoadFailure.malformed("\(delimiter) 구분자로 감싼 정의 JSON이 없다")
        }
        let definitionText = String(sql[open.upperBound..<close.lowerBound])

        guard let data = definitionText.data(using: .utf8),
              let definition = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let items = definition["items"] as? [[String: Any]] else {
            throw LoadFailure.malformed("정의 JSON을 객체로 읽지 못했다")
        }
        guard definition["testVersion"] as? String == "gn-2026.09.1" else {
            throw LoadFailure.malformed("정본 발행본이 아니다: testVersion=\(definition["testVersion"] ?? "없음")")
        }

        let voice = items.filter { $0["type"] as? String == "VOICE" }
        // 원문 조각을 앞에서부터 훑어 잘라 낸다. 문항은 배열 순서대로 파일에 있으므로 커서를
        // 되돌릴 일이 없다.
        var cursor = definitionText.startIndex
        return try voice.map { item in
            guard let itemId = item["itemId"] as? String,
                  let prompt = item["prompt"] as? String,
                  let guideF0 = item["guideF0"] as? [String: Any] else {
                throw LoadFailure.malformed("문항에 itemId·prompt·guideF0가 다 있지 않다: \(item)")
            }
            let raw = try rawGuideF0(in: definitionText, itemId: itemId, from: &cursor)
            let idLiteral = try jsonString(itemId)
            let promptLiteral = try jsonString(prompt)
            return VoiceItem(
                itemId: itemId,
                scriptKey: item["scriptKey"] as? String ?? "",
                guideF0: guideF0,
                // 웹이 보내는 payload 그대로다 — guideF0는 원문 조각을 손대지 않고 싣는다.
                payload: "{\"itemId\":\(idLiteral),\"prompt\":\(promptLiteral),"
                    + "\"itemNumber\":1,\"totalItems\":\(voice.count),\"maxDurationMs\":15000,"
                    + "\"guideF0\":\(raw)}"
            )
        }
    }

    /// `itemId` 다음에 오는 `guideF0` 객체의 원문을 중괄호 균형으로 잘라 낸다. 정의의 문자열
    /// 값에는 중괄호가 없어(`"semitone"`뿐이다) 깊이만 세도 경계가 정확히 잡힌다.
    private static func rawGuideF0(
        in text: String,
        itemId: String,
        from cursor: inout String.Index
    ) throws -> String {
        guard let idRange = text.range(of: "\"\(itemId)\"", range: cursor..<text.endIndex),
              let keyRange = text.range(of: "\"guideF0\"", range: idRange.upperBound..<text.endIndex),
              let start = text[keyRange.upperBound...].firstIndex(of: "{") else {
            throw LoadFailure.malformed("\(itemId)의 guideF0 원문을 찾지 못했다")
        }

        var depth = 0
        var index = start
        while index < text.endIndex {
            let character = text[index]
            if character == "{" { depth += 1 }
            if character == "}" {
                depth -= 1
                if depth == 0 {
                    let end = text.index(after: index)
                    cursor = end
                    return String(text[start..<end])
                }
            }
            index = text.index(after: index)
        }
        throw LoadFailure.malformed("\(itemId)의 guideF0 객체가 닫히지 않았다")
    }

    /// 문자열 하나를 JSON 리터럴로 만든다. 지문에 따옴표나 줄바꿈이 있어도 payload가 깨지지 않게
    /// 직접 이스케이프하지 않고 `JSONSerialization`에 맡긴다.
    private static func jsonString(_ value: String) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed])
        guard let literal = String(data: data, encoding: .utf8) else {
            throw LoadFailure.malformed("문자열을 JSON 리터럴로 만들지 못했다: \(value)")
        }
        return literal
    }
}
