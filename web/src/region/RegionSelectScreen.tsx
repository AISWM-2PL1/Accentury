/**
 * 출신(모어 사투리) 지역 선택 화면 (KAN-202).
 *
 * 웹 단독 실행 시작 게이트에 끼운 칸이다: 인트로 [시작하기] → 마이크 권한(KAN-56) → **여기** →
 * 목소리 점검 → 세션 생성(`startStandaloneTest`) → 문항 화면. 고른 값은 세션 생성 본문의
 * `region`(§3.1)으로 나가고, KAN-201이 그 값을 staging 학습 데이터의 S3 키 첫 조각으로 쓴다 —
 * 지역별로 녹음을 뽑아 쓰기 위한 **라벨**이지 사용자에게 주는 기능이 아니다. 그래서 빌드
 * 스위치(`isRegionSelectEnabled`, staging 한정)가 켜진 번들에만 이 화면이 있고, prod 번들은
 * 이 화면도 요청 필드도 없이 이 티켓 전과 같다 (`regions.ts` 헤더).
 *
 * 묻는 것은 **출신 지역**이다. 라벨의 뜻이 "이 녹음의 화자가 어느 사투리 화자인가"라서, 부제가
 * "본인이 사용한다고 생각하는 억양의 지역"으로 그 기준을 한 줄로 못 박는다 (문안은 2026-09-11
 * 팀장 확정). 결과에 영향이 없다는 말도 같은 줄에 둔다 — 없으면 "유리한 지역"을 고르려는
 * 사람이 생긴다.
 *
 * ## 왜 점검 앞인가
 *
 * 네트워크를 쓰지 않는 화면들(권한·지역·점검)을 세션 생성 **앞**에 모아 두면, 어디서 멈추든
 * 서버에 아무도 응시하지 않을 세션이 남지 않는다 (`IntroRoute`가 점검을 세션 앞에 둔 이유와
 * 같다). 점검 뒤에 두면 이미 잰 중심을 든 채 한 화면을 더 지나야 해서 얻는 것이 없다.
 *
 * ## 왜 저장소에 남기지 않는가
 *
 * 값은 세션을 만들 때 한 번만 쓰인다. 세션이 생기면 서버가 들고 있고, `?screen=test` 리로드
 * 뒤의 문항 화면은 이 값을 읽을 일이 없다. 그래서 `micGranted`와 같이 `IntroRoute`의 문서
 * 상태로만 산다 — 리로드하면 마이크 권한부터 다시 받는 흐름이라 지역도 다시 고르는 것이 맞다.
 *
 * 선택지는 어휘 문항의 라디오 카드(`.choice`)를 그대로 쓴다 — 단일 선택 보장·화살표 키 이동·
 * radiogroup 의미론을 브라우저가 주므로 (`VocabularyItemScreen` 헤더).
 */

import { useState } from 'react'
import { Button } from '../ui'
import { CheckIcon } from '../ui/icons'
import { REGIONS, type RegionCode } from './regions'

export interface RegionSelectScreenProps {
  /** 고른 지역 코드를 호출자에게 넘긴다 — 세션 생성 본문의 `region`이 된다 */
  onDone: (region: RegionCode) => void
}

/**
 * 화면에 나열하는 순서. `REGIONS`는 계약 표(백엔드 `Region.java`) 순서라 손대지 않고, 화면만
 * 따로 정한다 — 열 개를 스크롤 없이 한 화면에 두려면 2열이어야 하고, 2열에서는 나열 순서가
 * 곧 지도 위 배치이기 때문이다. 왼쪽 열은 서해안 축(서울·경기·전북·전남·제주), 오른쪽 열은
 * 동해안 축(강원·충북·충남·경북·경남)이다. 강원이 오른쪽 맨 위에 오도록 충북·충남이 그 아래로
 * 내려간다 — 왼쪽 위에서 시계 방향으로 도는 것보다 "서쪽 열·동쪽 열"로 읽히는 쪽이 찾기 쉽다.
 *
 * 배열은 **행 우선**이다 — DOM 순서가 곧 탭 키·스크린 리더 순서라, 그리드가 눈에 보이는 순서
 * (왼쪽→오른쪽, 위→아래)와 같아야 한다. 열 우선으로 두면 탭이 왼쪽 열을 다 내려간 뒤 오른쪽
 * 맨 위로 튄다.
 */
const DISPLAY_ORDER: readonly RegionCode[] = [
  'SEOUL',
  'GANGWON',
  'GYEONGGI',
  'CHUNGBUK',
  'JEONBUK',
  'CHUNGNAM',
  'JEONNAM',
  'GYEONGBUK',
  'JEJU',
  'GYEONGNAM',
]

export function RegionSelectScreen({ onDone }: RegionSelectScreenProps) {
  /*
   * 기본 선택은 없다. 라벨은 "모르겠으면 아무거나"가 아니라 이 화자의 실제 사투리 지역이라,
   * 처음부터 하나가 골라져 있으면 그냥 [다음]을 누른 사람의 녹음이 엉뚱한 지역으로 쌓인다.
   * 같은 이유로 건너뛰기도 없다 — 이 화면이 있는 빌드(staging)는 라벨을 받으려고 있는 빌드다.
   */
  const [selected, setSelected] = useState<RegionCode | null>(null)

  return (
    <main className="item-screen">
      <div>
        <h1 id="region-title" className="type-title-sm">
          출신 지역이 어디신가요?
        </h1>
        <p
          className="type-body-sm"
          style={{ color: 'var(--color-muted-foreground)', marginTop: 'var(--space-2)' }}
        >
          본인이 사용한다고 생각하는 억양의 지역을 골라주시면 됩니다. 
          결과에는 영향이 없어요.
        </p>
      </div>

      <div className="item-screen__body">
        {/* 제목이 곧 이 라디오 그룹의 이름이다 — 스크린 리더가 "그룹 진입"에서 질문을 읽는다 */}
        <div
          className="choice-list choice-list--columns"
          role="radiogroup"
          aria-labelledby="region-title"
        >
          {DISPLAY_ORDER.map((code) => {
            const checked = selected === code
            // 라벨은 계약 표에서 찾는다 — 표기가 바뀌면 한 곳(`regions.ts`)만 고치면 된다
            const label = REGIONS.find((region) => region.code === code)?.label ?? code
            return (
              <label key={code} className={checked ? 'choice choice--selected' : 'choice'}>
                <input
                  className="choice__radio"
                  type="radio"
                  name="region"
                  value={code}
                  checked={checked}
                  onChange={() => setSelected(code)}
                />
                <span>{label}</span>
                {/* 고른 것을 색 말고도 알린다 — 어휘 문항과 같은 규칙 (WCAG 1.4.1) */}
                {checked && (
                  <span className="choice__check">
                    <CheckIcon />
                  </span>
                )}
              </label>
            )
          })}
        </div>
      </div>

      <div className="item-screen__footer">
        {/*
          라디오를 고르는 것과 확정은 다른 동작이다 — 잘못 눌러도 [다음] 전에는 바꿀 수 있어야
          한다. 고르기 전에는 넘길 값이 없으므로 버튼을 잠근다 (어휘 문항의 [다음]과 같다).
        */}
        <Button
          onClick={() => {
            if (selected !== null) onDone(selected)
          }}
          disabled={selected === null}
          style={{ width: '100%' }}
        >
          다음
        </Button>
        {/* 점검 화면의 "저장하거나 보내지 않아요"와 같은 자리 — 이 값이 어디에 쓰이는지 한 줄 */}
        <p
          className="type-caption"
          style={{ color: 'var(--color-muted-foreground)', textAlign: 'center', marginTop: 'var(--space-4)' }}
        >
          이 정보는 억양 분석 모델을 다듬는 데만 써요
        </p>
      </div>
    </main>
  )
}
