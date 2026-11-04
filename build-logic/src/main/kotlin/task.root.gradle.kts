// 루트 규칙: 빌드 스크립트·build-logic 포맷 검사, 로컬 비밀값(인증키·토큰·DB 비밀번호) 생성 작업.
// 포맷 대상에 ai-logs/·scripts/는 넣지 않는다(AI 세션 로그와 로깅 도구는 포맷 작업이 바꾸지 않는다).
import task.buildlogic.LocalSecretsTask

plugins { id("com.diffplug.spotless") }

val catalog = the<VersionCatalogsExtension>().named("libs")
val ktfmtVersion: String = catalog.findVersion("ktfmt").get().requiredVersion

spotless {
  kotlinGradle {
    target("*.gradle.kts", "build-logic/*.gradle.kts", "build-logic/src/**/*.gradle.kts")
    ktfmt(ktfmtVersion).googleStyle()
  }
  kotlin {
    target("build-logic/src/**/*.kt")
    ktfmt(ktfmtVersion).googleStyle()
  }
}

val secretsDir = layout.projectDirectory.dir("secrets")

tasks.register<LocalSecretsTask>("generateLocalSecrets") {
  group = "local setup"
  description = "secrets/에 없는 로컬 인증키·고정 토큰·DB 비밀번호만 만든다(값은 출력하지 않음)."
  outputDir = secretsDir
  rotateJwt = false
}

tasks.register<LocalSecretsTask>("rotateLocalJwt") {
  group = "local setup"
  description = "로컬 인증키 쌍과 고정 토큰을 새로 만든다(DB 비밀번호는 유지)."
  outputDir = secretsDir
  rotateJwt = true
}
