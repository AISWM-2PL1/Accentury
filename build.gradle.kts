// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // 여기서는 클래스패스에만 올린다 (KAN-33). 실제 apply는 :app이 google-services.json 존재를
    // 확인한 뒤에 한다 - 설정 파일이 없으면 이 두 플러그인이 설정 단계에서 빌드를 죽인다.
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}
