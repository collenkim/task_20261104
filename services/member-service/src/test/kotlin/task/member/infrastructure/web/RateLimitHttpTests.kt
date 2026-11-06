package task.member.infrastructure.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import task.member.support.TestJwt
import task.member.support.TestProperties
import task.member.support.TestcontainersConfiguration

/** 인증된 subject별로 업무 경로 호출 수를 제한하고 초과하면 429와 Retry-After를 돌려준다(POL-SEC-007). */
@SpringBootTest(
  properties =
    [
      TestProperties.NO_LOCAL_SECRETS,
      "task.rate-limit.capacity=2",
      "task.rate-limit.refill-per-second=1",
    ]
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class RateLimitHttpTests {
  @Autowired lateinit var mvc: MockMvc

  private fun call(path: String, token: String) =
    mvc.get(path) { header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(token)) }

  @Test
  fun `용량을 넘긴 호출자만 429를 받고 다른 호출자와 업무 외 경로는 영향을 받지 않는다`() {
    val limited = TestJwt.token(subject = "rate-limited-client")
    val other = TestJwt.token(subject = "other-client")
    repeat(2) { call("/api/member/zzzzzzzzzz", limited).andExpect { status { isNotFound() } } }
    call("/api/member/zzzzzzzzzz", limited).andExpect {
      status { isEqualTo(429) }
      header { string(HttpHeaders.RETRY_AFTER, "1") }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("$.code") { value("TOO_MANY_REQUESTS") }
      jsonPath("$.status") { value(429) }
    }
    call("/api/member/zzzzzzzzzz", other).andExpect { status { isNotFound() } }
    repeat(3) { call("/test-probe/page", limited).andExpect { status { isOk() } } }
    repeat(3) { mvc.get("/actuator/health").andExpect { status { isOk() } } }
  }

  @Test
  fun `인증 실패는 요청 제한보다 먼저 401로 끝난다`() {
    repeat(3) { mvc.get("/api/members").andExpect { status { isUnauthorized() } } }
  }

  companion object {
    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) = TestProperties.register(registry)
  }
}
