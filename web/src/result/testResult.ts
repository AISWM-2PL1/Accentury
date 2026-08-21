/**
 * 최종 결과 응답(KAN-25, API 명세서 §3.7) 미러 타입.
 *
 * 정본은 백엔드의 `ResultResponse.java`다 — 이 파일은 그 응답을 웹에서 읽기 위한 사본이며,
 * 계약이 바뀌면 정본을 따라 여기를 고친다 (`testDefinition.ts`와 같은 규칙).
 *
 * **점수와 등급은 전부 서버 값이다.** 종합 점수 `(억양 × 2 + 단어) / 3`은 서버가 이미 계산해
 * 내려주고(KAN-21), 클라이언트는 재계산하지 않는다 (KAN-29 AC 1항). 그래서 이 타입에는
 * 가중치도 등급 경계값도 없다 — 여기 두는 순간 서버 정책(`scoreVersion`)과 갈라질 수 있는
 * 두 번째 정본이 생긴다.
 *
 * 발음·리듬 점수와 백분위는 MVP 범위 제외라(2026-07-22 확정) 응답에도 이 타입에도 없다.
 */

/** 각 0~100 정수. 셋 다 서버 산출값이다 (§4.3) */
export interface ResultScores {
  /** 억양 점수 — 음성 5문항 점수 평균 */
  intonation: number
  /** 단어 점수 — 어휘 정답률 × 100 (0/20/40/60/80/100) */
  vocabulary: number
  /** 종합 점수 — 억양 2 : 단어 1 가중 평균. 등급 판정의 입력이다 */
  overall: number
}

/**
 * 5개 캐릭터형 등급 (KAN-29 표와 1:1).
 *
 * `name`을 화면에 그대로 쓰고 `code`로 표시 자산을 고른다 — code가 자산 키 계약이고(KAN-21),
 * name은 서버가 바꿀 수 있는 표시 문자열이다. 등급명을 클라이언트에 하드코딩하지 않는 이유이기도
 * 하다: 표를 양쪽에 두면 서버가 문구를 고쳤을 때 화면만 옛 이름으로 남는다.
 */
export interface ResultTier {
  /** OUTSIDER · TRAVELER · WANNABE · HONORARY · NATIVE */
  code: string
  /** 외지인 · 여행객 · 사투리 호소인 · 명예주민 · 경남 토박이 */
  name: string
  /** 1(외지인)~5(경남 토박이) */
  rank: number
  /** 전체 등급 수 (5) */
  of: number
}

/**
 * 카카오 공유 카드 자산 (KAN-30이 소비).
 *
 * KAN-29는 이 값을 **화면에 그리지 않고 그대로 들고만 있다.** `imageUrl`이 가리키는
 * 등급별 정적 이미지는 아직 제작 전이라(설정값은 `static.accentury.app` 자리표시자),
 * 결과 화면이 그 로딩에 걸리면 자산이 생기기 전까지 화면이 깨진 채로 보인다.
 * 등급 표현은 디자인 토큰(KAN-148)으로 직접 그리고, 이 자산은 공유 시점에 KAN-30이 쓴다.
 */
export interface ResultShare {
  imageUrl: string
  text: string
  /** 캠페인 파라미터가 붙은 웹 테스트 완성 URL — 전 등급 공통 */
  webTestUrl: string
}

/**
 * `GET .../result`의 200 본문.
 *
 * `status`는 READY 하나뿐이다 — 준비 전(409)과 만료(410)는 200이 아니라 오류 봉투로 오므로
 * 이 타입에 도달하지 않는다. 그래도 필드를 지우지 않는 이유는 정본이 갖고 있기 때문이다.
 */
export interface TestResultView {
  status: 'READY'
  scores: ResultScores
  tier: ResultTier
  /** 등급별 진단 코멘트 — 서버 설정이라 앱 배포 없이 바뀔 수 있다 */
  comment: string
  share: ResultShare
  /** 세션이 고정했던 테스트 정의 버전 (KAN-29 요구 — 결과에 내부적으로 연결) */
  testVersion: string
  /** 집계에 쓴 점수 버전 (예: sv-0.3) */
  scoreVersion: string
  /** ISO-8601. 이 시각 이후 조회는 410이다 (생성 24시간 뒤) */
  expiresAt: string
}
