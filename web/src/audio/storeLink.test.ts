import { describe, expect, it } from 'vitest'
import {
  DEFAULT_APP_STORE_URL,
  DEFAULT_PLAY_STORE_URL,
  detectStorePlatform,
  storeUrlFor,
} from './storeLink'

const ANDROID_UA =
  'Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36'
const IPHONE_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1'
/** iPadOS 13+ 사파리의 기본 UA — 데스크톱 맥과 글자 하나 다르지 않다 */
const IPADOS_DESKTOP_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15'

describe('detectStorePlatform', () => {
  it('안드로이드 UA는 android다', () => {
    expect(detectStorePlatform(ANDROID_UA)).toBe('android')
  })

  it('아이폰 UA는 ios다', () => {
    expect(detectStorePlatform(IPHONE_UA)).toBe('ios')
  })

  it('아이패드는 맥 UA로 오지만 터치 포인트로 갈린다', () => {
    expect(detectStorePlatform(IPADOS_DESKTOP_UA, 5)).toBe('ios')
    // 진짜 맥. 같은 UA라도 터치 스크린이 없다
    expect(detectStorePlatform(IPADOS_DESKTOP_UA, 0)).toBe('unknown')
  })

  it('맥에서 터치 포인트를 빠뜨리면 데스크톱으로 본다 (기본값)', () => {
    expect(detectStorePlatform(IPADOS_DESKTOP_UA)).toBe('unknown')
  })

  it('알 수 없는 UA는 unknown이다', () => {
    expect(detectStorePlatform('Mozilla/5.0 (X11; Linux x86_64)')).toBe('unknown')
  })
})

describe('storeUrlFor', () => {
  it('ios는 앱스토어, 나머지는 플레이스토어다', () => {
    expect(storeUrlFor('ios')).toBe(DEFAULT_APP_STORE_URL)
    expect(storeUrlFor('android')).toBe(DEFAULT_PLAY_STORE_URL)
    // 모바일에서 iOS가 아니면 사실상 안드로이드다
    expect(storeUrlFor('unknown')).toBe(DEFAULT_PLAY_STORE_URL)
  })

  it('플레이스토어 URL에 앱 패키지명이 들어 있다', () => {
    expect(storeUrlFor('android')).toContain('id=com.accentury.app')
  })
})
