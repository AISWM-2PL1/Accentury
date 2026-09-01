/**
 * 브라우저 E2E 설정 (KAN-181 1단계).
 *
 * vitest가 이미 컴포넌트 단위를 지키고 있는데도 실브라우저 한 겹을 더 두는 이유는 **jsdom이
 * 없는 것들** 때문이다 — `getUserMedia`, `AudioContext`, `AudioWorklet`, 그리고 문서를 통째로
 * 다시 로드하는 화면 전환(`window.location.href` 대입). 시작 게이트(인트로 → 마이크 권한 →
 * 목소리 점검 → 세션 생성 → 문항 화면)는 그 넷을 전부 지나가므로, 단위 테스트에서는 주입으로
 * 갈아 끼운 자리만 남고 "실제로 이어지는가"는 아무도 보지 않는다. 여기가 그 자리다.
 *
 * 설정 파일이 `src/`가 아니라 web/ 바닥에 있는 것은 Playwright가 여기서 스펙 디렉터리를
 * 찾기 때문이고, 앱 타입체크(`tsconfig.json`의 include)에는 들어가지 않는다 —
 * e2e 쪽 타입은 `e2e/tsconfig.json`이 따로 본다.
 */

import { defineConfig, devices } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const webDir = path.dirname(fileURLToPath(import.meta.url))

/**
 * 가짜 마이크로 쓸 음성 파일. 앱 디버그 빌드가 에뮬레이터에 주입하는 것과 **같은 파일**이다
 * (`app/src/debug/assets/fake_mic.wav`, 2.5초 "안녕하세요"). 복사본을 web/ 안에 두지 않는
 * 이유는 두 벌이 되면 한쪽만 갱신되기 때문이다 — 앱과 웹이 같은 소리로 같은 점검을 통과해야
 * 한쪽에서 통과한 것이 다른 쪽 근거가 된다.
 *
 * Chromium은 파일 끝에 닿으면 **처음부터 다시 재생한다** — 2단계가 이 파일보다 긴 녹음을
 * 다루므로 짐작으로 두지 않고 실측했다(2026-09-01). 스트림의 rms를 9초 동안 0.28초 간격으로
 * 재 보면 2.5초를 지나도 소리가 끊기지 않고, 같은 봉우리(rms 0.31)가 3396ms와 5951ms에
 * 반복해서 나타난다 — 간격 2555ms가 이 파일 길이와 같다. 문항 녹음이 2.5초를 넘겨도 무음이
 * 되지 않는다는 뜻이라, 파일을 길게 늘릴 필요가 없다.
 *
 * 끊기게 하고 싶으면 경로 뒤에 `%noloop`을 붙인다 (Chromium의 파일 캡처 옵션).
 */
const fakeMicWav = path.resolve(webDir, '../app/src/debug/assets/fake_mic.wav')

/**
 * 스펙이 열 주소. `E2E_BASE_URL`이 있으면 그 환경(staging 등)을 그대로 두드리고, 없으면
 * 아래 `webServer`가 띄운 로컬 개발 서버를 본다. 스펙 쪽에 주소가 하나도 없는 것이 요점이다 —
 * 같은 스펙이 로컬과 배포 환경 양쪽에서 그대로 돈다.
 */
const externalBaseUrl = process.env.E2E_BASE_URL

export default defineConfig({
  testDir: './e2e',
  /*
   * 스펙끼리 세션을 나눠 쓰지 않으므로 병렬이 안전하다. 다만 브라우저마다 가짜 마이크가
   * 같은 파일을 읽는 것뿐이라 서로 간섭하지 않는다.
   */
  fullyParallel: true,
  /*
   * CI에서만 한 번 다시 해 본다. 실브라우저 + 실오디오 파이프라인이라 타이밍으로 한 번씩
   * 흔들릴 수 있는데, 로컬에서까지 재시도를 켜면 그 흔들림을 개발자가 못 본 채 지나간다.
   */
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: externalBaseUrl ?? 'http://localhost:5173',
    /*
     * 실패한 실행만 증거를 남긴다. trace에는 요청·콘솔·DOM 스냅샷이 다 들어 있어
     * `npx playwright show-trace`로 실패 순간을 되감아 볼 수 있다 — 오디오가 얽힌 실패는
     * 로그 몇 줄로는 재구성이 안 된다.
     */
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium-mobile',
      use: {
        /*
         * Pixel 7의 뷰포트·UA·DPR을 그대로 쓰되 `isMobile`은 끈다.
         *
         * 이 서비스의 사용자는 전부 모바일이라 뷰포트는 반드시 좁아야 하지만(`screen__footer`가
         * 바닥에 붙는 배치가 넓은 화면에서는 검증되지 않는다), `isMobile: true`는 Chromium의
         * 모바일 에뮬레이션을 켜서 클릭을 터치 이벤트로 내보낸다. 우리 화면은 마우스도 터치도
         * 같은 `onClick` 하나로 받으므로 얻는 것이 없고, 대신 `hasTouch`가 켜지면 결과 화면의
         * 스토어 판별(`detectStorePlatform`이 `navigator.maxTouchPoints`를 본다)이 데스크톱
         * Chromium UA와 엇갈린 조합으로 흘러 실패 원인을 찾기 어려워진다.
         */
        ...devices['Pixel 7'],
        isMobile: false,
        hasTouch: false,
        launchOptions: {
          args: [
            /*
             * 가짜 마이크 3종 세트. 순서대로 (1) 진짜 장치 대신 합성 장치를 쓰고,
             * (2) 권한 대화상자를 자동 승인하고, (3) 그 합성 장치가 낼 소리를 파일에서 읽는다.
             *
             * (2)가 없으면 `getUserMedia`가 사용자 응답을 기다리다 멈춘다. Playwright의
             * `permissions: ['microphone']` 대신 이 플래그를 쓰는 이유는 (1)·(3)과 한 덩어리라
             * 셋이 같이 있어야 "무엇이 들리는가"까지 정해지기 때문이다 — 권한만 주면 장치가
             * 없는 CI에서 빈 소리가 잡힌다.
             */
            '--use-fake-device-for-media-stream',
            '--use-fake-ui-for-media-stream',
            `--use-file-for-fake-audio-capture=${fakeMicWav}`,
          ],
        },
      },
    },
  ],
  /*
   * 개발 서버는 Playwright가 띄운다 — 사람이 미리 띄워 두는 것을 전제하면 CI에서 그 단계가
   * 통째로 빠진다. 이미 5173이 열려 있으면 그것을 그대로 쓰므로(reuseExistingServer) 로컬에서
   * 개발 서버를 띄워 둔 채 스펙만 돌리는 흐름도 그대로 남는다.
   *
   * `VITE_API_BASE=`(빈 값)이 핵심이다. 빈 값이면 웹이 API를 상대 경로로 부르고, 그 요청을
   * `vite.config.ts`의 `/v0` 프록시가 로컬 백엔드로 넘긴다 — 브라우저가 보기에는 화면과 API가
   * 같은 출처라 배포(CloudFront 단일 출처)와 같은 모양이 되고, CORS 설정을 맞출 일이 없다.
   * 이 값이 없으면 개발 빌드 기본값이 `http://10.0.2.2:8080`(에뮬레이터에서 본 호스트 주소)라
   * 데스크톱 브라우저에서는 닿지 않는다 (App.tsx의 `API_BASE`).
   */
  webServer: externalBaseUrl
    ? undefined
    : {
        command: 'npm run dev -- --port 5173',
        url: 'http://localhost:5173',
        reuseExistingServer: true,
        timeout: 60_000,
        env: { VITE_API_BASE: '' },
      },
})
