# App Link 검증 파일 (KAN-32 4단계)

`https://accentury.app/t?c=...` 링크를 브라우저가 아니라 앱이 받게 하려면, 그 도메인이
"이 앱을 인정한다"고 공개적으로 선언해야 한다. 그 선언이 여기 있는 두 파일이고, 각 환경의
웹 S3 버킷 루트 `/.well-known/` 아래에 그대로 올라간다.

```
infra/well-known/
  prod/.well-known/assetlinks.json                  accentury.app 용 (릴리스 지문 1개)
  prod/.well-known/apple-app-site-association       accentury.app 용
  staging/.well-known/assetlinks.json               staging.accentury.app 용 (릴리스 + 디버그 지문)
  staging/.well-known/apple-app-site-association    staging.accentury.app 용 (prod와 내용 동일)
```

환경 디렉터리가 그 환경에 올라갈 것 전부를 들고 있다. AASA 두 벌이 글자까지 같은데도 복사본을
둔 이유는 게시 스크립트가 `infra/well-known/<env>/.well-known/`을 통째로 올리는 한 곳만
보게 하기 위해서다 — 심볼릭 링크나 "prod 것을 staging에도" 같은 예외를 두면, 나중에 두 환경의
경로가 갈라져야 할 때(예: staging만 `/t-beta`를 여는 경우) 그 예외부터 풀어야 한다.

**JSON에는 주석을 쓸 수 없다.** 그래서 왜 그 값인지는 전부 이 파일에 적는다.

## 파일이 하는 일

### `assetlinks.json` — 안드로이드 App Links

Digital Asset Links 선언문 목록이다. "이 도메인의 링크를 `com.accentury.app` 패키지가,
그것도 **이 인증서로 서명된** 빌드가 열어도 된다"는 뜻이다.

`AndroidManifest.xml`의 VIEW 필터에 `android:autoVerify="true"`가 붙어 있어서, 설치 시점에
OS가 각 host의 이 파일을 직접 받아 대조한다. 통과하면 링크가 곧바로 앱으로 가고, 실패하면
앱 선택 시트로 떨어진다 — 결선 자체가 깨지는 게 아니라 "자동으로 앱이 열리는" 부분만 사라진다.

- `relation` — `delegate_permission/common.handle_all_urls` 하나. URL 처리 권한 위임이다.
- `package_name` — `com.accentury.app`.
- `sha256_cert_fingerprints` — **앱 서명 인증서**의 SHA-256 지문. 배열이라 여러 개를 넣을 수 있고,
  그중 하나만 맞아도 검증은 통과한다.

### `apple-app-site-association` — iOS Universal Links

확장자가 없는 순수 JSON이다(애플이 정한 파일 이름이라 `.json`을 붙이면 안 된다). 앱 설치·업데이트
시점에 애플 CDN이 이 파일을 받아 앱에 내려 준다.

- `appIDs` — `<Team ID>.<Bundle ID>` = `559P9SYY57.com.accentury.app`.
- `components` — 경로 필터. iOS 13+ 형식이고 우리 최소 지원은 16이라 구형 `apps`/`paths` 키는
  쓰지 않는다. `{"/": "/t"}`와 `{"/": "/t/"}` 둘뿐이다.

**팀 ID가 이 파일에 그대로 박히는 것은 정상이다.** 서명 설정 위생 때문에
`ios/Accentury/Config/Local.xcconfig`의 팀 ID는 gitignore돼 있지만, AASA는 애초에 도메인이
전 세계에 공개하는 파일이다 — 팀 ID는 비밀이 아니라 공개 식별자이고, 애플의 형식이 이 값을 요구한다.

## 어긋나면 링크가 조용히 죽는 자리들

같은 사실(호스트 2개, 경로 `/t`·`/t/`)이 여러 파일에 흩어져 있다. 한쪽만 고치면 컴파일도
테스트도 통과하는데 링크만 안 열리므로, 각 짝을 테스트가 붙들고 있다.

| 무엇 | 어디 | 붙들고 있는 테스트 |
|---|---|---|
| 안드로이드 host·path·autoVerify | `app/src/main/AndroidManifest.xml` | `AppLinkTest` (매니페스트를 직접 읽어 `APP_LINK_ORIGINS`와 대조) |
| 안드로이드 진입 origin | `app/.../web/AppLink.kt`의 `APP_LINK_ORIGINS` | 위와 같음 |
| iOS host | `ios/Accentury/Accentury.entitlements` | `AppLinkTests` (entitlements를 직접 읽어 `appLinkOrigins`와 대조) |
| iOS 진입 origin | `ios/AccenturyCore/.../Web/AppLink.swift`의 `appLinkOrigins` | 위와 같음 |
| 진입 경로 `/t`·`/t/` | 양 플랫폼 `parseAppLink` | `AppLinkTest`·`AppLinkTests` |
| 이 디렉터리의 AASA 경로 집합 | `apple-app-site-association` | `infra/modules/edge/spa-rewrite.test.mjs` (매니페스트 `android:path`와 대조) |
| `/.well-known/` 리라이트 예외 | `infra/modules/edge/spa-rewrite.js` | 같은 테스트 |

마지막 두 줄이 이 티켓에서 새로 생긴 것이다. **SPA 리라이트 예외가 특히 조용한 함정이다** —
CloudFront Function이 "마지막 경로 조각에 점이 없으면 `/index.html`"로 돌리는데, AASA는 확장자가
없어서 예외를 빼 두지 않으면 애플 CDN이 JSON 대신 HTML을 받는다. `assetlinks.json`은 점이 있어
영향을 받지 않지만, `/.well-known/`은 RFC 8615가 기계가 읽는 메타데이터용으로 예약한 접두사라
그 아래는 통째로 리라이트에서 뺀다.

## 지문은 어디서 오는가

### 릴리스 지문 (prod·staging 공통)

`~/keys/accentury-release.jks`의 인증서 SHA-256이다 (KAN-163).
정본은 `docs/wiki/android-release-signing.md` §1의 표.

CI가 실제로 서명한 APK에서 다시 뽑는 쪽이 더 확실하다 —
`.github/workflows/app-release.yml`의 서명 스텝이 `apksigner verify --print-certs` 출력에서
지문을 뽑아 `sha256` 스텝 출력과 실행 요약에 남긴다. 로컬에서 같은 값을 보려면:

```bash
# 키스토어에서 직접
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
  -list -v -alias accentury -keystore ~/keys/accentury-release.jks | grep -i 'SHA256:'

# 이미 만들어진 APK에서 (apksigner는 콜론 없는 소문자 hex로 찍는다)
"$ANDROID_HOME/build-tools/<버전>/apksigner" verify --print-certs app-release.apk
```

> **Play App Signing 함정 (KAN-174).** 구글이 앱 서명 키를 새로 생성하는 쪽을 고르면, 스토어가
> 배포하는 APK는 **구글의 키**로 다시 서명된다. 그러면 여기 적을 지문은 이 키스토어의 것이 아니라
> Play Console › 설정 › 앱 서명(App integrity)에 표시된 **앱 서명 인증서**의 SHA-256이다.
> 갈아 두지 않으면 스토어에서 받은 앱만 딥링크 검증에 실패한다 — 로컬 릴리스 빌드로는 재현되지
> 않아서 원인을 찾기 어려운 부류다. 우리는 이 키스토어를 PEPK로 올려 지문이 그대로 유지되는 쪽을
> 권하고 있다 (`docs/wiki/android-release-signing.md` §6).

### 디버그 지문 (staging 전용)

이성주 맥의 `~/.android/debug.keystore` 인증서다. 안드로이드 SDK가 만드는 고정 비밀번호
키스토어(`android`)라 비밀이 아니다.

```bash
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
  -list -v -alias androiddebugkey -keystore ~/.android/debug.keystore -storepass android
```

**staging에만 넣는다.** 디버그 키는 그 맥에 있는 사람 누구나 그 키로 서명한 아무 APK를 만들 수
있다는 뜻이고, 그런 빌드가 prod 도메인의 링크를 자동으로 가져가면 곤란하다. staging은 팀이
릴리스 전에 링크 탭 흐름을 실제로 밟아 보라고 있는 환경이라 그 위험을 받아들인다.
팀원이 늘어 각자 기기에서 확인해야 하면 그 사람의 디버그 지문을 staging 배열에 **추가**한다
(배열이라 여러 개가 공존한다).

## 게시

```bash
scripts/publish-well-known.sh staging
scripts/publish-well-known.sh prod
```

S3에 올리고 CloudFront를 무효화한 뒤 도메인으로 실제 응답을 받아 로컬 파일과 바이트 비교까지
한다. 멱등이라 몇 번 돌려도 된다.

**웹 배포와 무관하게 이 파일들만 갈아 끼울 수 있다.** `web-deploy.yml`은 `aws s3 sync`를
`--delete` 없이 쓰고 배포 역할에는 `s3:DeleteObject` 자체가 없어서(`infra/modules/deploy/main.tf`),
여기서 올린 `.well-known/*` 객체는 이후의 웹 배포에 지워지지 않는다. 지문이나 팀 ID가 바뀌면
이 스크립트만 다시 돌리면 된다.

필요한 권한은 해당 버킷의 `s3:PutObject`와 배포의 `cloudfront:CreateInvalidation` 둘뿐이다.

## 게시 뒤 확인

### 안드로이드

```bash
# 1) 서빙 자체 - 200 + application/json
curl -sI https://accentury.app/.well-known/assetlinks.json

# 2) 구글의 검증기가 우리 선언을 읽어 내는지
curl -s 'https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://accentury.app&relation=delegate_permission/common.handle_all_urls'

# 3) 실제 기기·에뮬레이터의 판정. 설치 시점에 한 번 하므로 다시 시킨다.
adb shell pm verify-app-links --re-verify com.accentury.app
adb shell pm get-app-links com.accentury.app     # 각 host가 verified 여야 한다
```

`get-app-links`가 `verified` 대신 `1024` 같은 코드로 남으면 네트워크·캐시 문제이거나 지문이
안 맞는 것이다. 지문 불일치는 (2)에서 먼저 드러난다.

### iOS

```bash
# 1) HTML이 아니라 JSON이 오는가 (SPA 리라이트 예외가 살아 있는지의 진짜 검사)
curl -s https://accentury.app/.well-known/apple-app-site-association

# 2) 애플 CDN이 받아 간 사본. 갱신에 시간이 걸릴 수 있다.
curl -s https://app-site-association.cdn-apple.com/a/v1/accentury.app
```

기기에서는 설정 › 개발자 › Universal Links › Diagnostics에 도메인을 넣어 본다. 앱은 AASA를
**설치·업데이트 시점에** 받으므로, 파일을 고친 뒤에는 앱을 지웠다 다시 깔아야 새 내용이 반영된다.
