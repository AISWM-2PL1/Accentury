import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"${kakaoNativeAppKey()}\"")
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
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // 결과 공유 (KAN-30). 피드 템플릿 공유만 쓰므로 v2-share 하나다 - 카카오 로그인(v2-user)은
    // 우리 인증에 없다.
    implementation(libs.kakao.share)
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