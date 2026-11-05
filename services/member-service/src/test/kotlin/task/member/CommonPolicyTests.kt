package task.member

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
import task.member.support.TestProperties
import task.member.support.TestcontainersConfiguration

/** 공통 정책이 멤버 서비스에 적용됐는지 확인한다: 사용자 무인증·오류 응답·페이지네이션·상태 확인·API 문서·DB 마이그레이션. */
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
  fun `상태 확인은 인증 없이 열려 있고 DB를 포함한 준비 상태가 UP이다`() {
    mockMvc.get("/actuator/health").andExpect {
      status { isOk() }
      jsonPath("$.status") { value("UP") }
    }
    mockMvc.get("/actuator/health/readiness").andExpect { jsonPath("$.status") { value("UP") } }
    mockMvc.get("/actuator/health/liveness").andExpect { jsonPath("$.status") { value("UP") } }
  }

  @Test
  fun `사용자 인증 없이 요청하며 기존 Bearer 헤더도 인증에 사용하지 않는다`() {
    mockMvc.get("/test-probe/page").andExpect { status { isOk() } }
    mockMvc
      .get("/test-probe/page") {
        header(HttpHeaders.AUTHORIZATION, listOf("Bearer", "unused").joinToString(" "))
      }
      .andExpect { status { isOk() } }
  }

  @Test
  fun `요청이 통과하고 페이지 크기는 기본 20, 최대 100이다`() {
    mockMvc
      .get("/test-probe/page") {}
      .andExpect {
        status { isOk() }
        jsonPath("$.page") { value(0) }
        jsonPath("$.size") { value(20) }
      }
    mockMvc.get("/test-probe/page?size=1000") {}.andExpect { jsonPath("$.size") { value(100) } }
  }

  @Test
  fun `오류 응답은 ProblemDetail과 code를 갖고 내부 정보를 넣지 않는다`() {
    mockMvc.get("/test-probe/none") {}.problem(404, "NOT_FOUND")
    mockMvc.put("/test-probe/page") {}.problem(405, "METHOD_NOT_ALLOWED")
    mockMvc.get("/test-probe/number?value=abc") {}.problem(400, "BAD_REQUEST")
    mockMvc
      .post("/test-probe/validated") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"name": ""}"""
      }
      .problem(400, "VALIDATION_FAILED")
    mockMvc
      .get("/test-probe/boom") {}
      .problem(500, "INTERNAL_ERROR")
      .andExpect {
        content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("내부 상세"))) }
        jsonPath("$.title") { value("내부 처리 오류") }
        jsonPath("$.detail") { value("요청을 처리하지 못했습니다.") }
      }
  }

  @Test
  fun `멤버 명세와 Swagger는 키 없이 열리고 내부 문서 전달 경로는 명세에서 제외한다`() {
    mockMvc.get("/v3/api-docs").andExpect {
      status { isOk() }
      jsonPath("$.info.version") { value("0.2.0") }
      jsonPath("$.security") { doesNotExist() }
      jsonPath("$.paths['/internal-docs/group']") { doesNotExist() }
    }
    mockMvc.get("/swagger-ui/index.html").andExpect { status { isOk() } }
    mockMvc.get("/swagger-ui/swagger-initializer.js").andExpect {
      status { isOk() }
      content { string(org.hamcrest.Matchers.containsString("supportedSubmitMethods")) }
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
  fun `Flyway가 서비스 전용 스키마에 마이그레이션 이력을 남긴다`() {
    val applied =
      jdbcTemplate.queryForObject(
        "select count(*) from member_service.flyway_schema_history where version = '1' and success",
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
