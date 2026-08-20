import { requestMicPermission } from '../bridge/bridge'
import { Button } from '../ui'
import {
  ESTIMATED_MINUTES,
  VOCABULARY_ITEM_COUNT,
  VOICE_ITEM_COUNT,
} from './introText'

/**
 * 테스트 인트로 (FR-TS-01). 권역 선택·동의 단계 없이 앱의 첫 화면이다.
 * 무엇을 하는 테스트인지 알리고 [시작하기]로 네이티브 마이크 권한 게이트(KAN-98 영역)에
 * 넘기는 것까지가 이 화면의 일이다.
 *
 * 배치는 시안(`prototype/src/app/App.tsx` LevelIntroScreen)을 따른다 — 히어로 아이콘,
 * 카피, 숫자 카드, 바닥의 주버튼. 문항 수·시간은 `introText.ts`의 상수가 정본이라
 * KAN-10 연동 때 서버 값으로 바꾸면 화면은 그대로 따라간다.
 */
export function IntroScreen() {
  const totalItems = VOICE_ITEM_COUNT + VOCABULARY_ITEM_COUNT

  return (
    <main className="screen">
      <div className="screen__body">
        <div className="hero-icon">
          🎯
          <span className="hero-icon__spark hero-icon__spark--tr" aria-hidden="true">
            ⭐
          </span>
          <span className="hero-icon__spark hero-icon__spark--bl" aria-hidden="true">
            ✨
          </span>
        </div>

        <div>
          <h1 className="type-headline">사투리 억양 테스트</h1>
          <p className="type-body-sm" style={{ color: 'var(--color-muted-foreground)', marginTop: 'var(--space-2)' }}>
            짧은 테스트로 내 억양이
            <br />
            얼마나 사투리인지 알아봐요.
          </p>
        </div>

        {/*
          숫자를 문장에 섞지 않고 칸으로 세운다 - 인트로에서 사용자가 실제로 재는 건
          "얼마나 걸리나"와 "몇 개나 하나" 둘뿐이라, 그 둘만 크게 보이는 편이 낫다.
        */}
        <div className="card">
          <div className="card__stats">
            <div className="card__stat">
              <p className="type-title card__stat-value">{totalItems}문항</p>
              <p className="type-caption card__stat-label">총 문제</p>
            </div>
            <div className="card__stat">
              <p className="type-title card__stat-value">~{ESTIMATED_MINUTES}분</p>
              <p className="type-caption card__stat-label">예상 시간</p>
            </div>
          </div>
          <div className="card__footnote type-label">
            <span>🎤 음성 {VOICE_ITEM_COUNT}문항</span>
            <span className="card__footnote-divider" aria-hidden="true">
              |
            </span>
            <span>📝 단어 {VOCABULARY_ITEM_COUNT}문항</span>
          </div>
        </div>
      </div>

      <div className="screen__footer">
        <Button
          onClick={() => {
            // 브리지가 없으면(브라우저 단독 실행) 조용히 무시한다 — 웹 단독 테스트는 KAN-31 범위.
            requestMicPermission()
          }}
          style={{ width: '100%' }}
        >
          시작하기
        </Button>
        <p className="type-caption" style={{ color: 'var(--color-muted-foreground)' }}>
          언제든지 다시 테스트할 수 있어요
        </p>
      </div>
    </main>
  )
}
