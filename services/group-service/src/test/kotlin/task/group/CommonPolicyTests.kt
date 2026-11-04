package task.group

import java.time.Duration
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import task.group.support.TestJwt
import task.group.support.TestProperties
import task.group.support.TestcontainersConfiguration

/** 공통 정책이 그룹 서비스에 적용됐는지 확인한다: 인증(JWT)·오류 응답·페이지네이션·상태 확인·API 문서·DB 마이그레이션. */
@SpringBootTest(
  properties =
    [
      TestProperties.NO_LOCAL_SECRETS,
      TestProperties.MEMBER_CLIENT_URL,
      TestProperties.MEMBER_CLIENT_TOKEN,
    ]
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CommonPolicyTests {
  @Autowired lateinit var mockMvc: MockMvc
  @Autowired lateinit var jdbcTemplate: JdbcTemplate

  private fun bearer(token: String = TestJwt.token()) = "Bearer $token"

  private fun ResultActionsDsl.problem(status: Int, code: String) = andExpect {
    status { isEqualTo(status) }
    content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
    jsonPath("$.status") { value(status) }
    jsonPath("$.code") { value(code) }
  }

  @Test
  fun `멤버 서비스 없이도 기동하고 상태 확인은 인증 없이 열려 있으며 준비 상태가 UP이다`() {
    mockMvc.get("/actuator/health").andExpect {
      status { isOk() }
      jsonPath("$.status") { value("UP") }
    }
    mockMvc.get("/actuator/health/readiness").andExpect { jsonPath("$.status") { value("UP") } }
    mockMvc.get("/actuator/health/liveness").andExpect { jsonPath("$.status") { value("UP") } }
  }

  @Test
  fun `토큰이 없으면 401 ProblemDetail과 WWW-Authenticate를 돌려준다`() {
    mockMvc.get("/test-probe/page").problem(401, "UNAUTHORIZED").andExpect {
      header { string(HttpHeaders.WWW_AUTHENTICATE, org.hamcrest.Matchers.startsWith("Bearer")) }
    }
  }

  @Test
  fun `대상이 다르거나 만료됐거나 다른 키로 서명한 토큰은 401이다`() {
    val invalid =
      listOf(
        TestJwt.token(audience = listOf("member-service")),
        TestJwt.token(expiresIn = Duration.ofMinutes(-5)),
        TestJwt.token(signer = TestJwt.otherKeyPair),
      )
    for (token in invalid) {
      mockMvc
        .get("/test-probe/page") { header(HttpHeaders.AUTHORIZATION, bearer(token)) }
        .problem(401, "UNAUTHORIZED")
    }
  }

  @Test
  fun `유효한 토큰이면 통과하고 페이지 크기는 기본 20, 최대 100이다`() {
    mockMvc
      .get("/test-probe/page") { header(HttpHeaders.AUTHORIZATION, bearer()) }
      .andExpect {
        status { isOk() }
        jsonPath("$.page") { value(0) }
        jsonPath("$.size") { value(20) }
      }
    mockMvc
      .get("/test-probe/page?size=1000") { header(HttpHeaders.AUTHORIZATION, bearer()) }
      .andExpect { jsonPath("$.size") { value(100) } }
  }

  @Test
  fun `오류 응답은 ProblemDetail과 code를 갖고 내부 정보를 넣지 않는다`() {
    mockMvc
      .get("/test-probe/none") { header(HttpHeaders.AUTHORIZATION, bearer()) }
      .problem(404, "NOT_FOUND")
    mockMvc
      .put("/test-probe/page") { header(HttpHeaders.AUTHORIZATION, bearer()) }
      .problem(405, "METHOD_NOT_ALLOWED")
    mockMvc
      .get("/test-probe/number?value=abc") { header(HttpHeaders.AUTHORIZATION, bearer()) }
      .problem(400, "BAD_REQUEST")
    mockMvc
      .post("/test-probe/validated") {
        header(HttpHeaders.AUTHORIZATION, bearer())
        contentType = MediaType.APPLICATION_JSON
        content = """{"name": ""}"""
      }
      .problem(400, "VALIDATION_FAILED")
    mockMvc
      .get("/test-probe/boom") { header(HttpHeaders.AUTHORIZATION, bearer()) }
      .problem(500, "INTERNAL_ERROR")
      .andExpect {
        content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("내부 상세"))) }
      }
  }

  @Test
  fun `API 문서는 인증 없이 열리고 Bearer 보안 스킴을 선언한다`() {
    mockMvc.get("/v3/api-docs").andExpect {
      status { isOk() }
      jsonPath("$.components.securitySchemes.bearerAuth.scheme") { value("bearer") }
      jsonPath("$.security[0].bearerAuth") { exists() }
    }
    mockMvc.get("/swagger-ui/index.html").andExpect { status { isOk() } }
  }

  @Test
  fun `Flyway가 서비스 전용 스키마에 마이그레이션 이력을 남긴다`() {
    val applied =
      jdbcTemplate.queryForObject(
        "select count(*) from group_service.flyway_schema_history where version = '1' and success",
        Int::class.java,
      )
    assertEquals(1, applied)
  }

  companion object {
    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) = TestProperties.register(registry)
  }
}
