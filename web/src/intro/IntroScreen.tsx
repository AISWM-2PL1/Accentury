import { useState } from 'react'
import { requestMicrophonePermission, type MicPermission } from '../audio/microphone'
import { detectStorePlatform } from '../audio/storeLink'
import { requestMicPermission } from '../bridge/bridge'
import { Button } from '../ui'
import {
  ESTIMATED_MINUTES,
  VOCABULARY_ITEM_COUNT,
  VOICE_ITEM_COUNT,
} from './introText'
import { MicBlockedScreen } from './MicBlockedScreen'

export interface IntroScreenProps {
  /**
   * 웹에서 마이크 권한을 받은 뒤 갈 곳. **어디로 갈지는 KAN-31이 정한다** — 세션 생성과
   * 라우팅이 그쪽 몫이라, 여기서 화면을 고르면 세션 없이 문항 화면에 들어가는 경로가 생긴다.
   */
  onWebStart?: () => void
  /** 주입용 권한 요청 (테스트용). jsdom에는 `getUserMedia`가 없어 실물로는 한 갈래만 볼 수 있다 */
  requestWebPermission?: () => Promise<MicPermission>
}

/**
 * 테스트 인트로 (FR-TS-01). 권역 선택·동의 단계 없이 앱의 첫 화면이다.
 * 무엇을 하는 테스트인지 알리고 [시작하기]로 마이크 권한 게이트에 넘기는 것까지가 이 화면의 일이다.
 *
 * ## 게이트가 두 갈래인 이유 (KAN-56)
 *
 * 앱 안에서는 네이티브가 권한을 받는다(KAN-98) — 안드로이드 권한 대화상자는 웹이 띄울 수
 * 없고, 거부됐을 때 설정으로 보내는 것도 네이티브만 할 수 있다. 브리지가 없으면 브라우저
 * 단독 실행이므로 웹이 직접 `getUserMedia`로 받는다. **두 경로의 경계가 이 함수 하나다**:
 * 브리지 호출이 성사되면(true) 여기서 끝이고, 아니면 웹 경로로 내려간다.
 *
 * 권한이 없으면 테스트를 시작할 수 없다(API 명세서 §5.6) — 그래서 실패는 안내 화면으로
 * 갈아치운다. 인트로에 오류 문구만 붙이면 [시작하기]가 계속 눌리는 버튼으로 남는다.
 *
 * 배치는 시안(`prototype/src/app/App.tsx` LevelIntroScreen)을 따른다 — 히어로 아이콘,
 * 카피, 숫자 카드, 바닥의 주버튼. 문항 수·시간은 `introText.ts`의 상수가 정본이라
 * KAN-10 연동 때 서버 값으로 바꾸면 화면은 그대로 따라간다.
 */
export function IntroScreen({
  onWebStart = warnWebStartUnwired,
  requestWebPermission = requestMicrophonePermission,
}: IntroScreenProps) {
  const totalItems = VOICE_ITEM_COUNT + VOCABULARY_ITEM_COUNT
  /** 권한 요청 중. 프롬프트가 떠 있는 동안 [시작하기]를 다시 누르지 못하게 막는다 */
  const [requesting, setRequesting] = useState(false)
  /** null이 아니면 게이트가 막혔다는 뜻이고, 값이 곧 막힌 이유다 */
  const [blocked, setBlocked] = useState<Exclude<MicPermission, 'granted'> | null>(null)

  async function startWebGate() {
    setRequesting(true)
    try {
      const permission = await requestWebPermission()
      if (permission === 'granted') {
        setBlocked(null)
        onWebStart()
        return
      }
      setBlocked(permission)
    } finally {
      setRequesting(false)
    }
  }

  function handleStart() {
    // 네이티브가 받아 주면 여기서 끝이다 — 이후 흐름(권한 대화상자, 세션 생성, 문항 화면
    // 재로드)은 전부 앱이 진행하고 이 페이지는 통째로 교체된다.
    if (requestMicPermission()) return
    if (requesting) return
    void startWebGate()
  }

  if (blocked !== null) {
    return (
      <MicBlockedScreen
        reason={blocked}
        platform={detectStorePlatform(navigator.userAgent, navigator.maxTouchPoints)}
        /*
         * 지원 자체가 없는 환경에는 재시도를 주지 않는다. 눌러도 같은 화면으로 되돌아올
         * 뿐이라, 버튼이 있으면 사용자는 될 때까지 눌러야 하는 줄 안다.
         */
        onRetry={
          blocked === 'unsupported'
            ? undefined
            : () => {
                setBlocked(null)
                void startWebGate()
              }
        }
      />
    )
  }

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
        <Button onClick={handleStart} disabled={requesting} style={{ width: '100%' }}>
          {requesting ? '마이크 확인 중…' : '시작하기'}
        </Button>
        <p className="type-caption" style={{ color: 'var(--color-muted-foreground)' }}>
          언제든지 다시 테스트할 수 있어요
        </p>
      </div>
    </main>
  )
}

/**
 * `onWebStart` 기본값. 브라우저 단독 실행에서 권한까지는 받았지만 갈 곳이 아직 없다는 뜻이다 —
 * KAN-31이 세션 생성과 라우팅을 붙이면 이 자리에 실제 이동이 들어온다. 조용히 아무 일도 하지
 * 않으면 "권한은 줬는데 화면이 그대로"라는 증상만 남아 원인을 찾기 어려우므로 흔적을 남긴다.
 */
function warnWebStartUnwired(): void {
  console.warn('[intro] 웹 단독 진입은 KAN-31 결선 전입니다')
}
