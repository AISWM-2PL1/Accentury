import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/*
 * 계측·크래시 플러그인은 google-services.json이 있을 때만 건다 (KAN-33).
 *
 * **없는 것이 정상 상태다.** [kakaoNativeAppKey]·[releaseSigning]과 같은 판단이다 - 콘솔 프로젝트가
 * 생기기 전의 로컬 빌드와 PR CI도 그대로 돌아야 한다. 다만 저 둘과 갈리는 지점이 있다: 카카오 키와
 * 키스토어는 빈 값을 코드가 받아 넘길 수 있지만, 이 두 플러그인은 설정 파일이 없으면 **설정 단계에서
 * 빌드를 죽인다.** 값이 아니라 플러그인 적용 자체를 조건에 거는 이유가 그것이다.
 *
 * 의존성(BoM·analytics·crashlytics)은 조건 없이 넣는다. 설정이 없으면 FirebaseApp이 초기화되지
 * 않을 뿐이고, 그 사실은 EventSink.create가 한 곳에서 판정해 Logcat sink로 내려간다
 * (analytics/FirebaseEventSink.kt). 의존성까지 조건부로 하면 설정 유무에 따라 컴파일되는 소스가
 * 갈려서, 설정이 없는 CI가 검증한 코드와 스토어로 나가는 코드가 달라진다.
 *
 * google-services.json은 커밋 대상이다 - 카카오 키와 갈리는 지점이다. 저 값은 우리 앱 키로 남이
 * 카카오 API를 두드릴 수 있지만, 이 파일의 앱 id·API 키는 패키지명과 서명 지문에 묶여 있어 다른
 * 앱이 가져다 쓸 수 없다. 콘솔에서 받아 app/에 넣고 커밋하면 이 분기가 켜진다.
 */
val firebaseConfig = file("google-services.json")
if (firebaseConfig.exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    apply(plugin = libs.plugins.firebase.crashlytics.get().pluginId)
}

/**
 * 가짜 마이크 asset 이름. `-PfakeMic=` 프로퍼티가 우선이고, 없으면 local.properties(gitignore 대상)의
 * `fakeMic=`을 본다 - Android Studio의 Run 버튼은 gradle 프로퍼티를 넘길 수 없어서다. 둘 다 없으면 "".
 */
fun fakeMicAsset(): String {
    (project.findProperty("fakeMic") as String?)?.let { return it }
    val local = rootProject.file("local.properties")
    if (!local.exists()) return ""
    val props = Properties()
    local.inputStream().use { props.load(it) }
    return props.getProperty("fakeMic") ?: ""
}

/**
 * 카카오 네이티브 앱 키 (KAN-30). `-PkakaoNativeAppKey=` → 환경변수 `KAKAO_NATIVE_APP_KEY` →
 * local.properties의 `kakaoNativeAppKey=` 순으로 본다. 셋 다 없으면 "".
 *
 * 환경변수 단계가 [fakeMicAsset]에 없고 여기에만 있는 이유: 이 값은 CI가 시크릿 저장소에서
 * 주입하는 값이고, gradle 프로퍼티로 넘기면 CI 로그의 명령줄에 그대로 남는다.
 *
 * **빈 값이 정상 상태다.** 키가 없으면 앱은 카카오 SDK를 초기화하지 않고 공유가 OS 공유 시트로만
 * 간다 (AccenturyApplication, ResultSharer) - 키를 모르는 로컬·CI 빌드도 빌드되고 동작해야 해서다.
 * 키 자체는 APK에 박히는 값이라 비밀은 아니지만, 레포에 넣으면 우리 앱 키로 다른 앱이 카카오 API를
 * 두드릴 수 있어(도메인·해시 등록으로 막는 것은 로그인 쪽이다) 커밋 대상에서 뺀다.
 * 로컬 예시: local.properties에 `kakaoNativeAppKey=0123456789abcdef...` (gitignore 대상).
 */
fun kakaoNativeAppKey(): String {
    (project.findProperty("kakaoNativeAppKey") as String?)?.let { return it }
    System.getenv("KAKAO_NATIVE_APP_KEY")?.let { return it }
    val local = rootProject.file("local.properties")
    if (!local.exists()) return ""
    val props = Properties()
    local.inputStream().use { props.load(it) }
    return props.getProperty("kakaoNativeAppKey") ?: ""
}

/**
 * 디버그 빌드가 열 웹·API 출처 오버라이드 (실기기 확인용, KAN-30). `-PwebUrl=` → local.properties의
 * `webUrl=` 순. 없으면 에뮬레이터 기본값(10.0.2.2)으로 두 값이 각각 갈린다.
 *
 * 실기기는 10.0.2.2를 모른다. 대신 `cloudflared tunnel --url http://localhost:5173`의 HTTPS 주소를
 * 여기에 주면 WebView가 그 주소를 열고, 네이티브의 세션·업로드 호출도 **같은 출처**로 나간다 -
 * Vite dev 서버가 `/v0`를 8080으로 프록시하므로(web/vite.config.ts) 배포와 같은 단일 출처 구성이다
 * (webview-layer.md §12.7). 예: `./gradlew :app:installDebug -PwebUrl=https://xxx.trycloudflare.com`
 */
fun debugWebUrlOverride(): String? {
    (project.findProperty("webUrl") as String?)?.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
    val local = rootProject.file("local.properties")
    if (!local.exists()) return null
    val props = Properties()
    local.inputStream().use { props.load(it) }
    return props.getProperty("webUrl")?.takeIf { it.isNotBlank() }?.trimEnd('/')
}

/**
 * 릴리스 서명 재료 (KAN-163). 환경변수 → local.properties 순으로 본다.
 *
 *   환경변수  RELEASE_KEYSTORE_PATH / RELEASE_KEYSTORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
 *   local     releaseKeystorePath  / releaseKeystorePassword  / releaseKeyAlias  / releaseKeyPassword
 *
 * [kakaoNativeAppKey]와 달리 **gradle 프로퍼티(-P) 단계가 없다.** 명령줄로 넘긴 값은 CI 로그의
 * 실행 명령에 그대로 남고 같은 머신의 다른 프로세스도 `ps`로 읽는다. 카카오 키는 어차피 APK에
 * 박히는 값이라 그 위험이 크지 않았지만 키스토어 비밀번호는 유출되면 서명 키 자체를 잃는 값이라
 * 명령줄로 받는 경로를 아예 만들지 않는다. CI는 환경변수로, 사람은 local.properties로만 준다.
 *
 * **없는 것이 정상 상태다.** 경로가 없으면 null을 돌려주고 릴리스 빌드는 서명 없이 나간다
 * (`app-release-unsigned.apk`) - 시크릿을 모르는 로컬 확인과 PR CI의 릴리스 컴파일도 그대로
 * 돌아가야 해서다. [kakaoNativeAppKey]의 "빈 값이 정상"과 같은 판단이다.
 *
 * 다만 **반쯤 설정된 상태는 실패시킨다.** 경로는 줬는데 파일이 없거나 비밀번호·alias가 비었으면
 * 설정 단계에서 죽인다. 이 경우 조용히 미서명 APK를 뱉으면 릴리스 파이프라인이 "빌드 성공"으로
 * 보고한 산출물이 스토어에 올릴 수 없는 파일이 되고, 그 사실은 업로드 단계까지 가서야 드러난다.
 */
data class ReleaseSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun releaseSigning(): ReleaseSigning? {
    val local = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { local.load(it) }
    }
    // 빈 문자열은 "주지 않은 것"으로 본다. CI에서 시크릿이 등록되지 않은 채 워크플로가 돌면
    // 환경변수가 ""로 들어오는데, 그걸 값으로 받으면 아래 파일 존재 검사에서 엉뚱한 메시지가 난다.
    fun value(env: String, localKey: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() }
            ?: local.getProperty(localKey)?.takeIf { it.isNotBlank() }

    val path = value("RELEASE_KEYSTORE_PATH", "releaseKeystorePath") ?: return null

    // 절대 경로를 권장한다. CI가 시크릿(base64)을 풀어 놓는 위치는 레포 밖 임시 디렉터리다.
    // 상대 경로를 준 경우에만 레포 루트 기준으로 푼다.
    val storeFile = File(path).let { if (it.isAbsolute) it else rootProject.file(path) }
    if (!storeFile.isFile) {
        error(
            "릴리스 키스토어를 찾을 수 없다 (KAN-163): $storeFile\n" +
                "  RELEASE_KEYSTORE_PATH(또는 local.properties의 releaseKeystorePath)가 가리키는 파일이 없다.\n" +
                "  서명 없이 빌드하려면 그 값을 아예 비워라 - 그러면 app-release-unsigned.apk가 나온다.",
        )
    }

    fun required(env: String, localKey: String): String =
        value(env, localKey) ?: error(
            "릴리스 키스토어 경로는 있는데 $env (또는 local.properties의 $localKey)가 비어 있다 (KAN-163).\n" +
                "  반쯤 설정된 서명은 미서명 APK로 조용히 넘어가지 않고 여기서 실패시킨다.",
        )

    return ReleaseSigning(
        storeFile = storeFile,
        storePassword = required("RELEASE_KEYSTORE_PASSWORD", "releaseKeystorePassword"),
        keyAlias = required("RELEASE_KEY_ALIAS", "releaseKeyAlias"),
        keyPassword = required("RELEASE_KEY_PASSWORD", "releaseKeyPassword"),
    )
}

/**
 * 릴리스 산출물이 빈 카카오 키로 나가는 것을 막는 빗장 (KAN-163). `-PrequireKakaoNativeAppKey=true`.
 *
 * [kakaoNativeAppKey]의 "빈 값이 정상"은 로컬·PR CI 이야기고, 스토어로 나가는 빌드에서는 키가
 * 빠진 것이 곧 공유 기능이 죽은 채 배포되는 것이다. 그래서 평소에는 끄고(기본 false) 릴리스
 * 워크플로만 이 플래그를 켜서 설정 단계에서 잡는다.
 *
 * 이건 명령줄(-P)로 받아도 된다. 비밀이 아니라 스위치라 로그에 남아도 잃을 것이 없다.
 * 값 없이 `-PrequireKakaoNativeAppKey`만 줘도 켜진 것으로 본다 - gradle이 ""를 넘겨서다.
 */
fun requireKakaoNativeAppKey(): Boolean {
    val raw = project.findProperty("requireKakaoNativeAppKey") as String? ?: return false
    return raw.isEmpty() || raw.toBoolean()
}

android {
    namespace = "com.accentury.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.accentury.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 인트로 웹 원격 로드 주소 (webview-layer.md §3 원격 전용). release가 이 값을 쓴다.
        // prod 도메인이다 - 화면과 API가 같은 출처(CloudFront 단일 출처, KAN-126)라 경로 없이
        // 루트다. 웹 번들은 Release 병합 시 KAN-127 파이프라인이 이 도메인의 버킷에 올린다.
        // staging(staging.accentury.app)을 보는 앱 빌드는 아직 없다 - 필요해지면 빌드 타입이나
        // 플레이버로 추가한다.
        buildConfigField("String", "WEB_URL", "\"https://accentury.app\"")
        // 네이티브(세션·업로드)의 API 출처. 화면과 API가 같은 CloudFront 출처라(KAN-126) WEB_URL과
        // 같은 값이다. debug는 아래에서 에뮬레이터 주소로 덮는다.
        buildConfigField("String", "API_BASE_URL", "\"https://accentury.app\"")

        // 결과 공유의 카카오 경로 스위치 (KAN-30). debug/release가 같은 값을 쓰므로 여기 둔다 -
        // 카카오 앱 키는 빌드 타입이 아니라 "주입됐는가"로 갈리는 값이다 (kakaoNativeAppKey 주석).
        val kakaoKey = kakaoNativeAppKey()
        if (kakaoKey.isBlank() && requireKakaoNativeAppKey()) {
            error(
                "카카오 네이티브 앱 키가 비어 있다 (KAN-163, -PrequireKakaoNativeAppKey=true).\n" +
                    "  릴리스 산출물은 빈 키로 나갈 수 없다 - 이 상태로 배포하면 결과 공유가 OS 공유 시트로만 간다.\n" +
                    "  CI라면 KAKAO_NATIVE_APP_KEY 시크릿이 등록됐는지, 로컬이라면 local.properties의\n" +
                    "  kakaoNativeAppKey= 값이 있는지 확인해라.",
            )
        }
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"$kakaoKey\"")
    }

    // 릴리스 서명 (KAN-163). 재료가 없으면 signingConfigs에 "release"를 아예 만들지 않고,
    // 아래 release 블록의 signingConfig도 null로 남아 미서명 APK가 나온다.
    val releaseSigningMaterial = releaseSigning()
    signingConfigs {
        if (releaseSigningMaterial != null) {
            create("release") {
                storeFile = releaseSigningMaterial.storeFile
                storePassword = releaseSigningMaterial.storePassword
                keyAlias = releaseSigningMaterial.keyAlias
                keyPassword = releaseSigningMaterial.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            // 에뮬레이터에서 호스트의 Vite dev 서버를 가리킨다 (web/에서 npm run dev).
            // 10.0.2.2 평문 허용은 network_security_config.xml에 이미 있다.
            // 실기기 확인은 debugWebUrlOverride()로 HTTPS 터널 주소를 주면 웹·API가 같은 출처로 간다.
            val override = debugWebUrlOverride()
            buildConfigField("String", "WEB_URL", "\"${override ?: "http://10.0.2.2:5173"}\"")
            buildConfigField("String", "API_BASE_URL", "\"${override ?: "http://10.0.2.2:8080"}\"")
            // 에뮬레이터 마이크가 무음만 주는 환경에서 assets의 WAV를 마이크 대신 끼운다.
            // 예: ./gradlew :app:installDebug -PfakeMic=fake_mic.wav (audio/PcmSources.kt).
            // Android Studio Run은 프로퍼티를 못 받으므로 local.properties의 fakeMic=도 읽는다.
            buildConfigField("String", "FAKE_MIC_ASSET", "\"${fakeMicAsset()}\"")
        }
        release {
            // 이 키스토어 하나가 앱 정체성의 뿌리다 (KAN-163). 여기서 나오는 인증서 지문이
            // App Links(KAN-32)의 assetlinks.json에 박히는 SHA-256이고, 카카오 콘솔에 등록하는
            // 키 해시(base64(sha1(cert)))도 같은 인증서에서 나온다. 키스토어를 바꾸면 그 두 곳을
            // 함께 갱신하지 않는 한 딥링크 검증과 카카오 공유가 동시에 깨진다.
            // 지문 뽑는 법은 scripts/make-release-keystore.sh가 출력한다.
            signingConfig = signingConfigs.findByName("release")

            // FAKE_MIC_ASSET은 여기에 없다. 이 필드를 읽는 코드가 src/debug에만 있어서다
            // (audio/PcmSources.kt) - 릴리스에는 파일 재생 경로가 상수 ""로 죽어 있는 게
            // 아니라 아예 존재하지 않는다.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    // 스플래시 화면 (KAN-178). minSdk 29라 플랫폼 SplashScreen(API 31)만으로는 29·30에서
    // 스플래시가 아예 없다 - 이 라이브러리가 그 두 버전에 같은 화면을 만들어 준다.
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // 결과 공유 (KAN-30). 피드 템플릿 공유만 쓰므로 v2-share 하나다 - 카카오 로그인(v2-user)은
    // 우리 인증에 없다.
    implementation(libs.kakao.share)
    // 익명 계측·크래시 (KAN-33). 위 조건부 apply와 달리 의존성은 늘 붙는다 - 설정이 없으면
    // FirebaseApp이 초기화되지 않고 sink가 만들어지지 않을 뿐이다 (analytics/FirebaseEventSink.kt).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}