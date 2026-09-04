import { describe, expect, it } from 'vitest'
import { tierImageFor } from './tierAssets'

const CODES = ['OUTSIDER', 'TRAVELER', 'WANNABE', 'HONORARY', 'NATIVE']

describe('tierImageFor', () => {
  it('다섯 등급 전부 그림이 있고 서로 다르다', () => {
    const urls = CODES.map((code) => tierImageFor(code))
    for (const url of urls) expect(url).toEqual(expect.any(String))
    expect(new Set(urls).size).toBe(CODES.length)
  })

  it('소문자·공백이 섞인 code도 같은 그림을 준다 — application.yml 키는 소문자다', () => {
    expect(tierImageFor('honorary')).toBe(tierImageFor('HONORARY'))
    expect(tierImageFor(' Native ')).toBe(tierImageFor('NATIVE'))
  })

  it('모르는 code는 undefined — 화면이 등급명 텍스트로 대신한다', () => {
    expect(tierImageFor('LEGEND')).toBeUndefined()
    expect(tierImageFor('')).toBeUndefined()
  })
})
