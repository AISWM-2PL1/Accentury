# 앱 아이콘 원본 (KAN-178)

Android 런처(adaptive icon)·iOS `AppIcon`·스플래시·Play/App Store 아이콘에 쓰는 도상의 **원본**이 여기 있다.
화면·스토어에 실리는 파일은 여기 없다 — 도상이 확정되면 `build.py`(2단계)가 원본에서 파생본을 만든다.

## 현재 상태 — 후보 2개, 팀 결정 대기 (2026-09-02)

| 후보 | 도상 | 원본 | 비고 |
| --- | --- | --- | --- |
| `c2` | "ㅅㅌㄹ" 세 글자를 세로로 길게, 잉크 종이 조각 + 크림 스티커 림 + 오프셋 그림자 | `candidates/c2/source.png` | 흰 배경(생성 편차) — 빌드 단계에서 크림 `#F3ECD9`로 정규화 |
| `d3` | 한 문장의 피치 곡선(F0 contour) 한 획, 같은 종이 구성 | `candidates/d3/source.png` | 배경 크림 |

두 후보를 런처 크기(400/144/96/72/48px)로 나란히 놓은 비교 시트가 `candidates/compare.png`다.
결정은 노션 「FE 개발 중 진행 상황 정리」 §1에 올렸다. 결정되면 채택본을 `source.png`로 승격하고
탈락본은 `candidates/`에 그대로 둔다(이력).

시안 이력(폐기, 레포에 없음): A 말풍선 속 곡선 → B 호소인 얼굴 → C 가로 ㅅㅌㄹ → C3a 테두리 없음 /
C3b 얇은 테두리(원복) → D 큰 곡선(N처럼 읽힘) → D2 곡선 + 점선 가이드(점선 제거).

## 앱 표시 이름

**Accentury** (2026-09-02 확정). Android `app/src/main/res/values/strings.xml`의 `app_name`,
iOS `Info-Debug.plist`·`Info-Release.plist`의 `CFBundleDisplayName`, 스토어 등록 정보(KAN-174·KAN-175)의
앱 이름이 전부 이 값이어야 한다.

## 생성 방법 (출처 기록)

- **도구**: Codex CLI 0.145 내장 `image_gen`(gpt-image-2). API 키·MCP 없이 기본 모드. 캐릭터(`assets/characters/README.md`)와 같은 경로.
- **참고 이미지**: 외부 이미지 없음. `-i`로 첨부한 것은 직전 라운드의 자체 산출물뿐(자기 참조).
- **실행**:
  ```
  codex exec --skip-git-repo-check --sandbox workspace-write -C <출력 폴더> -i ref.png - < prompt.md
  ```
  프롬프트는 반드시 stdin(`-`)으로 넘긴다(`-i`가 가변 인자라 인자로 주면 멈춘다).
- **색**: 앱 토큰 고정 — 종이 `#F3ECD9`, 그림자 `#CFC5AA`, 잉크 `#1C1A17`(`docs/wiki/design-tokens.md`).
- **규격 요구(티켓)**: Android adaptive icon은 108dp 캔버스의 66dp 안전 영역 안에 도상이 들어가야 하고,
  iOS는 1024×1024 알파 없는 PNG 한 장이다. 그래서 원본은 도상이 캔버스 가운데 55~80%를 차지하는 정사각형으로 뽑고,
  여백·배경은 빌드 스크립트가 플랫폼별로 다시 잡는다.
