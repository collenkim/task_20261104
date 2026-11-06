package task.member.presentation.error

import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
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
import task.member.support.TestJwt
import task.member.support.TestProperties
import task.member.support.TestcontainersConfiguration

/**
 * 공통 정책이 멤버 서비스에 적용됐는지 확인한다: JWT 인가(401·403)·GET 전용(405)·오류 코드표·페이지네이션·상태 확인·API 문서·DB 마이그레이션.
 *
 * 인증을 직접 확인하므로 기본 Bearer 설정을 가져오지 않고 요청마다 헤더를 정한다.
 */
@SpringBootTest(properties = [TestProperties.NO_LOCAL_SECRETS])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CommonPolicyTests {
  @Autowired lateinit var mockMvc: MockMvc
  @Autowired lateinit var jdbcTemplate: JdbcTemplate

  private fun MockHttpServletRequestDsl.bearer(token: String = TestJwt.defaultToken) {
    header(HttpHeaders.AUTHORIZATION, TestJwt.bearer(token))
  }

  private fun ResultActionsDsl.problem(status: Int, code: String) = andExpect {
    status { isEqualTo(status) }
    content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
    jsonPath("$.status") { value(status) }
    jsonPath("$.code") { value(code) }
    jsonPath("$.type") { value(ProblemType.DEFAULT) }
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
  fun `토큰이 없으면 업무 경로와 그 밖의 경로 모두 401 UNAUTHORIZED와 Bearer 요구를 돌려준다`() {
    for (path in listOf("/api/members", "/api/member/abcdefghij", "/test-probe/page", "/none")) {
      mockMvc.get(path).problem(401, "UNAUTHORIZED").andExpect {
        // RFC 6750 Bearer 요구와 RFC 9728 보호 자원 메타데이터 위치를 함께 알린다.
        header {
          string(
            HttpHeaders.WWW_AUTHENTICATE,
            "Bearer resource_metadata=\"http://localhost/.well-known/oauth-protected-resource\"",
          )
        }
      }
    }
    // Basic 등 다른 방식은 Bearer 토큰으로 읽지 않는다.
    mockMvc
      .get("/api/members") { header(HttpHeaders.AUTHORIZATION, "Basic " + "dXNlcjpwdw") }
      .problem(401, "UNAUTHORIZED")
  }

  @Test
  fun `보호 자원 메타데이터는 토큰 없이 GET으로만 열리고 Bearer 헤더 방식만 알린다`() {
    val body =
      mockMvc
        .get("/.well-known/oauth-protected-resource")
        .andExpect {
          status { isOk() }
          jsonPath("$.resource") { value("http://localhost") }
          jsonPath("$.resource_name") { value("member-service") }
          jsonPath("$.bearer_methods_supported") { value(org.hamcrest.Matchers.contains("header")) }
          jsonPath("$.scopes_supported") {
            value(org.hamcrest.Matchers.contains("member.read", "group.read"))
          }
          jsonPath("$.tls_client_certificate_bound_access_tokens") { value(false) }
        }
        .andReturn()
        .response
        .contentAsString
    assertFalse(body.contains("BEGIN"), "키 자료를 노출하지 않는다")
    mockMvc.request(HttpMethod.POST, "/.well-known/oauth-protected-resource").andExpect {
      status { isEqualTo(405) }
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidTokens")
  fun `서명·발급자·대상·시간·subject 조건을 어긴 토큰은 401이고 토큰 값을 응답에 넣지 않는다`(
    case: String,
    token: String,
  ) {
    mockMvc
      .get("/api/members") { bearer(token) }
      .problem(401, "UNAUTHORIZED")
      .andExpect {
        header {
          string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer error=\"invalid_token\""))
        }
        content { string(not(containsString(token))) }
      }
  }

  @Test
  fun `scope가 모자라면 403 FORBIDDEN이고 경로마다 필요한 scope가 다르다`() {
    val groupOnly = TestJwt.token(scopes = listOf("group.read"))
    val memberOnly = TestJwt.token(scopes = listOf("member.read"))
    val none = TestJwt.token(scopes = emptyList())
    for (token in listOf(groupOnly, none)) {
      mockMvc
        .get("/api/members") { bearer(token) }
        .problem(403, "FORBIDDEN")
        .andExpect {
          header {
            string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"insufficient_scope\""))
          }
        }
    }
    mockMvc
      .get("/internal-api/group/api/group/abcdefghij") { bearer(memberOnly) }
      .problem(403, "FORBIDDEN")
    mockMvc.get("/api/members") { bearer(memberOnly) }.andExpect { status { isOk() } }
  }

  @Test
  fun `시계 오차 허용 범위 안의 만료는 통과한다`() {
    val justExpired =
      TestJwt.token(issuedAt = Instant.now().minusSeconds(70), lifetime = Duration.ofSeconds(60))
    mockMvc.get("/test-probe/page") { bearer(justExpired) }.andExpect { status { isOk() } }
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("blockedMethods")
  fun `GET 외 메서드는 인증·핸들러보다 먼저 405와 Allow GET으로 막는다`(method: String, path: String) {
    val result =
      mockMvc.request(HttpMethod.valueOf(method), path).andExpect {
        status { isEqualTo(405) }
        header { string(HttpHeaders.ALLOW, "GET") }
      }
    // HEAD 응답 본문은 실제 서버가 보내지 않으므로 본문 검사는 HEAD 밖에서만 한다.
    if (method != "HEAD") result.problem(405, "METHOD_NOT_ALLOWED")
  }

  @Test
  fun `오류 응답은 코드표의 code를 쓰고 내부 정보를 넣지 않는다`() {
    mockMvc.get("/test-probe/none") { bearer() }.problem(404, "RESOURCE_NOT_FOUND")
    mockMvc.get("/test-probe/number?value=abc") { bearer() }.problem(400, "INVALID_PARAMETER")
    mockMvc.get("/test-probe/number") { bearer() }.problem(400, "INVALID_PARAMETER")
    mockMvc.get("/test-probe/validated?value=0") { bearer() }.problem(422, "VALIDATION_FAILED")
    mockMvc.get("/test-probe/validated?value=1") { bearer() }.andExpect { status { isOk() } }
    mockMvc
      .get("/test-probe/page") {
        bearer()
        accept = MediaType.APPLICATION_XML
      }
      .andExpect { status { isEqualTo(406) } }
    mockMvc
      .get("/test-probe/boom") { bearer() }
      .problem(500, "INTERNAL_SERVER_ERROR")
      .andExpect {
        content { string(not(containsString("내부 상세"))) }
        jsonPath("$.title") { value("서버 내부 오류") }
        jsonPath("$.detail") { value("요청을 처리하지 못했습니다.") }
      }
  }

  @Test
  fun `요청이 통과하고 페이지 크기는 기본 20, 최대 100이다`() {
    mockMvc
      .get("/test-probe/page") { bearer() }
      .andExpect {
        status { isOk() }
        jsonPath("$.page") { value(0) }
        jsonPath("$.size") { value(20) }
      }
    mockMvc
      .get("/test-probe/page?size=1000") { bearer() }
      .andExpect {
        jsonPath("$.size") { value(100) }
      }
  }

  @Test
  fun `명세는 Bearer 인증과 업무 API의 코드표 오류 응답을 노출하고 내부 문서 전달 경로는 제외한다`() {
    mockMvc.get("/v3/api-docs").andExpect {
      status { isOk() }
      jsonPath("$.info.version") { value("0.4.0") }
      jsonPath("$.security[0].bearerAuth") { exists() }
      jsonPath("$.components.securitySchemes.bearerAuth.scheme") { value("bearer") }
      jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat") { value("JWT") }
      jsonPath("$.paths['/internal-docs/group']") { doesNotExist() }
      jsonPath("$.paths['/internal-api/group/api/groups/summary']") { doesNotExist() }
      for (status in listOf("400", "401", "403", "405", "406", "422", "429", "500", "503")) {
        jsonPath(
          "$.paths['/api/members'].get.responses['$status'].content['application/problem+json']"
        ) {
          exists()
        }
      }
      for (status in listOf("400", "401", "403", "404", "405", "406", "429", "500", "503")) {
        jsonPath(
          "$.paths['/api/member/{memberId}'].get.responses['$status'].content['application/problem+json']"
        ) {
          exists()
        }
      }
      jsonPath("$.components.schemas.MemberProblemResponse.properties.code.enum") {
        value(
          org.hamcrest.Matchers.hasItems(
            "INVALID_PARAMETER",
            "TOO_MANY_REQUESTS",
            "GATEWAY_TIMEOUT",
          )
        )
      }
    }
  }

  @Test
  fun `기본 프로필은 Swagger에 토큰을 넣지 않고 그룹 중계 경로를 등록하지 않는다`() {
    mockMvc.get("/swagger-ui/index.html").andExpect { status { isOk() } }
    mockMvc.get("/swagger-ui/swagger-initializer.js").andExpect {
      status { isOk() }
      header { string(HttpHeaders.CACHE_CONTROL, "no-store") }
      content { string(containsString("\"accessToken\":null")) }
      content { string(containsString("\"groupProxyEnabled\":false")) }
      content { string(not(containsString(TestProperties.internalKey))) }
    }
    mockMvc.get("/internal-docs/group").problem(404, "RESOURCE_NOT_FOUND")
    mockMvc
      .get("/internal-api/group/api/group/abcdefghij") { bearer() }
      .problem(404, "RESOURCE_NOT_FOUND")
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

    @JvmStatic
    fun invalidTokens() =
      listOf(
        arrayOf("다른 키 서명", TestJwt.token(signWithOtherKey = true)),
        arrayOf("발급자 불일치", TestJwt.token(issuer = "other-issuer")),
        arrayOf("발급자 없음", TestJwt.token(issuer = null)),
        arrayOf("대상 불일치", TestJwt.token(audience = "group-service")),
        arrayOf("대상 없음", TestJwt.token(audience = null)),
        arrayOf("subject 없음", TestJwt.token(subject = null)),
        arrayOf("만료 시각 없음", TestJwt.token(lifetime = null)),
        arrayOf(
          "만료(허용 오차 초과)",
          TestJwt.token(
            issuedAt = Instant.now().minusSeconds(600),
            lifetime = Duration.ofMinutes(1),
          ),
        ),
        arrayOf("사용 시작 전", TestJwt.token(notBefore = Instant.now().plusSeconds(600))),
        arrayOf("형식 오류", listOf("not", "a", "jwt").joinToString(".")),
      )

    @JvmStatic
    fun blockedMethods() =
      listOf("HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE", "TRACE").flatMap { method ->
        listOf(
            "/api/members",
            "/api/member/abcdefghij",
            "/actuator/health",
            "/v3/api-docs",
            "/swagger-ui/swagger-initializer.js",
            "/test-probe/page",
          )
          .map { arrayOf(method, it) }
      }
  }
}
