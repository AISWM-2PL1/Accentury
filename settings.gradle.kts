pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 카카오 SDK는 mavenCentral에 없다 (KAN-30). FAIL_ON_PROJECT_REPOS라 모듈 build.gradle이
        // 아니라 여기서만 선언할 수 있다.
        // 그룹 필터를 거는 이유: 저장소는 선언 순서대로 조회되므로 필터가 없으면 이 사설 저장소가
        // 모든 의존성의 조회 경로에 끼어든다 - 느려지는 것도 문제지만, 우리 의존성 이름이 이쪽에서
        // 먼저 해석될 여지를 남기지 않는다.
        maven {
            url = uri("https://devrepo.kakao.com/nexus/content/groups/public/")
            content { includeGroup("com.kakao.sdk") }
        }
    }
}

rootProject.name = "accentury"
include(":app")
 