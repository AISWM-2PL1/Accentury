#if DEBUG
import Foundation
import WebKit

/// 웹 화면을 대신 눌러 주는 디버그 전용 구동기 (`-AutoFlowDrive 1`, KAN-108 §8).
///
/// 시뮬레이터에는 탭을 넣을 방법이 없다(`xcrun simctl`에 좌표 입력이 없다). 네이티브 화면은
/// 실행 인자마다 훅을 하나씩 뒀지만(`-AutoStartSmoke`·`-AutoGateSmoke`·`-AutoRecordingDrive`)
/// 그 방식이 웹에는 통하지 않는다 — 눌러야 하는 자리가 문항 수만큼 있고, 어느 화면이 언제 뜨는지는
/// 서버가 준 정의와 진행 상태가 정한다. 그래서 여기만 **폴링하는 구동기**다: 300ms마다 DOM을 보고
/// 지금이 어느 화면인지 판정한 뒤, 그 화면에 남은 조작이 있으면 한 번만 누른다.
///
/// ## 왜 판정이 JS 안에 있는가
///
/// `-AutoStartSmoke`가 폴링을 JS 안에 둔 것과 같은 이유다. 네이티브가 300ms마다
/// `evaluateJavaScript`를 왕복하면 왕복 사이에 화면이 바뀌었는지를 다시 따져야 하고, 그 판정이
/// 두 언어에 갈라진다. 여기서 네이티브가 하는 일은 스크립트를 심고(문서마다) 그 스크립트가 쌓아
/// 둔 줄을 걷어 로그로 옮기는 것뿐이다.
///
/// ## 무엇을 누르지 않는가
///
/// 음성 문항은 손대지 않는다 — 그 화면의 조작부는 네이티브 녹음 오버레이가 덮고 있고, 누르는 것은
/// `-AutoFlowDrive`의 네이티브 절반(`TestFlowView`)이다. 결과 화면에서도 아무것도 누르지 않는다:
/// 스모크의 끝이 그 화면이고, [친구에게 공유하기]는 시트를 띄워 캡처를 가린다.
///
/// 릴리스 바이너리에는 이 파일이 통째로 없다.
@MainActor
final class WebAutoDriver {

    /// 구동기가 켜져 있는가. 실행 인자 하나로만 켜진다.
    static var isEnabled: Bool { UserDefaults.standard.bool(forKey: "AutoFlowDrive") }

    private weak var webView: WKWebView?

    /// 로그를 걷는 반복 Task. 문서가 바뀌어도 하나만 돈다 — 스크립트는 문서마다 다시 심지만
    /// (전역이 새 문서에서 초기화된다) 걷는 쪽은 WebView 수명에 매인다.
    ///
    /// 멈추는 자리를 따로 두지 않는다. WebView 참조가 weak이라, WebView가 해제되면 다음 걷기에서
    /// 루프가 스스로 끝난다 — 스모크용 코드가 화면 해제 경로에 손을 뻗을 이유가 없다.
    private var drainTask: Task<Void, Never>?

    /// 인트로의 [시작하기]까지 이 구동기가 누를 것인가.
    ///
    /// `-AutoStartSmoke`가 이미 그 버튼을 누르는 훅이라, 둘 다 켜면 같은 버튼을 두 번 누른다.
    /// 두 번 눌려도 결과는 같지만(네이티브가 `startRequested = true`를 다시 적을 뿐) 로그에
    /// 같은 줄이 두 벌 남아 무엇이 흐름을 밀었는지 읽기 어려워진다.
    private let drivesIntro: Bool

    init(webView: WKWebView, drivesIntro: Bool) {
        self.webView = webView
        self.drivesIntro = drivesIntro
    }

    /// 지금 문서에 구동 스크립트를 심는다. 문서가 바뀔 때마다(`didFinish`) 다시 부른다 —
    /// 전역이 새 문서에서 사라지므로 스크립트도 함께 사라진다.
    func installIntoCurrentDocument() {
        guard let webView else { return }
        webView.evaluateJavaScript(Self.driverJs(drivesIntro: drivesIntro)) { result, error in
            if let error {
                smokeLog("FLOW: driver install failed — \(error.localizedDescription)")
                return
            }
            // "installed"면 방금 심었고 "already"면 같은 문서에 이미 있다 (리로드 없는 didFinish).
            guard let state = result as? String, state == "installed" else { return }
            smokeLog("FLOW: driver installed")
        }
        startDrainingIfNeeded()
    }

    /// 스크립트가 쌓아 둔 줄을 걷어 스모크 로그로 옮긴다.
    ///
    /// 걷는 쪽이 400ms인 것은 스크립트의 폴링(300ms)보다 느긋해서다 — 더 촘촘히 걷어도 새 줄이
    /// 없는 왕복만 는다. 줄은 걷을 때 스크립트 쪽에서 지워지므로 같은 줄을 두 번 찍지 않는다.
    private func startDrainingIfNeeded() {
        guard drainTask == nil else { return }
        drainTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 400_000_000)
                guard let self, let webView = self.webView else { return }
                let lines: String? = await withCheckedContinuation { continuation in
                    webView.evaluateJavaScript(Self.drainJs) { result, _ in
                        continuation.resume(returning: result as? String)
                    }
                }
                guard let lines, !lines.isEmpty else { continue }
                for line in lines.split(separator: "\n") {
                    smokeLog("FLOW: \(line)")
                }
            }
        }
    }

    deinit {
        drainTask?.cancel()
    }

    // MARK: - 주입 스크립트

    /// 쌓인 줄을 한 번에 걷어 오고 비운다.
    private static let drainJs = """
    (function(){
      var log = window.__accenturyDriveLog || [];
      window.__accenturyDriveLog = [];
      return log.join("\\n");
    })()
    """

    /// 화면 판정과 조작을 함께 든 구동 스크립트.
    ///
    /// 화면을 **클래스 이름으로** 가른다. 문구로 가르면 카피 한 줄 바뀔 때마다 스모크가 조용히
    /// 멈추는데, 여기 쓰는 클래스들은 전부 레이아웃이 의존하는 이름이라 그렇게 조용히 바뀌지 않는다
    /// (`web/src/tokens.css`). 다만 누르는 **버튼**은 문구로 찾는다 — 버튼에는 안정된 클래스가
    /// 따로 없고(`.btn.btn--primary`는 화면마다 여럿이다) 눌러야 하는 것이 무엇인지 사람이 읽는
    /// 이름이 곧 그 문구이기 때문이다.
    private static func driverJs(drivesIntro: Bool) -> String {
        """
        (function(){
          if (window.__accenturyDrive) return "already";
          window.__accenturyDrive = true;
          window.__accenturyDriveLog = [];
          var DRIVES_INTRO = \(drivesIntro ? "true" : "false");

          function log(line){ window.__accenturyDriveLog.push(line); }
          function textOf(el){ return (el.textContent || "").replace(/\\s+/g, " ").trim(); }

          /* 문구로 버튼 찾기. 비활성 버튼은 없는 셈 친다 — 어휘 문항의 [다음]은 선택 전에
             disabled라, 거르지 않으면 "눌렀다"고 로그만 남고 아무 일도 일어나지 않는다. */
          function button(label){
            var all = Array.prototype.slice.call(document.querySelectorAll("button"));
            for (var i = 0; i < all.length; i++) {
              if (!all[i].disabled && textOf(all[i]).indexOf(label) >= 0) return all[i];
            }
            return null;
          }

          function has(selector){ return document.querySelector(selector) !== null; }

          /* 지금 어느 화면인가. 순서가 규칙이다 — 앞 분기가 가져간 화면을 뒤가 다시 적지 않는다. */
          function screenName(){
            if (has(".analysis-progress")) return "waiting";
            if (has(".result-tier__rank") || has(".result-scores")) return "result";
            if (has(".choice-list")) return "vocab";
            if (has(".illustration--intro")) return "intro";
            if (has(".illustration--result")) return "result";
            /* 업데이트 안내(App.tsx)와 로드 실패는 화면 전용 클래스가 없어 문구로 가른다.
               스큐 검증(-BridgeVersionOverride)이 보는 화면이 이것이다. */
            var h1 = document.querySelector("h1");
            if (h1 && textOf(h1).indexOf("업데이트가 필요") >= 0) return "update-required";
            if (has(".item-screen__footer") || has(".prompt-card")) return "voice";
            if (has(".status-block")) return "status";
            return "unknown";
          }

          /* 지금 화면을 사람이 읽을 한 줄로. 문항은 프롬프트까지 붙여 같은 종류의 다른 문항을
             구분한다 — 그래야 "눌렀는가"의 열쇠가 화면 이름만으로 뭉개지지 않는다. */
          function screenKey(name){
            if (name === "vocab") {
              var p = document.querySelector("#vocab-prompt");
              return "vocab:" + (p ? textOf(p) : "?");
            }
            if (name === "voice") {
              var badge = document.querySelector(".prompt-card__badge");
              return "voice:" + (badge ? textOf(badge) : "?");
            }
            if (name === "status") {
              var s = document.querySelector(".status-block");
              return "status:" + (s ? textOf(s).slice(0, 40) : "?");
            }
            return name;
          }

          var lastKey = null;
          /* 이미 누른 자리. 키가 화면+문항이라 같은 문항에서 두 번 누르지 않는다. */
          var acted = {};

          function tick(){
            var name = screenName();
            var key = screenKey(name);
            if (key !== lastKey) {
              lastKey = key;
              log(key + " → 화면 진입");
            }

            if (name === "intro") {
              if (!DRIVES_INTRO || acted[key]) return;
              var start = button("시작하기");
              if (start) { acted[key] = true; log(key + " → [시작하기] 클릭"); start.click(); }
              return;
            }

            if (name === "vocab") {
              if (acted[key]) return;
              /* 두 걸음이다: 먼저 선택지를 고르고, 다음 tick에 [다음]이 활성화되면 누른다.
                 리액트가 상태를 반영해 버튼을 풀 때까지 한 프레임이 필요해서, 한 tick에
                 둘 다 하면 [다음]이 아직 disabled다. */
              var radio = document.querySelector(".choice__radio:not(:disabled)");
              if (radio && !radio.checked) { log(key + " → 첫 선택지 선택"); radio.click(); return; }
              var next = button("다음") || button("다시 시도");
              if (next) { acted[key] = true; log(key + " → [" + textOf(next) + "] 클릭"); next.click(); }
              return;
            }

            /* 음성 문항·대기·결과·업데이트 안내는 보기만 한다 (헤더 주석). */
          }

          window.__accenturyDriveTimer = setInterval(function(){
            try { tick(); } catch (e) { log("구동 실패 — " + e); }
          }, 300);
          return "installed";
        })()
        """
    }
}
#endif
