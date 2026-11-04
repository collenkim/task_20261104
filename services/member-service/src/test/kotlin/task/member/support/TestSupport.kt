package task.member.support

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
import org.testcontainers.postgresql.PostgreSQLContainer

/** 서비스 전용 DB와 같은 이미지로 테스트 DB를 띄운다(Docker 필요). */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
  @Bean @ServiceConnection fun postgres() = PostgreSQLContainer(POSTGRES_IMAGE)

  companion object {
    const val POSTGRES_IMAGE = "postgres:18.6"
  }
}

/** 테스트 공통 속성: 테스트 키로 JWT를 검증하고, 실제 secrets/는 읽지 않는다. */
object TestProperties {
  /** `@SpringBootTest(properties = ...)`에 넣는다. configtree 위치를 없는 폴더로 바꿔 secrets/를 읽지 않게 한다. */
  const val NO_LOCAL_SECRETS = "TASK_SECRETS_DIR=build/no-local-secrets"

  fun register(registry: DynamicPropertyRegistry) {
    registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location") {
      TestJwt.publicKeyLocation
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
