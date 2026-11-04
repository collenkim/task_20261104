// 서비스 공통 빌드 규칙: JDK 21 toolchain, Kotlin·Spring·JPA 플러그인, Spring Boot BOM, 포맷 검사,
// 서비스 독립성 검사(project 의존 금지), 실행 산출물(bootJar) 이름 고정.
import org.springframework.boot.gradle.plugin.SpringBootPlugin
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
  id("org.jetbrains.kotlin.jvm")
  id("org.jetbrains.kotlin.plugin.spring")
  id("org.jetbrains.kotlin.plugin.jpa")
  id("org.springframework.boot")
  id("com.diffplug.spotless")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

fun version(name: String): String = catalog.findVersion(name).get().requiredVersion

java { toolchain { languageVersion = JavaLanguageVersion.of(version("java")) } }

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
  }
}

// JPA 엔티티는 프록시를 위해 open이어야 한다(엔티티 규칙: docs/common/code-conventions.md).
allOpen {
  annotation("jakarta.persistence.Entity")
  annotation("jakarta.persistence.MappedSuperclass")
  annotation("jakarta.persistence.Embeddable")
}

dependencies {
  implementation(platform(SpringBootPlugin.BOM_COORDINATES))
  testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
}

// POL-ARCH-002: 서비스는 다른 서비스(또는 공유 모듈)에 project(...) 의존을 만들지 않는다.
configurations.configureEach {
  val configurationName = name
  dependencies.withType<ProjectDependency>().configureEach {
    throw GradleException(
      "서비스 독립성 위반(POL-ARCH-002): ${project.path}의 $configurationName 에 project 의존 '$path'가 있습니다. " +
        "다른 서비스와는 HTTP API로만 통신합니다."
    )
  }
}

spotless {
  kotlin {
    target("src/**/*.kt")
    ktfmt(version("ktfmt")).googleStyle()
  }
  kotlinGradle {
    target("*.gradle.kts")
    ktfmt(version("ktfmt")).googleStyle()
  }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// 이미지에는 실행 산출물 하나만 들어간다: build/libs/<서비스>.jar
tasks.named<Jar>("jar") { enabled = false }

tasks.named<BootJar>("bootJar") { archiveFileName = "${project.name}.jar" }

// bootRun은 SPRING_PROFILES_ACTIVE가 없으면 local 프로필로 실행하고, 저장소 secrets/ 의 절대 경로를 넘긴다.
// (configtree는 '..'이 든 상대 경로를 읽지 못하므로 절대 경로로 준다. 컨테이너는 Compose가 /run/secrets 를 넘긴다.)
tasks.named<BootRun>("bootRun") {
  val secrets = rootDir.resolve("secrets")
  systemProperty(
    "spring.profiles.active",
    providers.environmentVariable("SPRING_PROFILES_ACTIVE").getOrElse("local"),
  )
  environment("TASK_SECRETS_DIR", secrets.resolve("${project.name}/config").absolutePath)
  environment("TASK_JWT_PUBLIC_KEY", secrets.resolve("jwt/public-key.pem").absolutePath)
}
