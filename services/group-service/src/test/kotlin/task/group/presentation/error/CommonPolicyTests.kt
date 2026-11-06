package task.group.presentation.error

import kotlin.test.assertEquals
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request
import task.group.support.TestProperties
import task.group.support.TestcontainersConfiguration

/** 공통 정책이 그룹 서비스에 적용됐는지 확인한다: 내부 호출 키(403)·GET 전용(405)·오류 코드표·페이지네이션·상태 확인·API 문서·DB 마이그레이션. */
@SpringBootTest(properties = [TestProperties.NO_LOCAL_SECRETS])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CommonPolicyTests {
  @Autowired lateinit var mockMvc: MockMvc
  @Autowired lateinit var jdbcTemplate: JdbcTemplate

  private fun MockHttpServletRequestDsl.internal() {
    header(ID_HEADER, "member-service")
    header(KEY_HEADER, TestProperties.internalKey)
  }

  private fun ResultActionsDsl.problem(
    status: Int,
    code: String,
    type: String = ProblemType.DEFAULT,
  ) = andExpect {
    status { isEqualTo(status) }
    content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
    jsonPath("$.status") { value(status) }
    jsonPath("$.code") { value(code) }
    jsonPath("$.type") { value(type) }
  }

  @Test
  fun `멤버 서비스 없이도 기동하고 상태 확인은 인증 없이 열려 있으며 준비 상태가 UP이다`() {
    mockMvc.get("/actuator/health").andExpect {
      status { isOk() }
      content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
      jsonPath("$.status") { value("UP") }
    }
    for (path in listOf("/actuator/health/readiness", "/actuator/health/liveness")) {
      mockMvc.get(path).andExpect {
        content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        jsonPath("$.status") { value("UP") }
      }
    }
  }

  @Test
  fun `내부 키 누락 불일치 중복은 403 FORBIDDEN과 내부 호출 거부 type이고 응답에 키가 없다`() {
    val denied = "urn:problem-type:internal-access-denied"
    mockMvc.get("/test-probe/page").problem(403, "FORBIDDEN", denied)
    mockMvc
      .get("/test-probe/page") {
        header(ID_HEADER, "member-service")
        header(KEY_HEADER, "invalid")
      }
      .problem(403, "FORBIDDEN", denied)
    mockMvc
      .get("/v3/api-docs") {
        header(ID_HEADER, "member-service")
        header(KEY_HEADER, TestProperties.internalKey, "duplicate")
      }
      .problem(403, "FORBIDDEN", denied)
    mockMvc
      .get("/api/groups/summary?memberIds=tu3fgxd45k")
      .problem(403, "FORBIDDEN", denied)
      .andExpect {
        jsonPath("$.title") { value("내부 호출 거부") }
        content { string(not(containsString(TestProperties.internalKey))) }
      }
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("blockedMethods")
  fun `GET 외 메서드는 내부 키 검사와 핸들러보다 먼저 405와 Allow GET으로 막는다`(method: String, path: String) {
    val result =
      mockMvc.request(HttpMethod.valueOf(method), path).andExpect {
        status { isEqualTo(405) }
        header { string(HttpHeaders.ALLOW, "GET") }
      }
    // HEAD 응답 본문은 실제 서버가 보내지 않으므로 본문 검사는 HEAD 밖에서만 한다.
    if (method != "HEAD") result.problem(405, "METHOD_NOT_ALLOWED")
  }

  @Test
  fun `요청이 통과하고 페이지 크기는 기본 20, 최대 100이다`() {
    mockMvc
      .get("/test-probe/page") { internal() }
      .andExpect {
        status { isOk() }
        jsonPath("$.page") { value(0) }
        jsonPath("$.size") { value(20) }
      }
    mockMvc
      .get("/test-probe/page?size=1000") { internal() }
      .andExpect {
        jsonPath("$.size") { value(100) }
      }
  }

  @Test
  fun `오류 응답은 코드표의 code를 쓰고 내부 정보를 넣지 않는다`() {
    mockMvc.get("/test-probe/none") { internal() }.problem(404, "RESOURCE_NOT_FOUND")
    mockMvc.get("/test-probe/number?value=abc") { internal() }.problem(400, "INVALID_PARAMETER")
    mockMvc.get("/test-probe/number") { internal() }.problem(400, "INVALID_PARAMETER")
    mockMvc.get("/test-probe/validated?value=0") { internal() }.problem(422, "VALIDATION_FAILED")
    mockMvc
      .get("/test-probe/page") {
        internal()
        accept = MediaType.APPLICATION_XML
      }
      .andExpect { status { isEqualTo(406) } }
    mockMvc
      .get("/test-probe/boom") { internal() }
      .problem(500, "INTERNAL_SERVER_ERROR")
      .andExpect {
        content { string(not(containsString("내부 상세"))) }
        jsonPath("$.title") { value("서버 내부 오류") }
        jsonPath("$.detail") { value("요청을 처리하지 못했습니다.") }
      }
  }

  @Test
  fun `그룹 명세는 키가 필요하고 코드표 오류 응답을 노출하며 Swagger 화면은 제공하지 않는다`() {
    mockMvc
      .get("/v3/api-docs") { internal() }
      .andExpect {
        status { isOk() }
        jsonPath("$.info.version") { value("0.4.0") }
        jsonPath("$.components.securitySchemes.internalServiceKey.name") { value(KEY_HEADER) }
        for (status in listOf("400", "403", "405", "406", "422", "500", "503")) {
          jsonPath(
            "$.paths['/api/groups/summary'].get.responses['$status'].content['application/json']"
          ) {
            exists()
          }
        }
        for (status in listOf("400", "403", "404", "405", "406", "500", "503")) {
          jsonPath(
            "$.paths['/api/group/{memberId}'].get.responses['$status'].content['application/json']"
          ) {
            exists()
          }
        }
        jsonPath("$.components.schemas.GroupProblemResponse.properties.code.enum") {
          value(
            org.hamcrest.Matchers.hasItems("INVALID_PARAMETER", "FORBIDDEN", "VALIDATION_FAILED")
          )
        }
      }
    mockMvc.get("/swagger-ui/index.html") { internal() }.problem(404, "RESOURCE_NOT_FOUND")
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
    const val ID_HEADER = "X-Internal-Service-Id"
    const val KEY_HEADER = "X-Internal-Service-Key"

    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) = TestProperties.register(registry)

    @JvmStatic
    fun blockedMethods() =
      listOf("HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE", "TRACE").flatMap { method ->
        listOf(
            "/api/groups/summary?memberIds=tu3fgxd45k",
            "/api/group/tu3fgxd45k",
            "/actuator/health",
            "/v3/api-docs",
            "/test-probe/page",
          )
          .map { arrayOf(method, it) }
      }
  }
}
