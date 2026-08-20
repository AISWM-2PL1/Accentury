import { requestMicPermission } from '../bridge/bridge'
import {
  compositionText,
  estimatedDurationText,
  ESTIMATED_MINUTES,
  VOCABULARY_ITEM_COUNT,
  VOICE_ITEM_COUNT,
} from './introText'

/**
 * 테스트 인트로 (FR-TS-01). 권역 선택·동의 단계 없이 앱의 첫 화면이다.
 * 디자인은 붙이지 않는다 — 무엇을 하는 테스트인지 알리고 [시작하기]로
 * 네이티브 마이크 권한 게이트(KAN-98 영역)에 넘기는 것까지가 이 화면의 일이다.
 */
export function IntroScreen() {
  return (
    <main
      style={{
        minHeight: '100dvh',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 'var(--space-3)',
        padding: 'var(--space-4)',
        textAlign: 'center',
      }}
    >
      <h1 className="type-title">사투리 억양 테스트</h1>
      <p className="type-body">{compositionText(VOICE_ITEM_COUNT, VOCABULARY_ITEM_COUNT)}</p>
      <p className="type-label">{estimatedDurationText(ESTIMATED_MINUTES)}</p>
      <button
        type="button"
        className="type-body"
        onClick={() => {
          // 브리지가 없으면(브라우저 단독 실행) 조용히 무시한다 — 웹 단독 테스트는 KAN-31 범위.
          requestMicPermission()
        }}
        style={{
          // ux-ui.md 최소선: 터치 타겟 48dp 이상 (--touch-target-min)
          minHeight: 'var(--touch-target-min)',
          minWidth: '120px',
          marginTop: 'var(--space-2)',
          cursor: 'pointer',
        }}
      >
        시작하기
      </button>
    </main>
  )
}
