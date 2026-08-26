/**
 * 마이크 게이트 실패 안내 (KAN-56 Stage 2).
 *
 * 브라우저에서 녹음을 시작할 수 없을 때 인트로를 대신한다. 화면의 목적은 하나 —
 * **사용자가 다음에 무엇을 하면 되는지** 알려 주는 것이다. 그래서 실패 사유를 그대로 옮기지
 * 않고(그건 콘솔 몫이다) 사유마다 다른 행동을 제시한다.
 *
 * 앱 링크가 주버튼인 이유: 세 사유 중 둘(unsupported·unavailable)은 브라우저 안에서
 * 사용자가 할 수 있는 일이 없거나 확실치 않은데, 앱은 어느 경우에도 확실하게 동작한다.
 * 권한 거부(denied)만 설정으로 되돌릴 수 있어 [다시 시도]를 같이 준다.
 */

import { storeUrlFor, type StorePlatform } from '../audio/storeLink'
import { Button } from '../ui'

export interface MicBlockedScreenProps {
  reason: 'denied' | 'unavailable' | 'unsupported'
  platform: StorePlatform
  /** 권한을 다시 요청한다. 되돌릴 여지가 없는 `unsupported`에는 주지 않는다 */
  onRetry?: () => void
}

/** 사유별 문구. 비난 없는 톤을 지킨다 — "허용하지 않으셨습니다"가 아니라 무엇이 필요한지 */
const COPY: Record<MicBlockedScreenProps['reason'], { title: string; body: string }> = {
  denied: {
    title: '마이크 권한이 필요해요',
    body: '억양 테스트는 목소리로 진행돼요. 브라우저 설정에서 마이크를 허용한 뒤 다시 시작해 주세요.',
  },
  unsupported: {
    title: '이 브라우저에서는 녹음을 지원하지 않아요',
    body: '앱에서는 바로 테스트할 수 있어요.',
  },
  unavailable: {
    title: '마이크를 사용할 수 없어요',
    body: '다른 앱이 마이크를 쓰고 있지 않은지 확인해 주세요.',
  },
}

export function MicBlockedScreen({ reason, platform, onRetry }: MicBlockedScreenProps) {
  const copy = COPY[reason]
  const storeLabel = platform === 'ios' ? 'App Store' : 'Play 스토어'

  return (
    <main className="screen">
      <div className="screen__body">
        <div className="hero-icon">🎤</div>

        <div>
          <h1 className="type-title-sm">{copy.title}</h1>
          <p
            className="type-body-sm"
            style={{ color: 'var(--color-muted-foreground)', marginTop: 'var(--space-2)' }}
          >
            {copy.body}
          </p>
        </div>
      </div>

      <div className="screen__footer">
        {/*
          버튼이 아니라 <a>인 이유: 스토어로 나가는 것은 이동이라 링크의 기본 동작(새 탭·길게
          눌러 복사·스크린 리더의 "링크" 안내)이 전부 의미를 갖는다. onClick으로 location을
          바꾸면 그게 전부 사라진다. 생김새만 주버튼과 맞춘다.
        */}
        <a className="btn btn--primary" href={storeUrlFor(platform)} style={{ width: '100%' }}>
          앱으로 테스트하기
        </a>
        <p className="type-caption" style={{ color: 'var(--color-muted-foreground)' }}>
          {storeLabel}로 이동해요
        </p>
        {onRetry !== undefined && (
          <Button variant="text" onClick={onRetry} style={{ width: '100%' }}>
            다시 시도
          </Button>
        )}
      </div>
    </main>
  )
}
