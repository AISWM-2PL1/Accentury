plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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

        // 인트로 웹 원격 로드 주소 (webview-layer.md §3 원격 전용).
        // CloudFront 배포 확정 시 실제 도메인으로 교체한다 (§10 열린 질문 1).
        buildConfigField("String", "WEB_URL", "\"https://web.accentury.example\"")
    }

    buildTypes {
        debug {
            // 에뮬레이터에서 호스트의 Vite dev 서버를 가리킨다 (web/에서 npm run dev).
            // 10.0.2.2 평문 허용은 network_security_config.xml에 이미 있다.
            buildConfigField("String", "WEB_URL", "\"http://10.0.2.2:5173\"")
            // 에뮬레이터 마이크가 무음만 주는 환경에서 assets의 WAV를 마이크 대신 끼운다.
            // 예: ./gradlew :app:installDebug -PfakeMic=fake_mic.wav (audio/PcmSources.kt).
            buildConfigField(
                "String",
                "FAKE_MIC_ASSET",
                "\"${project.findProperty("fakeMic") ?: ""}\"",
            )
        }
        release {
            // 코드가 이 필드를 참조하므로 릴리스에도 있어야 한다. 상수 ""라 파일 재생 경로는 죽는다.
            buildConfigField("String", "FAKE_MIC_ASSET", "\"\"")
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