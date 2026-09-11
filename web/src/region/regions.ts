/**
 * 출신(모어 사투리) 지역 코드 표 (KAN-202, API 명세서 §3.1 `region`).
 *
 * 이 표는 백엔드 `session/Region.java`의 거울이다 — 코드 문자열이 그대로 요청 본문에 실리고,
 * 서버는 열 개 밖의 값(대소문자가 다른 것, 저장 전용 `UNKNOWN` 포함)을 400 `VALIDATION_FAILED`로
 * 돌려준다. 그래서 여기서 코드를 하나라도 바꾸면 백엔드도 같이 바꿔야 한다. 표기와 순서는 KAN-202
 * 표를 따르고, 화면이 지역을 나열하는 순서도 이 배열 순서다 — 따로 정렬하면 기획표와 화면이 갈린다.
 *
 * ## 왜 staging 빌드에서만 켜는가
 *
 * 지역은 KAN-201 학습 데이터의 라벨이다 — staging에 쌓이는 녹음을 지역별로 뽑아 쓰기 위한 값이지
 * 사용자에게 주는 기능이 아니다. prod 번들에는 선택 화면도, 요청 필드도 없어야 한다. 그래서 켜는
 * 조건을 런타임 설정이 아니라 **빌드 변수** `VITE_REGION_SELECT`에 둔다 — 빌드마다 한 값으로
 * 박혀서 prod에서 실수로 켜지는 경로가 없다 (GA4 측정 ID가 빌드 변수인 것과 같은 판단, `ga4.ts`).
 *
 * ## 왜 `=== 'true'` 엄격 비교인가
 *
 * 워크플로가 GitHub vars를 그대로 env로 넘기면 변수가 정의되지 않은 환경에서는 `undefined`가
 * 아니라 **빈 문자열**로 들어올 수 있고, 잘못 잡은 값(`'1'`, `'false'`)도 문자열이라 전부 truthy다.
 * "정확히 `'true'`일 때만"으로 잡아야 그 어떤 실수도 prod에서 화면을 켜는 쪽으로 기울지 않는다.
 */

export const REGIONS = [
  { code: 'SEOUL', label: '서울' },
  { code: 'GYEONGGI', label: '경기' },
  { code: 'GANGWON', label: '강원' },
  { code: 'CHUNGBUK', label: '충북' },
  { code: 'CHUNGNAM', label: '충남' },
  { code: 'JEONBUK', label: '전북' },
  { code: 'JEONNAM', label: '전남' },
  { code: 'GYEONGBUK', label: '경북' },
  { code: 'GYEONGNAM', label: '경남' },
  { code: 'JEJU', label: '제주도' },
] as const

/** 서버가 받아 주는 코드 열 개. `UNKNOWN`은 서버 저장 전용이라 여기 없다 */
export type RegionCode = (typeof REGIONS)[number]['code']

/**
 * 값이 표의 코드인가. 대소문자가 다른 값(`'seoul'`)도 아니다 — 서버가 `name().equals`로
 * 비교하므로 여기서 통과시키면 400이다.
 */
export function isRegionCode(value: unknown): value is RegionCode {
  return typeof value === 'string' && REGIONS.some((region) => region.code === value)
}

/**
 * 이 빌드에 지역 선택이 켜져 있는가. 빌드 변수 `VITE_REGION_SELECT`가 정확히 `'true'`일 때만
 * true이고, 그 외(`undefined`, `''`, `'false'`, `'1'`)는 전부 false다 — 파일 머리 주석 참고.
 *
 * 매번 읽는 함수로 두는 이유는 테스트가 `vi.stubEnv`로 값을 갈아끼울 자리를 남기기 위해서다.
 * 모듈 상수로 잡으면 첫 import 시점의 값이 굳는다.
 */
export function isRegionSelectEnabled(): boolean {
  return (import.meta.env.VITE_REGION_SELECT as string | undefined) === 'true'
}
