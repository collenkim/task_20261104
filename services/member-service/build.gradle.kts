// 멤버 서비스. 공통 규칙은 build-logic의 task.spring-service. 다른 서비스와 project 의존을 두지 않는다.
plugins { id("task.spring-service") }

dependencies {
  implementation(libs.spring.boot.starter.webmvc)
  implementation(libs.spring.boot.starter.restclient)
  implementation(libs.spring.boot.starter.validation)
  implementation(libs.spring.boot.starter.actuator)
  implementation(libs.spring.boot.starter.data.jpa)
  implementation(libs.spring.boot.starter.flyway)
  implementation(libs.springdoc.openapi.webmvc.ui)
  implementation(libs.jackson.module.kotlin)
  implementation(libs.kotlin.reflect)
  runtimeOnly(libs.flyway.mysql)
  runtimeOnly(libs.mariadb)

  testImplementation(libs.spring.boot.starter.webmvc.test)
  testImplementation(libs.spring.boot.testcontainers)
  testImplementation(libs.testcontainers.junit.jupiter)
  testImplementation(libs.testcontainers.mariadb)
  testImplementation(libs.kotlin.test.junit5)
  testImplementation(libs.archunit)
  testImplementation(libs.json.schema.validator)
  testRuntimeOnly(libs.junit.platform.launcher)
}

// POL-TEST-007: 실제 운영 경로만 스캔하는 별도 문맥에서 계약을 검증·생성한다.
val generatedOpenApi = layout.buildDirectory.file("generated/openapi/openapi.json")
val committedOpenApi = layout.projectDirectory.file("docs/openapi.json")
val apiDocument = layout.projectDirectory.file("docs/api.md")
val contractTestClass = "task.member.contract.MemberOpenApiContractTests"

tasks.named<Test>("test") { exclude("task/member/contract/**") }

val generateOpenApiSnapshot by
  tasks.registering(Test::class) {
    group = "verification"
    description = "운영 API 계약·실제 응답을 검사하고 build에 OpenAPI를 생성합니다."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    javaLauncher = tasks.named<Test>("test").flatMap { it.javaLauncher }
    filter { includeTestsMatching(contractTestClass) }
    systemProperty("task.openapi.output", generatedOpenApi.get().asFile.absolutePath)
    systemProperty("task.openapi.document", apiDocument.asFile.absolutePath)
    inputs.file(apiDocument)
    outputs.file(generatedOpenApi)
    shouldRunAfter(tasks.named("test"))
  }

/** 저장소 명세는 읽기 전용으로 비교하며 갱신 명령을 따로 둔다(POL-TEST-007). */
abstract class CheckOpenApiSnapshot : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val generated: RegularFileProperty

  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val snapshot: RegularFileProperty

  @TaskAction
  fun checkSnapshot() {
    if (!generated.get().asFile.readBytes().contentEquals(snapshot.get().asFile.readBytes())) {
      throw GradleException(
        "OpenAPI 스냅샷이 현재 계약과 다릅니다. 변경 영향을 검토한 뒤 :member-service:updateOpenApiSnapshot을 실행하세요."
      )
    }
  }
}

val checkOpenApiSnapshot by
  tasks.registering(CheckOpenApiSnapshot::class) {
    group = "verification"
    description = "공유 OpenAPI와 현재 계약의 일치를 읽기 전용으로 검사합니다."
    dependsOn(generateOpenApiSnapshot)
    generated.set(generatedOpenApi)
    snapshot.set(committedOpenApi)
  }

tasks.register<Copy>("updateOpenApiSnapshot") {
  group = "documentation"
  description = "검증한 현재 계약으로 공유 OpenAPI를 명시적으로 갱신합니다."
  dependsOn(generateOpenApiSnapshot)
  from(generatedOpenApi)
  into(committedOpenApi.asFile.parentFile)
}

tasks.named("check") { dependsOn(checkOpenApiSnapshot) }
