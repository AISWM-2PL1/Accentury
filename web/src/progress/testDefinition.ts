/**
 * 서버 테스트 정의 응답(KAN-10, API 명세서 §3.2) 미러 타입.
 *
 * 정본은 백엔드의 `TestDefinitionResponse.java`다 — 이 파일은 그 응답을 웹에서 읽기 위한
 * 사본일 뿐이며, 계약이 바뀌면 정본을 따라 여기를 고친다.
 * 별도 파일로 떼어 둔 이유: 진행 상태 머신(KAN-99)은 정의를 "읽기만" 하므로,
 * 순수 데이터 계약과 상태 전이 로직이 섞이지 않게 하기 위해서다.
 *
 * 정답 정보는 응답에 없다 (KAN-13 정오 미노출) — 그래서 이 타입에도 없다.
 */

/** 문항 유형. 백엔드 `TestDefinition.ItemType` enum과 1:1 */
export type ItemType = 'VOICE' | 'VOCABULARY'

/**
 * 사전 산출된 예측 F0 가이드 곡선 (§3.2, 산출: KAN-17 / 렌더링: KAN-54).
 * 개발 시점에 뽑아 정의에 박아두는 정적 데이터라 런타임 계산이 없다.
 */
export interface GuideF0 {
  /** semitone — 화자 음역 정규화 단위 */
  unit: string
  /** 시간축 샘플링 간격 (ms) */
  frameIntervalMs: number
  /** 정규화된 semitone 배열 */
  values: number[]
  /**
   * 허용 밴드 하한. 발행 검증이 required로 강제하지만(2026-08-09 확정, §6),
   * 응답은 null 필드를 빼고 직렬화하므로 그 이전에 발행된 정의에서는 없을 수 있다.
   */
  bandLow?: number[]
  /** 허용 밴드 상한. 유무 규칙은 bandLow와 같다 */
  bandHigh?: number[]
}

/** 4지선다 선택지. 정오 정보는 없다 — 정답은 서버 내부 모델에만 있다 */
export interface Choice {
  choiceId: string
  text: string
}

/** 유형과 무관하게 모든 문항이 갖는 필드 */
interface TestItemBase {
  itemId: string
  /** 진행 순서의 정본. 연속일 필요는 없고 대소 관계만 의미가 있다 */
  seq: number
  prompt: string
}

/**
 * 음성 문항. 유형별 필드 소유 규칙에 따라 `maxDurationMs`와 `guideF0`를 갖는다.
 * 판별 유니온으로 표현한 이유: 소유 규칙을 주석이 아니라 컴파일러가 강제하게 해서,
 * VOICE에서 `choices`를 읽거나 VOCABULARY에서 `guideF0`를 읽는 코드가 아예 안 써지게 하려는 것이다.
 */
export interface VoiceItem extends TestItemBase {
  type: 'VOICE'
  /** 최대 녹음 길이 (ms). 서버가 전 문항 공통 상수로 채워 내려준다 */
  maxDurationMs: number
  guideF0: GuideF0
}

/** 어휘 문항. 유형별 필드 소유 규칙에 따라 `choices`만 갖는다 */
export interface VocabularyItem extends TestItemBase {
  type: 'VOCABULARY'
  choices: Choice[]
}

/** 문항 하나. `type`으로 좁히면 유형별 필드가 열린다 */
export type TestItem = VoiceItem | VocabularyItem

/**
 * `GET /v0/tests/{testVersion}` 응답 전체.
 *
 * @property testVersion 정의 버전. 세션 생성(KAN-9) 응답에서 오며 세션에 고정된다 (§5.4 발행 후 불변)
 * @property scoreVersion 이 정의를 채점할 점수 버전 (KAN-21)
 * @property dialect 대상 방언. MVP는 GYEONGNAM 고정
 * @property estimatedDurationSec 예상 소요 시간 (초)
 * @property items 문항 10개 = VOICE 5 + VOCABULARY 5, seq 순서 고정
 */
export interface TestDefinition {
  testVersion: string
  scoreVersion: string
  dialect: string
  estimatedDurationSec: number
  items: TestItem[]
}
