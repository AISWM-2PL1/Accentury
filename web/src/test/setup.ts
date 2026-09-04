import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// vitest globals를 끄고 쓰므로 testing-library의 자동 cleanup이 등록되지 않는다.
// 직접 걸어주지 않으면 render가 테스트 간에 누적돼 중복 매칭으로 깨진다.
afterEach(() => {
  cleanup()
})
