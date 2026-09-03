# App Links / Universal Links (KAN-32)

카톡으로 받은 `https://accentury.app/t?c=kko_share`를 눌렀을 때, 앱이 깔려 있으면 브라우저가 아니라
앱이 열리고 유입 계측 코드가 그대로 따라 들어가게 하는 결선이다. 안드로이드는 App Links,
iOS는 Universal Links라 부르지만 성립 조건은 같다 — **도메인이 앱을 인정한다는 선언 파일**이
`/.well-known/` 아래 서빙돼야 한다.

왜 커스텀 스킴(`accentury://`)이 아닌가. 커스텀 스킴은 아무 앱이나 같은 스킴을 선언할 수 있어
링크를 가로챌 수 있고, 앱이 없으면 아무 일도 일어나지 않는다. HTTPS 링크는 도메인 소유가 곧
권한 증명이고, 앱이 없으면 그냥 웹이 열린다 — 공유 링크에 필요한 성질이 정확히 이쪽이다.

## 1. 링크 형태와 링크가 실을 수 있는 것

```
https://accentury.app/t?c=<계측 코드>
```

경로는 `/t`(끝 슬래시 허용)뿐이고, 쿼리에서 앱이 읽는 것은 `c` **하나뿐**이다.

이것이 AC "링크가 개인 결과 또는 세션 토큰을 포함하지 않는다"를 지키는 자리다. 링크는 누구나
손으로 지어낼 수 있으므로, `sessionId`·`screen`·`testVersion`·`bridge`·`app` 같은 파라미터를
붙여 와도 전부 버린다. 읽지 않는 것이 곧 "남의 세션을 주입당하거나 결과 화면으로 건너뛰는 링크가
성립하지 않는다"는 보증이다. 진입 URL은 앱이 `buildWebUrl`로 직접 조립하고, 링크는 계측 코드
한 개만 거기에 실어 보낸다.

계측 코드는 `^[A-Za-z0-9._-]{1,64}$`다 (웹 `campaign.ts`·백엔드 `@Pattern`과 같은 규칙).
코드가 계약에 어긋나면 **진입은 살리고 코드만 버린다** — 공유 링크는 메신저를 여러 번 거치며
잘리거나 트래킹 파라미터가 덧붙는 경로라 코드가 망가진 채 도착하는 일이 실제로 생기는데,
계측은 실패해도 되는 일이고 응시는 아니다.

경로를 `/t`로 정확히 맞춘 이유는 prefix로 열면 `/privacy`·`/terms` 같은 페이지까지 앱이
가져가기 때문이다. 그 화면들은 브라우저에 남아야 한다.

## 2. 링크 한 번의 전체 경로

```
링크 탭
  └─ OS가 "이 도메인이 이 앱을 인정하는가" 판정
       Android: 설치 시 assetlinks.json 대조 (autoVerify)
       iOS:     설치·업데이트 시 애플 CDN이 AASA를 받아 앱에 전달
  └─ 앱으로 배달
       Android: VIEW Intent → MainActivity(singleTask) onCreate/onNewIntent
       iOS:     NSUserActivity → .onContinueUserActivity
  └─ parseAppLink(url, allowedOrigins)   ← origin·경로·쿼리를 여기서 다시 검사
  └─ campaignToken 두 곳으로
       ① buildWebUrl의 `&c=`      → 웹 계측 이벤트
       ② POST /v0/sessions의 campaignToken → 서버 세션에 유입 경로
```

②가 따로 필요한 이유: 앱은 세션을 네이티브로 만든다 (KAN-34). URL에만 실으면 서버 세션에는
유입 경로가 남지 않는다.

OS 판정이 실패해도 파싱 이후는 그대로 동작한다 — 링크가 앱 선택 시트로 떨어질 뿐이다. 그래서
1~3단계는 검증 파일 없이도 결선이 끝났고, 4단계가 "자동으로 앱이 열리는" 마지막 조각이다.

## 3. 같은 사실이 흩어진 자리와 그것을 붙드는 테스트

호스트 2개(`accentury.app`, `staging.accentury.app`)와 경로 2개(`/t`, `/t/`)가 여러 파일에 있다.
한쪽만 고치면 **빌드도 단위 테스트도 조용한데 링크만 죽는다.** 그래서 각 짝을 테스트가 파일을
직접 읽어 대조한다.

| 사실 | 파일 | 대조하는 테스트 |
|---|---|---|
| 안드로이드 host·path·autoVerify·launchMode | `app/src/main/AndroidManifest.xml` | `app/src/test/.../web/AppLinkTest.kt` |
| 안드로이드 진입 origin | `app/.../web/AppLink.kt` `APP_LINK_ORIGINS` | 〃 (매니페스트를 XML 파싱해 대조) |
| iOS host | `ios/Accentury/Accentury.entitlements` | `ios/AccenturyCore/Tests/.../Web/AppLinkTests.swift` |
| iOS 진입 origin | `ios/AccenturyCore/.../Web/AppLink.swift` `appLinkOrigins` | 〃 (entitlements를 직접 읽어 대조) |
| 진입 경로 `/t`·`/t/` | 양 플랫폼 `parseAppLink` | 위 두 테스트 |
| AASA의 경로 집합 | `infra/well-known/*/.well-known/apple-app-site-association` | `infra/modules/edge/spa-rewrite.test.mjs` (매니페스트 `android:path`와 대조) |
| `/.well-known/` 리라이트 예외 | `infra/modules/edge/spa-rewrite.js` | 〃 |
| 릴리스 서명 지문 | `infra/well-known/*/.well-known/assetlinks.json` | 〃 (개수와 릴리스 지문 포함 여부) |

마지막 세 줄이 4단계에서 생겼다. `spa-rewrite.test.mjs`는 CI의 `edge-test` job이 돌린다
(`infra/modules/edge/**`, `infra/well-known/**`, `AndroidManifest.xml` 변경 시).

```bash
node --test 'infra/modules/edge/*.test.mjs'
```

디렉터리 경로를 그냥 넘기면 안 된다 — node 22.6부터 `--test`의 위치 인자는 glob으로 해석돼서,
디렉터리를 주면 그 이름의 모듈을 찾다가 `MODULE_NOT_FOUND`로 죽는다.

## 4. 게시와 확인

```bash
scripts/publish-well-known.sh staging
scripts/publish-well-known.sh prod
```

S3에 올리고 CloudFront를 무효화한 뒤 도메인으로 실제 응답을 받아 로컬 파일과 바이트 비교까지 한다.
멱등이고, 웹 배포와 서로를 지우지 않는다.

**명령과 값의 정본은 `infra/well-known/README.md`다** — 지문을 다시 뽑는 법, 게시 뒤 확인
(구글 `statements:list`, 애플 CDN, `adb shell pm get-app-links`, iOS Diagnostics), Play App
Signing 함정이 거기 있다. 여기 옮겨 적으면 두 벌이 갈린다.

인프라 쪽 사실(왜 Terraform이 안 만드는지, 왜 웹 배포에 안 지워지는지)은
`infra/README.md`의 "App Link 검증 파일 (KAN-32)".

## 5. 검증 파일 없이 링크 경로를 밟아 보기

두 플랫폼 모두 "실제 링크 탭이 부르는 것과 같은 함수"로 URL을 흘려 넣는 디버그 통로가 있다.

```bash
# Android — 매니페스트 필터는 https·accentury.app만 받으므로 컴포넌트를 -n으로 직접 지정한다
adb shell am start -n com.accentury.app/.MainActivity \
  -a android.intent.action.VIEW -d "http://10.0.2.2:5173/t?c=kko_share"

# iOS — simctl openurl은 사파리만 열어서 실행 인자가 유일한 통로다
xcrun simctl launch --console-pty booted com.accentury.app \
  -AppLinkURL "http://localhost:5173/t?c=kko_share"
```

둘 다 디버그 `WEB_URL`(`10.0.2.2:5173` / `localhost:5173`)이 진입 origin 목록에 더해지는 덕에
성립한다. 릴리스에서는 웹 origin이 이미 목록 안이라 아무것도 늘지 않는다.

## 6. 남은 것과 알아 둘 함정

- **Play App Signing 지문 (KAN-174).** 구글이 앱 서명 키를 새로 생성하는 쪽으로 등록하면
  `assetlinks.json`에 넣을 지문은 우리 키스토어의 것이 아니라 Play Console › 앱 서명에 표시된
  구글 인증서의 지문이다. **스토어에서 받은 앱만 딥링크가 깨지고 로컬 릴리스 빌드로는 재현되지
  않는다.** 우리는 키스토어를 PEPK로 올려 지문이 유지되는 쪽을 권한다
  (`docs/wiki/android-release-signing.md` §6).
- **iOS 콜드 스타트의 한 번 재로드.** iOS는 링크를 `NSUserActivity`로 씬이 붙은 뒤에 배달해서,
  콜드 스타트에서는 첫 로드가 코드 없이 한 번 나간 뒤 URL이 바뀌어 다시 로드된다. 안드로이드는
  `setContent` 전에 Intent를 읽어 이 재로드가 없다. 인트로 구간이라 잃을 진행이 없어 그대로 뒀다.
- **Associated Domains capability.** App ID에 이 권한을 붙이는 것은 자동 서명이 **첫 아카이브
  때** 해 준다. 그전에는 개발자 포털에서 보이지 않아 "설정이 빠진 것처럼" 보인다.
- **AASA 반영 시점.** 앱은 AASA를 설치·업데이트 시점에만 받는다. 파일을 고친 뒤 기기에서 확인하려면
  앱을 지웠다 다시 깔아야 한다.
- **`pm verify-app-links`는 재시도가 필요하다.** 안드로이드도 설치 시점에 한 번만 검증하므로,
  검증 파일을 나중에 올렸다면 `adb shell pm verify-app-links --re-verify com.accentury.app`으로
  다시 시켜야 `verified`가 된다.
- **staging에만 디버그 지문이 있다.** 팀원이 늘어 각자 기기에서 확인해야 하면 그 사람의 디버그
  지문을 staging 배열에 추가한다. prod 배열에는 넣지 않는다.
