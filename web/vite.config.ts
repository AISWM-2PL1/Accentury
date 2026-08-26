/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  /*
   * 개발 서버는 배포와 같은 단일 출처로 만든다 (API 명세서 §1.1·§2.5 - 화면과 /v0/*가 같은
   * 도메인, CORS 없음). `/v0`를 로컬 백엔드로 프록시하면 `VITE_API_BASE=`(빈 값, 상대 경로)로
   * 띄운 웹이 브라우저·휴대폰·에뮬레이터 어디서 열려도 API가 같은 출처다 - 기기마다 백엔드
   * 주소와 CORS allowlist를 맞추던 수고가 사라진다.
   *
   * allowedHosts: 휴대폰 실기기 확인은 HTTPS가 필요하다(getUserMedia는 보안 컨텍스트 전용).
   * `cloudflared tunnel --url http://localhost:5173`이 주는 *.trycloudflare.com 호스트를
   * Vite 7의 Host 검사가 막지 않도록 허용한다. 개발 서버 전용 설정이라 번들에는 영향이 없다.
   */
  server: {
    proxy: {
      '/v0': 'http://localhost:8080',
    },
    allowedHosts: ['.trycloudflare.com'],
  },
  build: {
    // 해시 자산은 CloudFront에서 immutable 캐시, index.html만 no-cache (webview-layer.md §4)
    outDir: 'dist',
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
})
