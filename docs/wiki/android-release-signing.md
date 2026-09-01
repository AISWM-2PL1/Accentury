# 안드로이드 릴리스 서명과 빌드 (KAN-163)

Release 브랜치에 앱 코드가 들어가면 GitHub Actions가 **서명된 AAB·APK**를 만들어 아티팩트로 남긴다.
스토어 업로드만 아직 사람이 한다.

왜 필요한가. 서명 없는 APK는 Play Console이 받지 않는다 — 스토어는 "같은 키로 서명됐는가"만 보고
업데이트를 받아들인다. 그리고 카카오 앱 키가 빈 채로 나간 빌드는 **공유 기능이 죽은 채 배포된다**.
둘 다 빌드는 "성공"으로 끝나고 문제는 업로드나 사용자 손에서야 드러나는 종류라, 워크플로가 그 두
상태를 먼저 끊는다.

## 1. 키스토어

| 항목 | 값 |
|---|---|
| 경로 | `~/keys/accentury-release.jks` (이성주의 맥, 레포 밖) |
| 담당 | 이성주 |
| 형식 | PKCS12, alias `accentury`, RSA 2048, validity 10000일 |
| 비밀번호 | 이성주가 보유 → 팀 비밀번호 관리자에 등록 |

만든 방법은 `scripts/make-release-keystore.sh ~/keys/accentury-release.jks`다. 비밀번호를 인자로도
환경변수로도 받지 않고 keytool이 직접 묻게 둔다 — 인자는 셸 히스토리와 `ps`에 남고 환경변수는
자식 프로세스 전부에 상속된다. 스크립트는 파일이 이미 있으면 **덮지 않고 멈춘다.**

이 키스토어 하나에서 두 값이 나오고, 그 둘이 앱 정체성의 뿌리다.

| 파생값 | 값 | 쓰이는 곳 |
|---|---|---|
| 인증서 SHA-256 지문 | `48:C1:0D:86:23:E2:69:1A:37:5A:9B:03:82:2F:4F:84:6C:CC:01:69:25:E8:12:F6:CD:9D:94:8C:48:E3:FB:F2` | App Links `assetlinks.json`의 `sha256_cert_fingerprints` (KAN-32) |
| 카카오 키 해시 `base64(sha1(cert))` | `mbUry3g8iSvbM3Ok5NlJ1ZYUIIo=` | 카카오 개발자 콘솔 (KAN-30) |

둘 다 비밀이 아니다 — 지문은 `assetlinks.json`으로 공개되는 값이고 키 해시도 콘솔 등록용이다.
**키스토어를 바꾸면 이 두 곳을 함께 갱신하지 않는 한 딥링크 검증과 카카오 공유가 동시에 깨진다.**

> **분실 = 영구 업데이트 불가.** 키스토어나 비밀번호를 잃으면 이미 올라간 앱의 업데이트를 영원히
> 올릴 수 없다. 패키지명을 바꿔 새 앱으로 다시 시작하는 것 말고는 방법이 없고, 설치 기반과 리뷰는
> 그대로 버려진다. 그래서 규칙 하나: **기존 키스토어를 덮어쓰지 않는다.**
> (Play App Signing에서 이 키를 **업로드 키**로만 쓰게 되면 분실해도 재발급 경로가 있다. 다만 앱 서명
> 키로 올리는 쪽을 권하고 있어서 — §6 — 지금은 복구 경로가 없는 것으로 다룬다.)

## 2. 빌드 설정 (`app/build.gradle.kts`)

`releaseSigning()`이 서명 재료를 **환경변수 → `local.properties`** 순으로 읽는다.

| 환경변수 | `local.properties` |
|---|---|
| `RELEASE_KEYSTORE_PATH` | `releaseKeystorePath` |
| `RELEASE_KEYSTORE_PASSWORD` | `releaseKeystorePassword` |
| `RELEASE_KEY_ALIAS` | `releaseKeyAlias` |
| `RELEASE_KEY_PASSWORD` | `releaseKeyPassword` |

**gradle 프로퍼티(`-P`) 단계가 일부러 없다.** 명령줄로 넘긴 값은 CI 로그의 실행 명령에 그대로 남고
같은 머신의 다른 프로세스도 `ps`로 읽는다. 카카오 키는 어차피 APK에 박히는 값이라 `-P` 경로를
뒀지만, 키스토어 비밀번호는 유출되면 서명 키 자체를 잃는 값이라 그 경로를 만들지 않았다.

세 가지 상태로 갈린다.

| 재료 | 결과 | 왜 |
|---|---|---|
| 없음 | `app-release-unsigned.apk` (성공) | 시크릿을 모르는 로컬 확인과 PR CI의 릴리스 컴파일도 돌아야 한다 |
| 반쯤 (경로만, 또는 파일 없음) | 설정 단계에서 실패 | 조용히 미서명으로 넘어가면 "빌드 성공"이 스토어에 못 올릴 파일이 되고, 그 사실은 업로드까지 가서야 드러난다 |
| 전부 | 서명된 `app-release.apk` + `.aab` | |

`requireKakaoNativeAppKey` 빗장은 별개다. `-PrequireKakaoNativeAppKey=true`면 카카오 키가 비었을 때
설정 단계에서 죽인다. 평소에는 꺼 두고(기본 false) **릴리스 워크플로만 켠다** — "빈 키가 정상"은
로컬·PR CI 이야기지 스토어로 나가는 빌드 이야기가 아니다. 이건 비밀이 아니라 스위치라 `-P`로 준다.

로컬에서 서명 빌드를 해 보려면 `local.properties`(gitignore 대상)에:

```properties
releaseKeystorePath=/Users/<사용자>/keys/accentury-release.jks
releaseKeystorePassword=...
releaseKeyAlias=accentury
releaseKeyPassword=...
kakaoNativeAppKey=...
```

그 다음 `./gradlew :app:assembleRelease` (JAVA_HOME은 Android Studio JBR).

## 3. 릴리스 워크플로 (`.github/workflows/app-release.yml`)

트리거는 **`Release` 브랜치 푸시**(앱 관련 `paths`만) + **수동 실행**이다. 웹·백엔드만 바뀐 Release
병합까지 앱을 다시 빌드하면 올리지 않을 산출물이 쌓이고 서명 시크릿을 쓸데없이 자주 꺼낸다.
겹친 실행은 취소하지 않고 기다린다 — 같은 커밋을 두 번 서명해도 해로울 것이 없고, 끊긴 실행이
남기는 반쯤 만들어진 아티팩트가 더 헷갈린다.

`deploy.yml`과 한 파일에 섞지 않은 이유: 저쪽은 검증-승격 모델(같은 SHA를 staging → prod)인데 앱은
승격할 대상이 없다 — 산출물이 Play Console로 가고 그 경로는 우리 파이프라인 밖이다.

12스텝: ① checkout ② JDK 21 (`test.yml`의 app-test와 같은 값 — 갈리면 PR에서 통과한 빌드가 여기서
깨진다) ③ setup-gradle ④ 짧은 SHA ⑤ **시크릿 존재 검사**(`-z`로 이름만, 20분 빌드를 태우기 전에)
⑥ 키스토어를 `$RUNNER_TEMP`에 base64 디코딩(체크아웃 밖 — 레포 안에 두면 아티팩트에 딸려 나갈 수
있다) ⑦ `:app:bundleRelease :app:assembleRelease` 한 번 호출 ⑧ **apksigner verify** + 지문 추출
⑨ 카카오 키 재확인(생성된 `BuildConfig` grep + 서명된 APK의 DEX 검사) ⑩ 아티팩트 업로드 ⑪ 매핑 업로드(있으면) ⑫ 실행 요약.

시크릿은 저장소 시크릿 4개다.

| 이름 | 무엇 | 등록자 |
|---|---|---|
| `RELEASE_KEYSTORE_BASE64` | `.jks`를 base64 한 줄로 | 박유현 |
| `RELEASE_KEYSTORE_PASSWORD` | 키스토어 비밀번호 | 박유현 |
| `RELEASE_KEY_PASSWORD` | 키 비밀번호 | 박유현 |
| `KAKAO_NATIVE_APP_KEY` | 카카오 네이티브 앱 키 (KAN-30) | 박유현 |

**키 alias(`accentury`)는 시크릿이 아니라 워크플로 상수다** — `app-release.yml`의 릴리스 빌드 스텝에
`RELEASE_KEY_ALIAS: accentury`로 박혀 있다. 시크릿으로 두면 GitHub가 실행 로그에서 그 문자열을 전부
가려 패키지명이 `com.***.app`, 아티팩트 이름이 `***-app-release-<sha7>`로 나온다 — 1차 실행에서 실제로
그렇게 됐고, 그 상태로는 로그를 읽을 수가 없다. alias는 애초에 비밀이 아니다(스크립트 기본값이고 이
문서에도 적혀 있다). 키스토어를 다른 alias로 만들면 그 상수를 함께 바꾼다. 이미 등록된
`RELEASE_KEY_ALIAS` 시크릿은 이제 아무 데서도 참조하지 않으므로 지워도 된다(참조되지 않는 시크릿은
러너로 넘어가지도, 로그에서 가려지지도 않는다 — 남겨 둬도 마스킹은 다시 생기지 않는다).

값을 뽑는 명령은 `scripts/make-release-keystore.sh`가 생성 직후 출력한다. base64 변환은 출력이 아니라
**명령만** 안내한다 — 스크롤백에 남기지 않으려고.

스텝 셸은 잡 레벨 `defaults.run.shell: bash`로 고정했다. `shell:`을 비우면 GitHub는 `bash -e {0}`로만
돌려 **pipefail이 꺼진 채**다(1차 실행 로그의 `shell: /usr/bin/bash -e {0}`). 명시하면
`bash --noprofile --norc -eo pipefail {0}`이 되어 base64 디코딩 실패처럼 파이프 중간에서 죽는 경우도
스텝을 멈춘다. 워크플로의 `|| true`·`grep -c` 같은 방어 코드는 그 전제에서 맞춘 것이다.

산출물은 아티팩트 `accentury-app-release-<sha7>`(AAB + APK, 90일 보관)이고 매핑은 별도 아티팩트다.
실행 요약에는 커밋 SHA·ref·트리거·버전(`output-metadata.json`에서 읽은 실제 값)·APK/AAB 크기·**인증서
SHA-256 지문**이 표로 뜬다. 지문을 실행마다 남기는 이유는 키스토어가 바뀐 것을 그 자리에서 알아채기
위해서다.

**스토어 업로드(수동)**: 실행 페이지 → 아티팩트 내려받기 → `app-release.aab`를 Play Console에 업로드.

## 4. 카카오 콘솔 (KAN-30)

2025년 콘솔 개편으로 메뉴가 바뀌었다. 예전 문서의 "플랫폼 › Android", "카카오톡 공유 활성화" 토글은
**더 이상 없다.** 현재 경로:

- 키 해시·패키지명: [앱] › [플랫폼 키] › [네이티브 앱 키] › [Android 앱 정보]
- 웹 도메인: [앱] › [제품 링크 관리] › [웹 도메인]

등록 상태: 패키지 `com.accentury.app`, 디버그 키 해시 `TTKS+ESuXtPKhL8TBuRqaFc/+QI=`(이성주의 맥),
릴리스 키 해시 `mbUry3g8iSvbM3Ok5NlJ1ZYUIIo=`, 웹 도메인 `https://accentury.app`(미등록이면 카드
버튼 링크가 차단된다).

**앱 키는 지금 하나다** — staging/prod를 나누지 않았다. 나눌 대상이 없어서다: staging을 보는 앱
빌드가 아직 없고 릴리스의 `WEB_URL`은 prod다(`app/build.gradle.kts`). staging 플레이버가 생기면
그때 나눈다 — 콘솔은 환경당 키를 5개까지 준다.

## 5. 확인 절차 (AC)

| AC | 내용 | 어떻게 확인하는가 |
|---|---|---|
| AC1 | 서명된 산출물 | 워크플로의 `apksigner verify --verbose`. 미서명이면 파일명이 `app-release-unsigned.apk`라 스텝이 실패한다. 지문은 실행 요약에 기록 |
| AC2 | 카카오 키 주입 | 빗장 셋 — 빌드 시점 `-PrequireKakaoNativeAppKey=true`(설정 단계), 빌드 뒤 생성된 `BuildConfig.java` grep, 서명된 APK의 DEX에 그 키가 들어갔는지 검사(앞의 둘은 빌드 입력이고 이것만 산출물 자체다) |
| AC3 | 실기기 카카오 공유 | **미확인.** 아티팩트의 릴리스 APK를 실기기에 설치 → 결과 화면 [친구에게 공유하기] → 카톡 친구 선택 화면이 뜨는지 |
| AC4 | 로그에 시크릿 없음 | 값을 `echo`하는 곳이 없다. 존재 검사는 `-z`로만, 실패 메시지에도 이름만. base64 디코딩은 명령줄 인자가 아니라 환경변수를 파이프로 넘긴다 |

AC1·AC2·AC4는 로컬 시뮬레이션(임시 키스토어)으로 전 스텝을 태워 확인했고, 빈 키·시크릿 누락·키스토어
없음 세 부정 케이스가 각각 의도한 메시지로 실패하는 것까지 봤다. AC3만 실기기에 릴리스 APK를 올려야
닫힌다.

## 6. 이월

- **Play App Signing — 앱 서명 키를 무엇으로 할지 (KAN-174).** 2021년 8월 이후 Play에 새로 올리는
  앱은 Play App Signing이 **필수**라 "켤지 말지"는 고를 수 없다. 고를 수 있는 것은 앱 서명 키를
  어디서 오게 하느냐다.

  | 선택 | 앱 서명 키 | 지문·키 해시 |
  |---|---|---|
  | 구글이 생성 | 구글이 새로 만든 키 | **바뀐다** — `assetlinks.json`(KAN-32)과 카카오 콘솔을 구글 쪽 인증서 지문으로 갈아야 한다 |
  | 이 키스토어를 업로드 (PEPK) | `~/keys/accentury-release.jks`의 키 | **그대로** — §1에 적힌 지문과 키 해시가 계속 맞는다 |

  **이 키스토어를 앱 서명 키로 올리는 쪽을 권한다.** KAN-32의 `assetlinks.json`과 카카오 콘솔(KAN-30)에
  이미 §1의 값이 들어가 있어서, 그대로 두면 등록 때 갈아야 할 곳이 없다. 올리는 방법은 Play Console이
  안내하는 PEPK 도구로 키를 내보내 등록하는 것이다.

  **함정은 구글이 키를 생성하는 쪽에만 붙는다.** 그 경우 `assetlinks.json`과 카카오 콘솔에 넣을 지문은
  이 키스토어의 지문이 아니라 **구글이 들고 있는 앱 서명 인증서의 지문**(Play Console › 설정 › 앱 서명)이고,
  등록하는 사람이 두 곳을 함께 갈지 않으면 딥링크 검증과 카카오 공유가 동시에 깨진다.

  어느 쪽을 고르든 **업로드 키**는 따로 만들어도 되고 이 키스토어를 그대로 써도 된다. 업로드 키는
  잃어도 구글에 재발급을 요청할 수 있다 — 앱 서명 키와 달리 복구 경로가 있는 쪽이다.
- **`versionCode`.** `app/build.gradle.kts`에 `1`로 박혀 있다. 스토어 릴리스마다 올려야 하는데 지금은
  수동이고 규칙도 없다.
- **R8 축소.** release가 `optimization { enable = false }`라 R8이 돌지 않는다(카카오 SDK가 retrofit·
  moshi를 끌고 와 APK가 커진 상태). 켜면 매핑 파일이 생기고, 워크플로의 매핑 업로드 스텝은 이미
  그때를 대비해 결선돼 있다.
- **스토어 업로드 자동화.** 서비스 계정 + 트랙 승격은 이 티켓 범위 밖이다.

## 참고

- `app/build.gradle.kts` — `releaseSigning()`, `requireKakaoNativeAppKey()`
- `scripts/make-release-keystore.sh` — 키스토어 생성과 지문·키 해시 출력
- `.github/workflows/app-release.yml` — 워크플로 (헤더 주석에 판단 근거)
- 카카오 공유 설계 전반 — 리서치 레포 `docs/wiki/webview-layer.md` §13
