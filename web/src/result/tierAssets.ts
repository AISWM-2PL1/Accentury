/**
 * 등급 code → 결과 화면 캐릭터 그림 (KAN-162 2단계).
 *
 * 서버가 주는 `tier.code`(OUTSIDER · TRAVELER · WANNABE · HONORARY · NATIVE, `testResult.ts`)가
 * 자산 키 계약이다 (KAN-21). 표는 이 파일 하나에만 있다 — 화면이 code로 파일명을 조립하면
 * 5장 중 한 장이 빠졌을 때 빌드가 아니라 사용자 화면에서 깨진 이미지로 발견된다. import로
 * 묶어 두면 파일이 없는 순간 빌드가 멈춘다.
 *
 * **등급명은 여기 없다.** alt·폴백 텍스트는 서버의 `tier.name`을 쓴다 — 클라이언트에 등급
 * 표를 두지 않는다는 KAN-29 결정 그대로다. 이 표가 아는 것은 "어느 code에 어느 그림"뿐이다.
 *
 * 그림 원본·프롬프트·재생성 명령은 `assets/characters/README.md`. 여기 WebP는 그 스크립트
 * (`build.py`)의 산출물이라 손으로 고치지 않는다.
 */

import honorary from '../assets/characters/honorary.webp'
import native from '../assets/characters/native.webp'
import outsider from '../assets/characters/outsider.webp'
import traveler from '../assets/characters/traveler.webp'
import wannabe from '../assets/characters/wannabe.webp'

/**
 * `build.py`가 뽑는 WebP의 크기. 4:5 세로 캔버스라 다섯 장이 같다 (`wannabe`만 974로 1px
 * 차이가 나는데 트림 반올림이고, 슬롯 크기는 CSS가 정하므로 표시에 영향이 없다).
 * `<img width height>`에 실어 그림이 오기 전에도 자리를 잡게 한다 — 없으면 이미지 로딩
 * 순간 아래 등급명이 내려앉는다.
 */
export const TIER_IMAGE_WIDTH = 780
export const TIER_IMAGE_HEIGHT = 975

const TIER_IMAGES: Readonly<Record<string, string>> = {
  OUTSIDER: outsider,
  TRAVELER: traveler,
  WANNABE: wannabe,
  HONORARY: honorary,
  NATIVE: native,
}

/**
 * code에 해당하는 그림 URL. 모르는 code면 `undefined` — 화면은 그 경우 등급명 텍스트로
 * 대신한다 (KAN-162 Req 3: 로딩 실패 시 등급명 폴백).
 *
 * 대소문자를 맞춘다: 계약은 대문자지만(§3.7), 백엔드 `application.yml`의 `tiers.<code>`
 * 키와 `image-url` 파일명은 소문자라 어느 쪽 표기가 흘러와도 그림이 빠지지 않게 한다.
 * 앞뒤 공백도 지운다 — 설정 파일에서 흘러온 값의 흔한 사고다.
 */
export function tierImageFor(code: string): string | undefined {
  return TIER_IMAGES[code.trim().toUpperCase()]
}
