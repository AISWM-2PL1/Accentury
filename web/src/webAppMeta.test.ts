import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/*
 * index.html의 head와 `web/public/`의 자산이 서로 맞는지 지킨다 (KAN-179).
 *
 * 여기 있는 것들은 **화면에 안 나타나는 계약**이다. 파비콘 경로를 오타 내도, OG 이미지를 새
 * 버전으로 갈면서 index.html을 안 고쳐도, manifest가 사라진 아이콘을 가리켜도 앱은 멀쩡히 뜨고
 * 단위 테스트도 e2e도 전부 초록불이다. 깨진 것은 링크를 붙여 넣은 사람의 화면에서만 보이고,
 * 그때는 이미 나간 뒤다.
 *
 * 특히 `-v1` 버전 토큰이 그렇다. 자산을 갈려면 `assets/web/build.py`의 VERSION을 올리고 새
 * 이름으로 만든 다음 index.html의 참조를 따라 고쳐야 하는데(캐시 규칙은 assets/web/README.md),
 * 그 세 걸음 중 하나만 빠져도 조용히 어긋난다. 그래서 참조와 실제 파일을 양방향으로 맞춰 본다 —
 * 가리키는 것이 다 있는지, 그리고 있는 것을 다 가리키는지.
 */

/* vitest의 root가 web/이라 거기서 잰다. `new URL(..., import.meta.url)`을 쓰면 Vite가 그 경로를
   자산으로 여겨 번들하려 든다 (nativeSmokeSelectors.test.ts와 같은 이유). */
const WEB_ROOT = process.cwd()
const PUBLIC_DIR = join(WEB_ROOT, 'public')

/** 공유되는 주소는 prod 하나뿐이다 — 배포 산출물이 환경을 모르므로 여기에 박혀 있다 */
const ORIGIN = 'https://accentury.app'

const html = readFileSync(join(WEB_ROOT, 'index.html'), 'utf8')
const head = new DOMParser().parseFromString(html, 'text/html').head

const meta = (selector: string): string =>
  head.querySelector(selector)?.getAttribute('content')?.trim() ?? ''

const linkHref = (rel: string): string =>
  head.querySelector(`link[rel="${rel}"]`)?.getAttribute('href')?.trim() ?? ''

/** PNG의 IHDR에서 실제 픽셀 크기를 읽는다. 선언한 크기와 파일이 갈리는 것이 여기서 잡힌다. */
function pngSize(path: string): [number, number] {
  const buf = readFileSync(path)
  // 시그니처 8바이트를 다 본다 — 앞 네 글자만 보면 잘린 파일도 통과한다
  expect(buf.subarray(0, 8).toString('hex'), `${path}: PNG가 아니다`).toBe('89504e470d0a1a0a')
  return [buf.readUInt32BE(16), buf.readUInt32BE(20)]
}

/** `/favicon-v1.svg` → `web/public/favicon-v1.svg`. 절대 URL이면 오리진을 떼고 같은 규칙을 쓴다. */
function publicPath(reference: string): string {
  const path = reference.startsWith(ORIGIN) ? reference.slice(ORIGIN.length) : reference
  return join(PUBLIC_DIR, path.replace(/^\//, ''))
}

const manifestHref = linkHref('manifest')
const manifest = JSON.parse(readFileSync(publicPath(manifestHref), 'utf8'))

describe('head 메타와 public 자산', () => {
  it('링크 미리보기에 필요한 OG 태그가 다 있다', () => {
    // 하나라도 비면 카카오·슬랙이 그 자리를 빈 칸으로 그린다
    for (const property of ['og:type', 'og:site_name', 'og:title', 'og:description', 'og:url', 'og:image']) {
      expect(meta(`meta[property="${property}"]`), `${property} 가 비어 있다`).not.toBe('')
    }
    // 이 값이 아니면 큰 이미지 카드가 아니라 작은 썸네일 카드로 그려진다
    expect(meta('meta[name="twitter:card"]')).toBe('summary_large_image')
    // 링크 미리보기지 기사·프로필이 아니다. 값이 틀리면 받는 쪽이 다른 모양으로 그린다
    expect(meta('meta[property="og:type"]')).toBe('website')
  })

  it('같은 태그가 두 번 있지 않다', () => {
    /* 크롤러마다 첫 것을 쓰기도 마지막 것을 쓰기도 한다 — 둘이 있으면 어느 쪽이 나갈지가
       받는 앱에 달리고, 우리 화면에서는 아무것도 달라 보이지 않는다. */
    for (const selector of [
      'meta[property="og:title"]',
      'meta[property="og:image"]',
      'meta[property="og:url"]',
      'meta[name="twitter:card"]',
      'meta[name="theme-color"]',
      'link[rel="icon"]',
      'link[rel="manifest"]',
    ]) {
      expect(head.querySelectorAll(selector).length, `${selector} 가 여러 개다`).toBe(1)
    }
  })

  it('og:url과 og:image가 절대 URL이다', () => {
    // 상대 경로면 카카오가 이미지를 자기 서버로 가져가지 못해 카드가 글자만 남는다
    for (const property of ['og:url', 'og:image']) {
      // 정규식을 쓰지 않는다 — 오리진의 `.`이 아무 글자나 먹어 accenturyXapp도 통과한다
      const value = meta(`meta[property="${property}"]`)
      expect(value.startsWith(`${ORIGIN}/`), `${property}(${value})가 ${ORIGIN}으로 시작하지 않는다`).toBe(true)
    }
  })

  it('선언한 og:image 크기가 실제 파일과 같다', () => {
    const declared = [Number(meta('meta[property="og:image:width"]')), Number(meta('meta[property="og:image:height"]'))]
    expect(pngSize(publicPath(meta('meta[property="og:image"]')))).toEqual(declared)
    /* 큰 이미지 카드로 그려지는 비율은 1.91:1 언저리다(1200×630 = 1.905). 여기서 크게
       벗어나면 받는 쪽 앱이 위아래나 좌우를 잘라 문구가 사라지므로, 정확한 값이 아니라
       "카드로 읽히는 범위"를 본다 — 캔버스를 조금 손보는 것까지 막을 이유는 없다. */
    const ratio = declared[0] / declared[1]
    expect(ratio, `${declared[0]}x${declared[1]} 는 1.91:1 카드 비율에서 너무 멀다`).toBeGreaterThan(1.85)
    expect(ratio).toBeLessThan(1.95)
  })

  it('head가 가리키는 자산이 public/에 다 있다', () => {
    const referenced = [linkHref('icon'), linkHref('apple-touch-icon'), manifestHref, meta('meta[property="og:image"]')]
    for (const reference of referenced) {
      expect(reference, '참조가 비어 있다').not.toBe('')
      const path = publicPath(reference)
      expect(existsSync(path), `${reference} 를 가리키는 것이 public/에 없다`).toBe(true)
      // 같은 이름의 디렉터리가 있으면 존재 검사만으로는 통과한다
      expect(statSync(path).isFile(), `${reference} 는 파일이 아니다`).toBe(true)
    }
  })

  it('manifest가 홈 화면 추가만 하고 설치형 PWA로 뜨지 않는다', () => {
    // standalone이면 크롬이 "앱 설치" 배너를 띄워 스토어 설치(KAN-174·175)와 두 갈래가 된다
    expect(manifest.display).toBe('browser')
    expect(manifest.name).not.toBe('')
    expect(manifest.short_name).toBe('Accentury')
  })

  it('manifest 아이콘이 실재하고 적힌 크기와 같다', () => {
    // 192·512는 티켓이 정한 집합이다(홈 화면 바로가기와 스플래시가 이 둘을 집는다)
    expect(manifest.icons.map((icon: { sizes: string }) => icon.sizes).sort()).toEqual(['192x192', '512x512'])
    for (const icon of manifest.icons) {
      expect(icon.type, `${icon.src} 에 type이 없다`).toBe('image/png')
      const [width, height] = pngSize(publicPath(icon.src))
      expect(`${width}x${height}`, `${icon.src} 의 sizes가 실제 크기와 다르다`).toBe(icon.sizes)
    }
  })

  it('테마색이 디자인 토큰의 배경색과 같다', () => {
    /* 주소창·상태 표시줄 색이 화면 종이색과 갈리면 스크롤 끝에서 다른 색 띠가 보인다.
       값을 여기 적어 두지 않고 tokens.css에서 읽는 이유는, 팔레트가 바뀔 때 이 테스트가
       같이 따라와서는 안 되기 때문이다 — 따라오면 갈린 것을 알려 줄 사람이 없다. */
    const tokens = readFileSync(join(WEB_ROOT, 'src', 'tokens.css'), 'utf8')
    const background = tokens.match(/--color-background:\s*(#[0-9a-fA-F]{6})/)?.[1]
    expect(background, 'tokens.css에서 --color-background를 찾지 못했다').toBeDefined()
    expect(meta('meta[name="theme-color"]').toLowerCase()).toBe(background?.toLowerCase())
    expect(manifest.theme_color.toLowerCase()).toBe(background?.toLowerCase())
    expect(manifest.background_color.toLowerCase()).toBe(background?.toLowerCase())
  })

  it('public/에 아무도 가리키지 않는 파일이 없다', () => {
    /* 버전을 올리면서 옛 파일을 안 지우면 1년 캐시에 두 벌이 남는다. 반대 방향(참조 → 파일)만
       보면 이것은 영영 안 잡힌다 — 새 파일이 다 있으니 초록불이다. */
    const referenced = new Set(
      [
        linkHref('icon'),
        linkHref('apple-touch-icon'),
        manifestHref,
        meta('meta[property="og:image"]'),
        ...manifest.icons.map((icon: { src: string }) => icon.src),
      ].map((reference) => publicPath(reference)),
    )
    const orphans = readdirSync(PUBLIC_DIR).filter((file) => !referenced.has(join(PUBLIC_DIR, file)))
    expect(orphans, 'index.html·manifest 어느 쪽도 가리키지 않는다 — 버전을 올리고 지우지 않았는지 본다').toEqual([])
  })
})
