import task.buildlogic.CheckOpenApiSnapshot
import task.buildlogic.UpdateOpenApiSnapshot

plugins { java }

val servicePackage = project.name.substringBefore("-")
val contractTestClass =
  "task." +
    servicePackage +
    ".contract." +
    servicePackage.replaceFirstChar { it.uppercaseChar() } +
    "OpenApiContractTests"
val generatedOpenApi = layout.buildDirectory.file("generated/openapi/openapi.json")
val committedOpenApi = layout.projectDirectory.file("docs/openapi.json")
val apiDocument = layout.projectDirectory.file("docs/api.md")

tasks.named<Test>("test") { exclude("task/" + servicePackage + "/contract/**") }

val generateOpenApiSnapshot by
  tasks.registering(Test::class) {
    group = "verification"
    description = "운영 계약·실제 응답을 검사하고 OpenAPI를 생성합니다."
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
val checkOpenApiSnapshot by
  tasks.registering(CheckOpenApiSnapshot::class) {
    group = "verification"
    dependsOn(generateOpenApiSnapshot)
    generated.set(generatedOpenApi)
    snapshot.set(committedOpenApi)
  }

val updateOpenApiSnapshot =
  tasks.register<UpdateOpenApiSnapshot>("updateOpenApiSnapshot") {
    group = "documentation"
    description = "검증한 계약으로 공유 OpenAPI를 갱신합니다."
    dependsOn(generateOpenApiSnapshot)
    generated.set(generatedOpenApi)
    snapshot.set(committedOpenApi)
  }

checkOpenApiSnapshot { mustRunAfter(updateOpenApiSnapshot) }

tasks.named("check") { dependsOn(checkOpenApiSnapshot) }
