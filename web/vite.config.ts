/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { configDefaults } from 'vitest/config'

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
    /*
     * e2e/는 vitest가 줍지 않는다 (KAN-181). 파일 이름 규칙(`*.spec.ts`)이 겹쳐서 그냥 두면
     * `npm test`가 Playwright 스펙까지 실어 `test`를 못 찾고 죽는다 - 두 러너는 이름만 같지
     * 서로의 API를 모른다.
     *
     * 기본 제외 목록을 펼쳐서 더한다. exclude는 덮어쓰기라, 배열을 그냥 주면 node_modules와
     * dist가 제외 대상에서 빠져 빌드 산출물 안의 테스트까지 돌게 된다.
     */
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
})
