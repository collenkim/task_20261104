package task.member.support

import jakarta.validation.constraints.Min
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpHeaders
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.web.bind.annotation.GetMapping
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
      .withDatabaseName("member_service")
      .withUsername("member_app")
      .withCommand(
        "--character-set-server=utf8mb4",
        "--collation-server=utf8mb4_unicode_ci",
        "--default-time-zone=+00:00",
      )

  companion object {
    const val MARIADB_IMAGE = "mariadb:11.8.9"
  }
}

/**
 * 모든 MockMvc 요청에 기본 Bearer 토큰(두 scope)을 붙인다(POL-SEC-001).
 *
 * 인증 자체를 확인하는 테스트는 이 설정을 가져오지 않고 요청마다 헤더를 정한다. 요청에 직접 준 `Authorization`이 우선한다.
 */
@TestConfiguration(proxyBeanMethods = false)
class DefaultBearerTokenConfiguration {
  @Bean
  fun defaultBearerToken() = MockMvcBuilderCustomizer { builder ->
    builder.defaultRequest(
      MockMvcRequestBuilders.get("/").header(HttpHeaders.AUTHORIZATION, TestJwt.bearer())
    )
  }
}

/** 테스트는 실행 중 만든 가짜 내부 키·JWT 키만 쓰며 실제 secrets/를 읽지 않는다. */
object TestProperties {
  const val NO_LOCAL_SECRETS = "spring.config.import="
  val internalKey: String = listOf("test", "internal", "service", "key").joinToString("-")

  fun register(registry: DynamicPropertyRegistry) {
    registry.add("task.internal-service.key") { internalKey }
    registry.add("task.security.jwt.public-key") { TestJwt.publicKeyPem }
    registry.add("task.security.jwt.issuer") { TestJwt.ISSUER }
    registry.add("task.security.local-token.signing-key") { TestJwt.signingKeyPem }
    registry.add("task.group-client.base-url") { "http://127.0.0.1:9" }
    registry.add("spring.flyway.locations") {
      "classpath:db/migration/mariadb,classpath:db/test-migration"
    }
  }
}

/** 공통 정책(인증·오류 응답·페이지네이션) 확인용 테스트 전용 GET API. 운영 코드에는 없다. */
@RestController
@RequestMapping("/test-probe")
class ProbeController {
  @GetMapping("/page")
  fun page(pageable: Pageable) = mapOf("page" to pageable.pageNumber, "size" to pageable.pageSize)

  @GetMapping("/number") fun number(@RequestParam value: Int) = mapOf("value" to value)

  /** 형식은 맞지만 제약을 어긴 값(0 이하)은 메서드 검증 실패(422)다. */
  @GetMapping("/validated")
  fun validated(@RequestParam @Min(1) value: Int) = mapOf("value" to value)

  @GetMapping("/boom") fun boom(): Map<String, Any> = error("내부 상세 정보")
}
