import { readFileSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/*
 * iOS 스모크 구동기가 보는 클래스 이름이 이 레포에 아직 있는지 지킨다 (KAN-178).
 *
 * `ios/Accentury/Web/WebAutoDriver.swift`는 웹 화면을 클래스 이름으로 가른다 — 문구로 가르면
 * 카피 한 줄에 스모크가 멈춘다는 이유였는데, 클래스를 지우면 똑같이 멈춘다. 실제로 그렇게
 * 멈춘 적이 있다: 인트로 히어로를 `.illustration--intro`에서 `.intro-hero`로 옮기면서 구동기
 * 쪽을 못 따라갔고, `-AutoFlowDrive` 단독 실행이 인트로를 "unknown"으로 읽어 [시작하기]를
 * 영영 누르지 않았다. 빌드도 타입 검사도 통과하고 `-AutoStartSmoke`를 함께 켠 조합만 돌려
 * 봐서 아무도 못 봤다.
 *
 * 그래서 **웹 쪽 테스트**로 둔다. 이름을 바꾸는 사람은 웹 개발자고, 그 사람이 돌리는 것은
 * `npm test`지 `xcodebuild test`가 아니다. 여기서 깨져야 고칠 사람 손에 걸린다.
 *
 * 보는 것은 "있는가"뿐이다. 어느 화면이 어느 이름을 다는지까지 여기서 못 박으면 화면 구조를
 * 손볼 때마다 이 테스트도 같이 고쳐야 해서, 지키려던 것보다 성가신 것이 된다.
 */

/* vitest의 root가 web/이라 거기서 잰다. `new URL(..., import.meta.url)`을 쓰면 Vite가 그
   경로를 자산으로 여겨 번들하려 들어 레포 밖 파일을 못 읽는다. */
const WEB_ROOT = process.cwd()
const DRIVER_PATH = 'ios/Accentury/Web/WebAutoDriver.swift'

/** 구동기 소스에서 클래스·id 선택자를 걷는다. 큰따옴표 문자열 중 `.`·`#`로 시작하는 것들이다. */
function selectorsInDriver(source: string): string[] {
  const found = new Set<string>()
  for (const [literal] of source.matchAll(/"[.#][^"]*"/g)) {
    // `.choice__radio:not(:disabled)`처럼 뒤에 붙는 의사 클래스·조합자는 떼고 이름만 남긴다.
    const name = literal.slice(1, -1).match(/^[.#][A-Za-z0-9_-]+/)
    if (name) found.add(name[0])
  }
  return [...found].sort()
}

/** `web/src` 아래 모든 소스를 한 덩어리로. 이름은 tsx가 달고 css가 받으므로 둘 다 본다. */
function webSources(dir: string): string {
  let text = ''
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) text += webSources(path)
    // 테스트 파일은 뺀다 — 지워진 클래스를 테스트가 아직 부르고 있으면 초록불이 헛뜬다.
    else if (/\.(tsx?|css)$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name)) text += readFileSync(path, 'utf8')
  }
  return text
}

describe('iOS 스모크 구동기가 보는 선택자', () => {
  const selectors = selectorsInDriver(readFileSync(resolve(WEB_ROOT, '..', DRIVER_PATH), 'utf8'))
  const sources = webSources(join(WEB_ROOT, 'src'))

  it('구동기 소스에서 선택자를 실제로 걷는다', () => {
    // 정규식이 헛돌아 빈 목록이 되면 아래 테스트가 통째로 무의미해진다.
    expect(selectors.length).toBeGreaterThan(5)
    expect(selectors).toContain('.intro-hero')
  })

  it.each(selectors)('%s 가 web/src에 남아 있다', (selector) => {
    const name = selector.slice(1)
    // 이름 전체가 하나의 토큰으로 있어야 한다 — `prompt-card`가 `prompt-card__badge`에
    // 얹혀 통과하면 지우고도 초록불이 뜬다.
    const whole = new RegExp(`(?<![A-Za-z0-9_-])${name}(?![A-Za-z0-9_-])`)
    expect(whole.test(sources), `${selector} 를 쓰는 자리가 web/src에 없다 — ${DRIVER_PATH}도 같이 고쳐야 한다`).toBe(
      true,
    )
  })
})
