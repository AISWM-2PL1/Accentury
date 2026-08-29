import AccenturyCore
import SwiftUI

/// 녹음 화면 (KAN-100·KAN-102·KAN-146·KAN-147). 안드로이드 `recording/RecordingScreen.kt`의
/// 이식본이고, 문구와 구조를 1:1로 옮긴다 — 한 테스트 안에서 이 화면과 웹 녹음 패널이 번갈아
/// 나오므로 문구 한 줄이 달라도 사용자에게는 같은 문항이 다르게 동작하는 것으로 보인다.
///
/// **재생은 없다** (FR-AD-09). 검토 화면의 선택지는 [재녹음]과 [다음] 둘뿐이고, [다음]은 품질
/// 판정이 통과일 때만 선다 (FR-AD-08).
///
/// 곡선 좌표를 고르는 자리이기도 하다 (KAN-105) — 어떤 프레임을, 얼마짜리 창으로 볼지는
/// ``curveCard``가 한 번에 정하고 레인은 픽셀 변환만 한다.
struct RecordingScreen: View {

    let questionText: String
    let questionIndex: Int
    let totalQuestions: Int

    /// 제출한 시도의 결과가 웹에 닿기를 기다리는 중인가 (KAN-146). 화면을 갈아끼우지 않고 이
    /// 화면 안에서 아래쪽만 바꾼다 — 문항 문구도 곡선도 제자리에 남아, [다음]을 누른 뒤 다음
    /// 문항이 뜰 때까지가 한 화면의 상태 변화로 읽힌다.
    var submitting: Bool = false

    /// 서버가 이 녹음을 거절해서 화면이 스스로 다시 열린 경우인가 (KAN-147). 사용자가 [다음]을
    /// 누르고 웹으로 돌아간 뒤에 벌어지는 일이라, 이유를 한 줄 적어두지 않으면 녹음 화면이 까닭
    /// 없이 되돌아온 것으로 보인다.
    var afterUploadFailure: Bool = false

    /// 그 거절에서 서버가 준 문구 (KAN-147). 녹음이 왜 거절됐는지(너무 길다, 너무 작다)는
    /// 서버만 아는 것이라 그대로 보여준다 — 앱이 지어낸 일반 문구로 덮으면 사용자가 같은 실패를
    /// 반복한다. nil이면 기본 안내를 쓴다.
    var failureMessage: String?

    /// 상단 레인의 정적 가이드 곡선 (KAN-102). nil은 안 실어 보낸 구버전 웹 — 레인만 비운다.
    var guideF0: GuideF0?

    /// 사용자 곡선 y축의 중심 음높이 (KAN-105). 목소리 점검 화면이 미리 잰 값을 넘긴다.
    /// nil이면 이 녹음의 첫 유성 프레임들로 직접 잡고(``AccenturyCore/userCurveCenterHz(_:)``),
    /// 그것도 안 되면 곡선을 그리지 않는다 — 축이 정해지기 전에 임시 축으로 그려 두면 축이
    /// 잠기는 순간 곡선 전체가 한 번 점프한다.
    var centerHz: Float?

    @ObservedObject var model: RecordingModel

    /// 정적인 가이드 좌표를 문항당 한 번만 계산한다 — 안드로이드 `remember(guideF0)`의 자리다
    /// (``GuideCurveCache``). 참조 타입을 `@State`로 들고 있어야 뷰 값이 다시 만들어져도
    /// 같은 인스턴스가 남는다.
    @State private var guideCurve = GuideCurveCache()

    /// quality는 검토 상태에만 있고 화면이 넘어가는 즉시 되감기므로 호출자가 나중에 되물을 수
    /// 없다. 브리지 계약(KAN-89)이 qualityStatus를 요구해서 여기서 함께 넘긴다.
    let onNext: (_ attemptId: String, _ durationMs: Int64, _ quality: QualityStatus) -> Void

    var body: some View {
        /*
         * 화면 틀 (아트보드 ②). 위 64 · 좌우 24 · 아래 32다 — 위만 8의 배수 밖에 서는 이유는
         * 배치이기 때문이고(정본 §4), 웹 4화면의 `--screen-padding-top`과 같은 값이라 문항이 두
         * 런타임을 오가도 첫 요소가 같은 높이에서 시작한다.
         */
        VStack(spacing: 0) {
            /*
             * 본문은 스크롤하고 하단(타이머·녹음 버튼)은 고정이다. 글꼴을 크게 키운 기기에서
             * 대사 카드와 곡선이 자라면 녹음 버튼이 화면 밖으로 밀리는데, 이 화면에서 버튼이
             * 안 보이는 것은 곧 녹음을 못 하는 것이다 — 밀려야 할 쪽은 본문이다.
             */
            ScrollView {
                VStack(spacing: Papercut.space6) {
                    /*
                     * 웹 진행바와 같은 컴포넌트, 같은 값, 같은 폭이다. `note`가 "음성"인 것도
                     * 같은 이유다 — 웹 캡션이 "3 / 10 · 음성"이라, 여기서만 종류를 빼면 같은
                     * 자리의 같은 줄이 화면을 넘어갈 때마다 길어졌다 짧아진다.
                     */
                    ProgressIndicator(current: questionIndex, total: totalQuestions, note: "음성")

                    /*
                     * 대사 카드. 배지·이모지·부연을 걷고 캡션 한 줄 + 대사만 남겼다 (아트보드 ②).
                     * "평소 말하듯 자연스럽게 읽어주세요"가 사라진 것은 문구를 줄이려는 게 아니라
                     * 자리를 옮긴 것이다 — 카드는 읽을 문장을 내밀고, 어떻게 하라는 말은 버튼 밑
                     * 캡션이 한다.
                     */
                    PromptCard(
                        caption: promptCaption(questionIndex: questionIndex, totalQuestions: totalQuestions),
                        prompt: questionText
                    )

                    curveCard
                }
                .frame(maxWidth: .infinity)
            }
            .scrollBounceIfAvailable()

            // 하단 고정. 아트보드의 footer는 본문과 16만큼 떨어진다.
            VStack(spacing: 0) {
                footer
            }
            .frame(maxWidth: .infinity)
            .padding(.top, Papercut.space4)
        }
        .padding(.top, Papercut.screenPaddingTop)
        .padding(.horizontal, Papercut.space6)
        .padding(.bottom, Papercut.space8)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Papercut.cream.ignoresSafeArea())
    }

    /// 곡선 두 레인을 감싸는 상자 (시안). 레인을 상자에 넣는 이유는 곡선이 "화면에 그려진 선"이
    /// 아니라 "지금 보고 있는 자료"로 읽히게 하기 위해서다 — 대사 카드와 나란히 놓이면 두
    /// 덩어리가 화면의 위아래를 나눈다.
    ///
    /// 상자 위에 "억양 곡선" 제목을 달지 않는다 (KAN-161 2단계) — 레인 라벨이 이미 "가이드"와
    /// "내 억양"이라, 제목은 같은 말을 한 번 더 하면서 세로 공간만 먹는다.
    private var curveCard: some View {
        /*
         * 좌표 계산 한 벌. 안드로이드가 `remember`로 묶던 정적인 것 둘은 여기서도 다시 계산되지
         * 않는다 — 가이드 좌표는 ``GuideCurveCache``가 payload 기준으로, 좌표→`Path` 변환은
         * `CurveLaneView`의 형상 캐시가 좌표 기준으로 들고 있다. 청크마다 새로 도는 것은
         * 사용자 곡선 하나뿐이고, 그건 어차피 매번 달라진다.
         *
         * 창 길이에는 unit 가드를 걸지 않는다. 가이드 쪽 가드(``GuideCurveCache/points(for:)``)는
         * "값을 어떻게 읽을 것인가"의 문제라 단위를 모르면 그릴 수 없지만, 길이는 간격 × 구간
         * 수라서 단위와 무관하게 맞는다. 그래서 가이드를 못 그리는 경우에도 창은 제 값을 잡는다.
         */
        let liveWindowMs = userCurveWindowMs(
            frameIntervalMs: guideF0?.frameIntervalMs,
            valueCount: guideF0?.values.count
        )
        let frames = model.curvePitchFrames
        // 이 창은 사용자 레인만 쓴다. 가이드는 사용자 창과 무관하게 항상 자기 길이로 레인 폭
        // 전체를 쓴다 (2026-08-25 결정 — `docs/wiki/pitch-curve.md` §4 "가이드 레인은 별도
        // 시간축이다"). 두 레인은 같은 시각을 맞춰 보는 도구가 아니라 모양을 견주는 도구다.
        let windowMs = model.isReviewing
            ? reviewWindowMs(frames, liveWindowMs: liveWindowMs)
            : liveWindowMs
        let guidePoints = guideCurve.points(for: guideF0)
        let userSegments = userCurveDisplayPoints(frames, windowMs: windowMs, centerHz: centerHz)

        return CurveLaneGroup {
            // 가이드는 무성 구간을 보간으로 이어 둔 하나짜리 폴리라인이라 선분 하나로 감싼다.
            CurveLaneView(label: "가이드", variant: .guide, segments: [guidePoints])
            CurveLaneView(
                label: "내 억양",
                variant: .user,
                segments: userSegments,
                topDivider: true,
                renderedFrameCount: frames.count
            )
        }
    }

    // MARK: - 하단

    @ViewBuilder
    private var footer: some View {
        if submitting {
            /*
             * 결과를 기다리는 동안의 하단. 버튼 자리를 문구 하나로 바꿔 "눌린 건 알아들었고 지금
             * 처리 중"만 알린다 — 진행률이나 취소를 주지 않는 이유는 이 구간이 보통 1초 안쪽이고
             * (상한도 호출자가 건다) 여기서 되돌릴 수 있는 것이 없기 때문이다.
             */
            Text("제출 중…")
                .papercutType(.body)
                .foregroundColor(Papercut.ink)
        } else {
            switch model.uiState {
            case .idle:
                idleControls

            case .recording(let recording):
                recordingControls(recording)

            case .review(let review):
                reviewControls(review)

            case .failed(let reason):
                StatusBlock(tone: .error, message: "녹음에 실패했어요", detail: reason) {
                    AccenturyButton(text: "다시 시도") { model.retry() }
                }
            }
        }
    }

    /// 대기. 누를 것 하나와 그 아래 캡션 한 줄이다 (아트보드 ②).
    private var idleControls: some View {
        VStack(spacing: Papercut.space2) {
            RecordButton(accessibilityLabel: "녹음 시작") { model.start() }
            caption(
                afterUploadFailure
                    ? (failureMessage ?? "업로드에 실패해서 다시 녹음이 필요해요")
                    : "버튼을 눌러 녹음"
            )
        }
    }

    /// 녹음 중 (아트보드 ②·②b). 위에서부터 타이머 → 버튼 → 캡션이고, 마지막 2초에는 타이머
    /// 자리가 잉크 캡슐로, 캡션이 자동 종료 안내로 바뀐다.
    ///
    /// 같은 숫자를 다르게 그리는 것이 아니라 **다른 것을 말한다** — 위 표기는 "얼마나 읽었나",
    /// 캡슐은 "곧 끊긴다"다. 그래서 문장을 맺어야 하는 순간에만 나타나고, 나타났다는 사실 자체가
    /// 신호가 된다. 팔레트에 빨강이 없어 위급함을 색으로 말할 수 없는데(정본 §7), 애초에 색보다
    /// 문구와 등장이 강하다.
    private func recordingControls(_ recording: RecordingUiState.Recording) -> some View {
        let warning = recording.countdownActive
        return VStack(spacing: Papercut.space2) {
            /*
             * 타이머와 캡슐이 같은 자리를 나눠 쓴다. 높이를 캡슐 크기로 고정해 두는 이유는 둘의
             * 높이가 달라서다(글자 한 줄 vs 32 알약) — 자리를 안 잡아 두면 경고가 뜨는 순간
             * 아래 녹음 버튼이 내려앉는다. 사용자는 그때 버튼을 누르려던 참이다.
             */
            ZStack {
                if warning {
                    countdownCapsule(recording.remainingSeconds)
                } else {
                    elapsedTimer(recording.elapsedLabel)
                }
            }
            .frame(height: countdownCapsuleHeight)
            .animation(.easeOut(duration: Papercut.Motion.base), value: warning)

            RecordButton(accessibilityLabel: "녹음 정지", recording: true) { model.stop() }

            // 자동 종료를 미리 알린다 — 갑자기 멈추면 사용자는 자기가 뭘 잘못 눌렀다고 생각한다.
            caption(warning ? "10초가 되면 자동으로 멈춰요" : "녹음 중 · 탭해서 멈추기")
        }
    }

    /// `00:04 / 10초`. 뒤쪽 상한만 흐린 잉크다 — 앞의 두 자리가 초마다 바뀌는 값이고 뒤는
    /// 고정이라, 같은 무게로 적으면 어느 쪽이 지금인지 한 번에 안 갈린다.
    private func elapsedTimer(_ label: String) -> some View {
        (
            Text(label).foregroundColor(Papercut.ink)
                + Text(" / \(RecordingEngine.maxDurationMs / 1000)초").foregroundColor(Papercut.muted)
        )
        .papercutType(.timer)
    }

    /// 8초 경고 캡슐 (아트보드 ②b). 잉크로 채운 알약이라 화면에서 주 버튼 다음으로 눈에 띈다.
    ///
    /// 남은 시간이 매초 바뀌는 값이라 스크린 리더에 끼어들지 않는다 — 끼어드는 쪽으로 두면
    /// 사용자가 지금 소리 내어 읽고 있는 대사를 가로챈다. 안드로이드가 `LiveRegionMode.Polite`를
    /// 고른 자리이고, iOS에서는 그에 해당하는 것이 "알림을 쏘지 않고 라벨만 최신으로 둔다"다.
    private func countdownCapsule(_ seconds: Int) -> some View {
        Text("\(seconds)초 남음")
            .papercutType(.timer)
            .foregroundColor(Papercut.cream)
            .padding(.horizontal, 14)
            .frame(height: countdownCapsuleHeight)
            .background(Capsule().fill(Papercut.ink))
    }

    /// 정지 뒤의 확인. 판정 한 줄, 길이 한 줄, 그리고 갈림길 둘.
    private func reviewControls(_ review: RecordingUiState.Review) -> some View {
        VStack(spacing: Papercut.space2) {
            if review.autoStopped {
                caption("10초가 지나 자동으로 종료됐어요")
            }
            Text(Self.qualityMessage(review.quality))
                .papercutType(.body)
                .foregroundColor(Papercut.ink)
                .multilineTextAlignment(.center)
            caption(String(format: "녹음 길이 %.1f초", Double(review.durationMs) / 1000.0))

            Spacer().frame(height: Papercut.space2)

            /*
             * 둘이 같은 폭을 갖는다 (웹 `.record-actions`와 같은 규칙). 무게는 이미 변형이
             * 가르므로(보조는 그림자 없는 크림, 주는 잉크 면) 폭까지 다르면 보조 동작이 눌리지
             * 않을 만큼 작아진다.
             */
            HStack(spacing: Papercut.space3) {
                AccenturyButton(text: "재녹음", variant: .secondary, fillsWidth: true) { model.retry() }
                AccenturyButton(
                    text: "다음",
                    enabled: review.canProceed,
                    fillsWidth: true,
                    /*
                     * 되감기(reset)를 여기서 부르지 않는다 (KAN-146). [다음] 뒤에도 이 화면은
                     * 결과가 나갈 때까지 제출 중 상태로 남으므로, 이 자리에서 되감으면 방금 그린
                     * '내 억양' 곡선이 그 구간에서 사라진다. 되감기는 화면이 걷힌 뒤 호출자
                     * (`TestFlowView`)가 한다. onNext 안의 consumeRecording이 PCM을 이미
                     * 가져가므로(FR-DP-02) 되감기가 늦어져도 음성 바이트가 남지는 않는다.
                     */
                    action: { onNext(review.attemptId, review.durationMs, review.quality) }
                )
            }

            // 품질이 통과가 아니면 [다음]이 서지 않는다 (FR-AD-08). 버튼이 왜 안 눌리는지
            // 적어 두지 않으면 사용자는 앱이 멈춘 것으로 읽는다.
            if !review.canProceed {
                caption("다시 녹음해야 다음으로 넘어갈 수 있어요")
            }
        }
    }

    /// 하단 캡션 한 줄. 13 흐린 잉크다 (정본 §3 `caption`).
    private func caption(_ text: String) -> some View {
        Text(text)
            .papercutType(.caption)
            .foregroundColor(Papercut.muted)
            .multilineTextAlignment(.center)
    }

    /// 경고 캡슐 높이 (아트보드 ②b: 높이 32 · 가로 패딩 14).
    private let countdownCapsuleHeight: CGFloat = 32

    private static func qualityMessage(_ quality: QualityStatus) -> String {
        switch quality {
        case .normal: return "녹음 상태가 좋아요"
        case .tooShort: return "발화가 너무 짧아요 — 조금 더 길게 말해주세요"
        case .tooQuiet: return "소리가 너무 작아요 — 조금 더 크게 말해주세요"
        case .clipped: return "소리가 튀었어요 — 마이크에서 조금 떨어져 주세요"
        }
    }
}

private extension View {
    /// 본문이 짧을 때 스크롤 뷰가 통통 튀지 않게 한다 — 안드로이드 `verticalScroll`에는 없는
    /// 동작이라 그쪽과 같은 느낌을 만든다. iOS 16.4 미만에는 이 API가 없어 그냥 지나간다.
    @ViewBuilder
    func scrollBounceIfAvailable() -> some View {
        if #available(iOS 16.4, *) {
            self.scrollBounceBehavior(.basedOnSize)
        } else {
            self
        }
    }
}
