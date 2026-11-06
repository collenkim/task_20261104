package task.member.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.RestController
import task.member.application.architecturefixture.ForbiddenReferences

/** 바이트코드의 필드·인자·반환·실행 의존성을 검사한다(POL-TEST-005). */
class MemberArchitectureTests {
  private val classes =
    ClassFileImporter()
      .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
      .importPackages("task.member")
  private val applicationRule =
    noClasses()
      .that()
      .resideInAPackage("task.member.application..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "task.member.infrastructure..",
        "task.member.presentation..",
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
  fun `기술 중계 컨트롤러는 명시한 HTTP 경계에 있고 저장소와 업무 핸들러에 의존하지 않는다`() {
    classes()
      .that()
      .areAnnotatedWith(RestController::class.java)
      .should()
      .resideInAnyPackage("task.member.presentation..", "task.member.infrastructure.web..")
      .check(classes)
    noClasses()
      .that()
      .resideInAPackage("task.member.infrastructure.web..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "task.member.infrastructure.persistence..",
        "task.member.application.port.output..",
        "task.member.application.query..",
        "jakarta.persistence..",
      )
      .check(classes)
  }

  @Test
  fun `application과 포트는 기술 계층과 전송 타입 트랜잭션에 의존하지 않는다`() {
    applicationRule.check(classes)
  }

  @Test
  fun `presentation은 저장소 외부 DTO 출력 포트와 구체 핸들러를 참조하지 않는다`() {
    noClasses()
      .that()
      .resideInAPackage("task.member.presentation..")
      .should()
      .dependOnClassesThat()
      .resideInAnyPackage(
        "task.member.infrastructure..",
        "jakarta.persistence..",
        "org.springframework.data..",
        "task.member.application.port.output..",
      )
      .check(classes)
    noClasses()
      .that()
      .resideInAPackage("task.member.presentation..")
      .should()
      .dependOnClassesThat()
      .haveSimpleNameEndingWith("QueryHandler")
      .check(classes)
  }

  @Test
  fun `서비스 간 코드와 다른 서비스 엔티티를 참조하지 않는다`() {
    noClasses().should().dependOnClassesThat().resideInAPackage("task.group..").check(classes)
  }

  @Test
  fun `계층 검사는 의도적으로 만든 엔티티와 응답 DTO 참조를 실제로 검출한다`() {
    val failure =
      applicationRule.evaluate(ClassFileImporter().importClasses(ForbiddenReferences::class.java))
    assertTrue(failure.hasViolation())
    val report = failure.failureReport.toString()
    assertTrue(report.contains("MemberEntity"))
    assertTrue(report.contains("MemberDetailResponse"))
  }
}
