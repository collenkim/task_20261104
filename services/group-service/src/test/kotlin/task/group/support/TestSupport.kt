package task.group.support

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.data.domain.Pageable
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.testcontainers.mariadb.MariaDBContainer

/** 서비스 전용 DB와 같은 이미지로 테스트 DB를 띄운다(Docker 필요). */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
  @Bean
  @ServiceConnection
  fun mariadb() =
    MariaDBContainer(MARIADB_IMAGE)
      .withDatabaseName("group_service")
      .withUsername("group_app")
      .withCommand(
        "--character-set-server=utf8mb4",
        "--collation-server=utf8mb4_unicode_ci",
        "--default-time-zone=+00:00",
      )

  companion object {
    const val MARIADB_IMAGE = "mariadb:11.8.9"
  }
}

/** 테스트는 실행 중 만든 가짜 내부 키만 쓰며 실제 secrets/를 읽지 않는다. */
object TestProperties {
  const val NO_LOCAL_SECRETS = "spring.config.import="
  val internalKey: String = listOf("test", "internal", "service", "key").joinToString("-")

  fun register(registry: DynamicPropertyRegistry) {
    registry.add("task.internal-service.clients.member-service.key") { internalKey }
    registry.add("spring.flyway.locations") {
      "classpath:db/migration/mariadb,classpath:db/test-migration"
    }
  }
}

/** 공통 정책(인증·오류 응답·페이지네이션) 확인용 테스트 전용 API. 운영 코드에는 없다. */
@RestController
@RequestMapping("/test-probe")
class ProbeController {
  @GetMapping("/page")
  fun page(pageable: Pageable) = mapOf("page" to pageable.pageNumber, "size" to pageable.pageSize)

  @GetMapping("/number") fun number(@RequestParam value: Int) = mapOf("value" to value)

  @PostMapping("/validated") fun validated(@Valid @RequestBody body: ProbeBody) = body

  @GetMapping("/boom") fun boom(): Map<String, Any> = error("내부 상세 정보")

  data class ProbeBody(@field:NotBlank val name: String?)
}
