/**
 * 대기·오류 문구 블록 (KAN-148). 두 상태를 한 컴포넌트로 묶은 이유: 화면 구성이 같고
 * (문구 + 부연 + 선택적 동작) 색과 역할만 다르다. 따로 두면 한쪽에만 부연이 생기는 식으로
 * 어긋난다.
 *
 * `tone="error"`일 때 `role="alert"`을 붙인다 — 화면이 이미 떠 있는 상태에서 나타나는
 * 실패 문구는 스크린 리더가 스스로 읽어 줘야 사용자가 알아챈다. 대기 문구에는 붙이지
 * 않는다: 로딩은 곧 바뀔 상태라 매번 읽어 주면 소음이 된다.
 */

import type { ReactNode } from 'react'

export interface StatusBlockProps {
  tone: 'waiting' | 'error'
  /** 사용자용 문구. 비난 없는 카피 톤을 지킨다 (ux-ui.md) */
  message: string
  /** 원인·부연. 오류에서는 진단 문구가 들어온다 */
  detail?: string
  /** 재시도 같은 복구 동작 */
  action?: ReactNode
}

export function StatusBlock({ tone, message, detail, action }: StatusBlockProps) {
  return (
    <div
      className={`status-block status-block--${tone}`}
      {...(tone === 'error' ? { role: 'alert' as const } : {})}
    >
      <p className="type-body">{message}</p>
      {detail !== undefined && <p className="type-caption status-block__detail">{detail}</p>}
      {action}
    </div>
  )
}
