pluginManagement {
  // 공통 빌드 규칙(컨벤션 플러그인)
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories { mavenCentral() }
}

rootProject.name = "task-20261104"

// 서비스는 서로 project(...) 의존을 만들지 않는다(build-logic이 빌드를 실패시킨다).
include(":member-service", ":group-service")

project(":member-service").projectDir = file("services/member-service")

project(":group-service").projectDir = file("services/group-service")
