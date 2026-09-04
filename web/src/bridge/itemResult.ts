/**
 * 네이티브 → 웹 반환 계약 (KAN-89) 미러 타입.
 *
 * 정본은 네이티브의 `ItemResult.kt`다 — 이 파일은 그 payload를 웹에서 읽기 위한 사본일 뿐이며,
 * 계약이 바뀌면 정본을 따라 여기를 고친다 (testDefinition.ts가 백엔드 응답을 미러하는 것과 같은 방식).
 *
 * 원본 PCM은 이 타입에 실리지 않는다 — 바이트 계열 필드가 없는 것이 계약의 핵심이다.
 * 음성은 업로드 경로로만 서버에 가고 브리지를 경유하지 않는다 (FR-DP-02).
 */

/**
 * 클라이언트 측 품질 판정. 네이티브 `QualityStatus` enum과 이름까지 1:1이다
 * (kotlinx-serialization이 enum을 이름 문자열로 내보낸다).
 */
export type QualityStatus = 'NORMAL' | 'TOO_SHORT' | 'TOO_QUIET' | 'CLIPPED'

const QUALITY_STATUSES: readonly QualityStatus[] = ['NORMAL', 'TOO_SHORT', 'TOO_QUIET', 'CLIPPED']

/**
 * React가 문항 하나에 대해 돌려받는 전부. 필드는 이 5개가 계약이다.
 *
 * @property durationMs 네이티브에선 Long이지만 녹음 길이는 최대 수십 초라
 *   `Number.MAX_SAFE_INTEGER` 근처에 갈 일이 없어 number로 받는다
 */
export interface ItemResult {
  itemId: string
  attemptId: string
  analysisJobId: string
  durationMs: number
  qualityStatus: QualityStatus
}

/**
 * 네이티브가 넘긴 JSON 문자열을 계약 타입으로 좁힌다. 신뢰할 수 없으면 null이다.
 *
 * 브리지 반대편은 웹에서 보면 신뢰 경계 밖이다 — evaluateJavascript로 들어오는 문자열은
 * 이론적으로 무엇이든 될 수 있으므로 `unknown`으로 받아 필드마다 실제로 확인한다
 * (progressSnapshot.ts의 저장소 읽기와 같은 방침).
 *
 * 모르는 필드가 섞여 있어도 통과시킨다: 계약 규칙(§5)상 필드 추가는 하위호환이므로,
 * 새 필드를 단 신버전 앱이 구버전 웹에서 거부당하면 안 된다. 반대로 모르는 `qualityStatus`
 * 값은 거부한다 — 값이 늘어나는 것은 enum의 의미 변경이라 버전 증가 대상이고,
 * 화면이 판정할 수 없는 상태를 그냥 흘려보내는 편이 더 위험하다.
 */
export function parseItemResult(raw: string): ItemResult | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }

  if (typeof parsed !== 'object' || parsed === null) return null
  const { itemId, attemptId, analysisJobId, durationMs, qualityStatus } = parsed as Record<
    keyof ItemResult,
    unknown
  >

  if (typeof itemId !== 'string') return null
  if (typeof attemptId !== 'string') return null
  if (typeof analysisJobId !== 'string') return null
  // NaN·Infinity·음수 길이는 어느 것도 정상 녹음에서 나올 수 없다.
  if (typeof durationMs !== 'number' || !Number.isFinite(durationMs) || durationMs < 0) return null
  if (!isQualityStatus(qualityStatus)) return null

  return { itemId, attemptId, analysisJobId, durationMs, qualityStatus }
}

function isQualityStatus(value: unknown): value is QualityStatus {
  return typeof value === 'string' && (QUALITY_STATUSES as readonly string[]).includes(value)
}
