package task.group.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import task.group.application.architecturefixture.ForbiddenReferences

/** 바이트코드의 필드·인자·반환·실행 의존성을 검사한다(POL-TEST-005). */
class GroupArchitectureTests {
  private val classes =
    ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("task.group")
  private val applicationRule =
    noClasses()
      .that()
      .resideInAPackage("task.group.application..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "task.group.infrastructure..",
        "task.group.presentation..",
        "jakarta.persistence..",
        "org.springframework.data..",
        "org.springframework.web..",
        "org.springframework.http..",
        "jakarta.servlet..",
        "java.net..",
        "tools.jackson..",
        "com.fasterxml.jackson..",
        "io.swagger..",
        "org.springframework.transaction..",
      )

  @Test
  fun `application과 포트는 기술 계층과 전송 타입 트랜잭션에 의존하지 않는다`() {
    applicationRule.check(classes)
  }

  @Test
  fun `presentation은 저장소 외부 DTO 출력 포트와 구체 핸들러를 참조하지 않는다`() {
    noClasses()
      .that()
      .resideInAPackage("task.group.presentation..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "task.group.infrastructure..",
        "jakarta.persistence..",
        "org.springframework.data..",
        "task.group.application.port.output..",
      )
      .check(classes)
    noClasses()
      .that()
      .resideInAPackage("task.group.presentation..")
      .should()
      .dependOnClassesThat()
      .haveSimpleNameEndingWith("QueryHandler")
      .check(classes)
  }

  @Test
  fun `서비스 간 코드와 다른 서비스 엔티티를 참조하지 않는다`() {
    noClasses().should().dependOnClassesThat().resideInAPackage("task.member..").check(classes)
  }

  @Test
  fun `계층 검사는 의도적으로 만든 엔티티와 응답 DTO 참조를 실제로 검출한다`() {
    val failure =
      applicationRule.evaluate(ClassFileImporter().importClasses(ForbiddenReferences::class.java))
    assertTrue(failure.hasViolation())
    val report = failure.failureReport.toString()
    assertTrue(report.contains("GroupEntity"))
    assertTrue(report.contains("MemberGroupDetailResponse"))
  }
}
