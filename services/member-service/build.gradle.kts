// 멤버 서비스. 공통 규칙은 build-logic의 task.spring-service. 다른 서비스와 project 의존을 두지 않는다.
plugins { id("task.spring-service") }

dependencies {
  implementation(libs.spring.boot.starter.webmvc)
  implementation(libs.spring.boot.starter.validation)
  implementation(libs.spring.boot.starter.actuator)
  implementation(libs.spring.boot.starter.data.jpa)
  implementation(libs.spring.boot.starter.flyway)
  implementation(libs.spring.boot.starter.security.oauth2.resource.server)
  implementation(libs.springdoc.openapi.webmvc.ui)
  implementation(libs.jackson.module.kotlin)
  implementation(libs.kotlin.reflect)
  runtimeOnly(libs.flyway.database.postgresql)
  runtimeOnly(libs.postgresql)

  testImplementation(libs.spring.boot.starter.webmvc.test)
  testImplementation(libs.spring.boot.starter.security.test)
  testImplementation(libs.spring.boot.testcontainers)
  testImplementation(libs.testcontainers.junit.jupiter)
  testImplementation(libs.testcontainers.postgresql)
  testImplementation(libs.kotlin.test.junit5)
  testRuntimeOnly(libs.junit.platform.launcher)
}
