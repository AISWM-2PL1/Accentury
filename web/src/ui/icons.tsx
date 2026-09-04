/**
 * 화면이 쓰는 선화 아이콘 (KAN-161 3단계).
 *
 * ## 이모지가 아니라 SVG인 이유
 *
 * 이모지(✓·🎤·📝)는 **시스템이 자기 색으로 그린다.** 잉크 한 색으로 접은 화면에서 그것만
 * 색을 갖고, 안드로이드·iOS·데스크톱이 저마다 다른 그림을 준다 — 같은 화면이 기기마다 다른
 * 그림이 되는 셈이라 시안을 맞출 수가 없다. 여기 있는 것은 전부 잉크 선 하나로 그린 도형이다.
 *
 * ## 색을 받지 않는다
 *
 * 전부 `currentColor`다. 크림 버튼 안에서는 크림으로, 잉크 글자 옆에서는 잉크로 그려진다 —
 * 아이콘마다 색을 인자로 받으면 부르는 자리가 색을 알아야 하고, 그 자리가 팔레트 바깥의
 * 값을 적기 시작하는 첫 걸음이 된다.
 *
 * 전부 `aria-hidden`이다. 아이콘 옆에는 항상 같은 뜻의 글자가 있고(버튼 라벨, 선택지 문구),
 * 그림까지 읽히면 스크린 리더가 같은 말을 두 번 한다.
 */

/** 고른 선택지의 표시. 잉크 20px — 선택지 오른쪽 끝에 선다 (아트보드 `Vocab.dc.html`) */
export function CheckIcon({ size = 20 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" aria-hidden="true">
      <path
        d="M4 10.5 L8 14.5 L16 5.5"
        stroke="currentColor"
        strokeWidth="2.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

/**
 * 끝난 단계의 표시. 24px 잉크 원 **안에** 들어가므로 획이 더 굵고(3) 크기가 작다(12) —
 * 같은 ✓를 그냥 줄이면 원 안에서 선이 실처럼 얇아져 무엇인지 안 보인다.
 */
export function CheckSmallIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 20 20" fill="none" aria-hidden="true">
      <path
        d="M4 10.5 L8 14.5 L16 5.5"
        stroke="currentColor"
        strokeWidth="3"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

/** 공유. 위로 나가는 화살표 + 담는 상자 — 잉크 주버튼 안이라 크림 선으로 그려진다 */
export function ShareIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true">
      <path
        d="M10 12 L10 3 M6.5 6.5 L10 3 L13.5 6.5 M4 10 L4 16 L16 16 L16 10"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}

/**
 * 마이크 선화. 히어로 아이콘(지름 112 크림 원) 안에 서는 크기라 56px이다.
 *
 * 면이 아니라 선인 이유는 녹음 버튼과 같다 (정본 §8): 크림 위의 잉크 면은 주 버튼과 무게가
 * 같아져 "눌러야 하는 것"으로 읽힌다. 이 그림은 화면이 무엇에 대한 것인지 말할 뿐이다.
 */
export function MicIcon({ size = 56 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" fill="none" aria-hidden="true">
      <path
        d="M24 6 C20.7 6 18 8.7 18 12 L18 24 C18 27.3 20.7 30 24 30 C27.3 30 30 27.3 30 24 L30 12 C30 8.7 27.3 6 24 6 Z M12 22 L12 24 C12 30.6 17.4 36 24 36 C30.6 36 36 30.6 36 24 L36 22 M24 36 L24 42 M17 42 L31 42"
        stroke="currentColor"
        strokeWidth="2.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
