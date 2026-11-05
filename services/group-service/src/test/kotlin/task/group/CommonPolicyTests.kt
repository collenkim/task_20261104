package task.group

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import task.group.support.TestProperties
import task.group.support.TestcontainersConfiguration

/** 공통 정책이 그룹 서비스에 적용됐는지 확인한다: 내부 호출 키·오류 응답·페이지네이션·상태 확인·API 문서·DB 마이그레이션. */
@SpringBootTest(properties = [TestProperties.NO_LOCAL_SECRETS])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CommonPolicyTests {
  @Autowired lateinit var mockMvc: MockMvc
  @Autowired lateinit var jdbcTemplate: JdbcTemplate

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
  fun `내부 키 누락 불일치 중복은 403이고 응답에 키가 없다`() {
    mockMvc.get("/test-probe/page").problem(403, "INTERNAL_ACCESS_DENIED")
    mockMvc
      .get("/test-probe/page") {
        header("X-Internal-Service-Id", "member-service")
        header(KEY_HEADER, "invalid")
      }
      .problem(403, "INTERNAL_ACCESS_DENIED")
    mockMvc
      .get("/v3/api-docs") {
        header("X-Internal-Service-Id", "member-service")
        header(KEY_HEADER, TestProperties.internalKey, "duplicate")
      }
      .problem(403, "INTERNAL_ACCESS_DENIED")
    mockMvc.get("/v3/api-docs").problem(403, "INTERNAL_ACCESS_DENIED").andExpect {
      jsonPath("$.title") { value("내부 호출 거부") }
      content {
        string(
          org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString(TestProperties.internalKey)
          )
        )
      }
    }
  }

  @Test
  fun `요청이 통과하고 페이지 크기는 기본 20, 최대 100이다`() {
    mockMvc
      .get("/test-probe/page") {
        header("X-Internal-Service-Id", "member-service")
        header(KEY_HEADER, TestProperties.internalKey)
      }
      .andExpect {
        status { isOk() }
        jsonPath("$.page") { value(0) }
        jsonPath("$.size") { value(20) }
      }
    mockMvc
      .get("/test-probe/page?size=1000") {
        header("X-Internal-Service-Id", "member-service")
        header(KEY_HEADER, TestProperties.internalKey)
      }
      .andExpect { jsonPath("$.size") { value(100) } }
  }

  @Test
  fun `오류 응답은 ProblemDetail과 code를 갖고 내부 정보를 넣지 않는다`() {
    mockMvc
      .get("/test-probe/none") {
        header("X-Internal-Service-Id", "member-service")
        header(KEY_HEADER, TestProperties.internalKey)
      }
      .problem(404, "NOT_FOUND")
    mockMvc
      .put("/test-probe/page") {
        header("X-Internal-Service-Id", "member-service")
        header(KEY_HEADER, TestProperties.internalKey)
      }
      .problem(405, "METHOD_NOT_ALLOWED")
    mockMvc
      .get("/test-probe/number?value=abc") {
        header("X-Internal-Service-Id", "member-service")
        header(KEY_HEADER, TestProperties.internalKey)
      }
      .problem(400, "BAD_REQUEST")
    mockMvc
      .post("/test-probe/validated") {
        header("X-Internal-Service-Id", "member-service")
        header(KEY_HEADER, TestProperties.internalKey)
        contentType = MediaType.APPLICATION_JSON
        content = """{"name": ""}"""
      }
      .problem(400, "VALIDATION_FAILED")
    mockMvc
      .get("/test-probe/boom") {
        header("X-Internal-Service-Id", "member-service")
        header(KEY_HEADER, TestProperties.internalKey)
      }
      .problem(500, "INTERNAL_ERROR")
      .andExpect {
        content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("내부 상세"))) }
        jsonPath("$.title") { value("내부 처리 오류") }
        jsonPath("$.detail") { value("요청을 처리하지 못했습니다.") }
      }
  }

  @Test
  fun `그룹 명세는 키가 필요하고 Swagger 화면은 제공하지 않는다`() {
    mockMvc
      .get("/v3/api-docs") {
        header("X-Internal-Service-Id", "member-service")
        header(KEY_HEADER, TestProperties.internalKey)
      }
      .andExpect {
        status { isOk() }
        jsonPath("$.info.version") { value("0.1.0") }
        jsonPath("$.components.securitySchemes.internalServiceKey.name") { value(KEY_HEADER) }
      }
    mockMvc
      .get("/swagger-ui/index.html") {
        header("X-Internal-Service-Id", "member-service")
        header(KEY_HEADER, TestProperties.internalKey)
      }
      .problem(404, "NOT_FOUND")
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
    const val KEY_HEADER = "X-Internal-Service-Key"

    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) = TestProperties.register(registry)
  }
}
