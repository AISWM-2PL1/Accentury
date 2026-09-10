/**
 * 발행본 가이드 곡선 전수 검사 (KAN-194).
 *
 * 픽스처 한 문항(`guideF0Fixture.ts`)이 대표라면 이 파일은 표본이 아니라 전수다 — 정본
 * 발행본 `gn-2026.09.1`의 음성 145문항을 하나씩 곡선 계산에 태워, 실제로 사용자에게
 * 내려가는 데이터에서 곡선이 버려지는 문항(AC1)과 사용자 창이 폴백으로 주저앉는
 * 문항(AC2)이 0건임을 확인한다. 곡선 규칙 자체는 `guideCurve.test.ts`·`userCurve.test.ts`가
 * 덮으므로, 여기서 보는 것은 "그 규칙이 발행본 형태를 견디는가" 하나다.
 *
 * 입력은 백엔드 마이그레이션 SQL을 직접 읽는다. 웹에 정의 사본을 떠 두면 발행본이 바뀔 때
 * 사본만 낡아 초록불이 거짓말을 하므로, 정본 파일을 그때그때 읽는 편이 맞다.
 *
 * **파일이 없으면 skip이 아니라 실패다.** 발행본이 이 검사의 정본이라, 못 읽었으면 검사가
 * 안 돌았다는 뜻이지 통과했다는 뜻이 아니다. 조용히 건너뛰면 마이그레이션 파일이
 * 사라지거나 이름이 바뀐 날 아무도 모르게 커버리지가 0이 된다.
 */

import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import type { GuideF0 } from '../progress/testDefinition'
import { guideCurveDisplayPoints } from './guideCurve'
import { USER_CURVE_WINDOW_SCALE, userCurveWindowMs } from './userCurve'

/** 정본 발행본이 담긴 마이그레이션. cwd가 아니라 이 파일 위치를 기준으로 잡는다 */
const MIGRATION_PATH = resolve(
  dirname(fileURLToPath(import.meta.url)),
  '../../../backend/src/main/resources/db/migration/V6__gn_2026_09_1_content.sql',
)

/** 정의 JSON을 감싼 PostgreSQL 달러 인용 구분자 */
const DELIMITER = '$definition$'

/** 발행본 문항 중 이 검사가 보는 부분만. 정의 전체 타입은 `testDefinition.ts`가 정본이다 */
interface PublishedVoiceItem {
  itemId: string
  scriptKey: string
  type: 'VOICE'
  guideF0: GuideF0
}

/** 가이드가 없거나 잘못된 문항도 그대로 실어야 검사가 그것을 잡는다 */
interface PublishedItem {
  itemId: string
  scriptKey: string
  type: string
  guideF0?: GuideF0
}

function loadPublishedVoiceItems(): PublishedVoiceItem[] {
  if (!existsSync(MIGRATION_PATH)) {
    throw new Error(
      `발행본 마이그레이션을 찾지 못했다: ${MIGRATION_PATH}\n` +
        '이 검사의 입력은 정본 발행본이라, 파일이 없으면 건너뛰는 게 아니라 실패다.',
    )
  }
  const sql = readFileSync(MIGRATION_PATH, 'utf8')
  const start = sql.indexOf(DELIMITER)
  const end = sql.indexOf(DELIMITER, start + DELIMITER.length)
  if (start < 0 || end < 0) {
    throw new Error(`${DELIMITER} 구분자로 감싼 정의 JSON이 없다: ${MIGRATION_PATH}`)
  }

  const definition = JSON.parse(sql.slice(start + DELIMITER.length, end)) as {
    testVersion: string
    items: PublishedItem[]
  }
  expect(definition.testVersion).toBe('gn-2026.09.1')
  return definition.items.filter((item): item is PublishedVoiceItem => item.type === 'VOICE')
}

const voiceItems = loadPublishedVoiceItems()

/**
 * 조건을 어긴 문항만 추린다. 개수만 세면 "몇 개 깨졌다"까지만 나오지만, 어긴 문항을 모아
 * 비교하면 실패 메시지에 itemId·scriptKey와 어긋난 값이 그대로 찍힌다.
 */
function violations(
  inspect: (item: PublishedVoiceItem) => Record<string, unknown> | null,
): Record<string, unknown>[] {
  const found: Record<string, unknown>[] = []
  for (const item of voiceItems) {
    const detail = inspect(item)
    if (detail !== null) {
      found.push({ itemId: item.itemId, scriptKey: item.scriptKey, ...detail })
    }
  }
  return found
}

/**
 * VOICE 문항의 녹음 상한. 정의 JSON에는 없고 서버가 응답에 붙이는 고정값이라
 * (백엔드 `TestDefinition.VOICE_MAX_DURATION_MS`) 이 검사도 같은 값을 놓고 본다.
 *
 * **이 값은 BE 레포에 있어 여기서 참조할 수 없다 — 손으로 맞춘 사본이다.** 서버가 상한을
 * 올리면 실제로는 아무 문항도 안 잘리는데 이 검사는 여전히 "29개가 물린다"를 통과시킨다.
 * 그 드리프트를 여기서 잡을 방법이 없으므로, 상한을 바꾸는 BE 변경은 이 상수도 함께 고쳐야
 * 한다. 앱·iOS 쪽 같은 검사는 `RecordingEngine`의 실제 상수를 참조하므로 이 문제가 없다.
 */
const VOICE_MAX_DURATION_MS = 10_000

/** 가이드를 쓸 수 없을 때의 창 길이. 어느 문항도 여기로 떨어지면 안 된다 */
const FALLBACK_WINDOW_MS = userCurveWindowMs(null, null, VOICE_MAX_DURATION_MS)

describe('발행본 gn-2026.09.1 가이드 곡선 전수 검사 (KAN-194)', () => {
  it('음성 문항이 145개다', () => {
    expect(voiceItems.length).toBe(145)
  })

  it('frameIntervalMs는 전 문항이 1 이상의 정수다', () => {
    // 산출물의 실수를 반올림해 발행한다 (2026-09-04 결정) - 스키마와 앱·웹이 정수로 읽는다
    expect(
      violations((item) => {
        const interval = item.guideF0.frameIntervalMs
        return Number.isInteger(interval) && interval >= 1 ? null : { frameIntervalMs: interval }
      }),
    ).toEqual([])
  })

  it('전 문항에서 곡선이 그려진다 - 버려지는 문항이 없다 (AC1)', () => {
    expect(
      violations((item) =>
        guideCurveDisplayPoints(item.guideF0.values).length > 0
          ? null
          : { valueCount: item.guideF0.values.length, points: 0 },
      ),
    ).toEqual([])
  })

  it('전 문항의 사용자 창이 가이드 길이에서 나오고 폴백으로 떨어지지 않는다 (AC2)', () => {
    expect(
      violations((item) => {
        const { frameIntervalMs, values } = item.guideF0
        const actual = userCurveWindowMs(frameIntervalMs, values.length, VOICE_MAX_DURATION_MS)
        const doubled = USER_CURVE_WINDOW_SCALE * frameIntervalMs * (values.length - 1)
        const expected = Math.min(doubled, VOICE_MAX_DURATION_MS)
        if (actual === expected && actual !== FALLBACK_WINDOW_MS) return null
        return { windowMs: actual, expectedWindowMs: expected, fallbackMs: FALLBACK_WINDOW_MS }
      }),
    ).toEqual([])
  })

  it('전 문항의 사용자 창이 녹음 상한을 넘지 않는다 (KAN-195)', () => {
    // 상한을 넘는 창은 녹음으로 닿을 수 없는 오른쪽 여백이 되어 레인이 끝까지 차지 않는다.
    expect(
      violations((item) => {
        const { frameIntervalMs, values } = item.guideF0
        const windowMs = userCurveWindowMs(frameIntervalMs, values.length, VOICE_MAX_DURATION_MS)
        return windowMs <= VOICE_MAX_DURATION_MS ? null : { windowMs, maxDurationMs: VOICE_MAX_DURATION_MS }
      }),
    ).toEqual([])
  })

  it('상한이 실제로 물리는 문항이 29개다 - 자르기가 죽은 코드가 아니다 (KAN-195)', () => {
    /*
     * 앞 검사는 "상한을 넘지 않는다"만 보므로 상한이 실제로 물리는지는 말해 주지 않는다.
     * 발행본 가이드가 3.18~5.98초라 두 배가 6.36~11.96초이고, 그중 5초를 넘는 29문항만
     * 상한에 닿는다 - 그 수를 함께 못박아야 발행본이 바뀐 것을 안다. 이 수가 달라지면
     * 창 규칙을 다시 볼 자리라는 신호다.
     */
    const clamped = voiceItems.filter((item) => {
      const { frameIntervalMs, values } = item.guideF0
      return USER_CURVE_WINDOW_SCALE * frameIntervalMs * (values.length - 1) > VOICE_MAX_DURATION_MS
    })
    expect(clamped.length).toBe(29)
    expect(
      clamped.every(
        (item) =>
          userCurveWindowMs(item.guideF0.frameIntervalMs, item.guideF0.values.length, VOICE_MAX_DURATION_MS) ===
          VOICE_MAX_DURATION_MS,
      ),
    ).toBe(true)
  })

  it('단위는 semitone이고 허용 밴드는 없다 - KAN-17 1안은 중앙선만 낸다', () => {
    expect(
      violations((item) => {
        const { unit, bandLow, bandHigh } = item.guideF0
        if (unit === 'semitone' && bandLow === undefined && bandHigh === undefined) return null
        return { unit, hasBandLow: bandLow !== undefined, hasBandHigh: bandHigh !== undefined }
      }),
    ).toEqual([])
  })

  it('값 개수는 20의 배수이고 160~320점이다 - 어절 20등분 격자', () => {
    expect(
      violations((item) => {
        const count = item.guideF0.values.length
        return count % 20 === 0 && count >= 160 && count <= 320 ? null : { valueCount: count }
      }),
    ).toEqual([])
  })
})
