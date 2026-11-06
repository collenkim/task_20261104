package task.member.infrastructure.client

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.request
import task.member.support.DefaultBearerTokenConfiguration
import task.member.support.TestJwt
import task.member.support.TestProperties
import task.member.support.TestcontainersConfiguration

/**
 * 실제 HTTP로 그룹 명세를 읽어 공개 경로까지 전달하는 계약·실패 변환 검증(POL-API-004).
 *
 * 중계는 local 전용 설정(`task.group-proxy.enabled`)이 켜졌을 때만 등록되므로 이 테스트에서 직접 켠다(POL-SEC-006).
 */
@SpringBootTest(properties = [TestProperties.NO_LOCAL_SECRETS, "task.group-proxy.enabled=true"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, DefaultBearerTokenConfiguration::class)
class GroupApiDocsTests {
  @Autowired lateinit var mockMvc: MockMvc

  @BeforeEach
  fun reset() {
    status.set(200)
    delay.set(0)
    firstDelay.set(0)
    bodyDelay.set(0)
    hits.set(0)
    receivedKey.set(null)
    receivedCaller.set(null)
    receivedPath.set(null)
    receivedQuery.set(null)
    body.set(SPEC)
    contentType.set("application/json")
  }

  @Test
  fun `그룹 명세는 서버 키로 조회하며 사용자 헤더와 내부 호출 정보가 노출되지 않는다`() {
    val response =
      mockMvc
        .get("/internal-docs/group") {
          header("X-Internal-Service-Key", "user-supplied")
        }
        .andExpect {
          status { isOk() }
          header { string("Cache-Control", "no-store") }
          jsonPath("$.paths['/api/groups/summary'].get.summary") { value("그룹 조회") }
          jsonPath("$.servers[0].url") { value("/internal-api/group") }
          jsonPath("$.security[0].bearerAuth") { exists() }
          jsonPath("$.security[0].internalServiceKey") { doesNotExist() }
          jsonPath("$.paths['/api/groups/summary'].servers") { doesNotExist() }
          jsonPath("$.paths['/api/groups/summary'].get.security") { doesNotExist() }
          jsonPath("$.components.securitySchemes.bearerAuth.scheme") { value("bearer") }
          jsonPath("$.components.securitySchemes.internalServiceKey") { doesNotExist() }
        }
        .andReturn()
        .response
        .contentAsString
    assertThat(receivedKey.get()).isEqualTo(TestProperties.internalKey)
    assertThat(response).doesNotContain(TestProperties.internalKey, "172.30.104.3")
    assertThat(hits.get()).isEqualTo(1)
  }

  @Test
  fun `그룹 503은 전용 이용 불가로 바꾸며 응답 본문을 노출하거나 재시도하지 않는다`() {
    status.set(503)
    body.set("upstream-private-detail")
    mockMvc.get("/internal-docs/group").andExpect {
      status { isServiceUnavailable() }
      jsonPath("$.code") { value("SERVICE_UNAVAILABLE") }
      jsonPath("$.type") { value("urn:problem-type:group-service-unavailable") }
      jsonPath("$.title") { value("그룹 서비스 이용 불가") }
    }
    assertThat(hits.get()).isEqualTo(1)
    mockMvc.get("/actuator/health/readiness").andExpect { jsonPath("$.status") { value("UP") } }
    mockMvc.get("/v3/api-docs").andExpect { status { isOk() } }
  }

  @Test
  fun `내부 키 거부는 사용자 인증 오류가 아닌 설정 오류이며 재시도하지 않는다`() {
    for (upstreamStatus in listOf(401, 403)) {
      status.set(upstreamStatus)
      mockMvc.get("/internal-docs/group").andExpect {
        status { isBadGateway() }
        jsonPath("$.code") { value("BAD_GATEWAY") }
        jsonPath("$.type") { value("urn:problem-type:group-upstream-failure") }
      }
    }
    assertThat(hits.get()).isEqualTo(2)
  }

  @Test
  fun `다른 주소로 리다이렉트하지 않고 잘못된 명세는 설정 오류로 알린다`() {
    status.set(302)
    mockMvc.get("/internal-docs/group").andExpect { status { isBadGateway() } }
    assertThat(hits.get()).isEqualTo(1)
    status.set(200)
    body.set("{}")
    mockMvc.get("/internal-docs/group").andExpect {
      status { isBadGateway() }
      jsonPath("$.code") { value("BAD_GATEWAY") }
      jsonPath("$.type") { value("urn:problem-type:group-response-contract-violation") }
    }
  }

  @Test
  fun `응답 시간 초과는 한 번 재시도하고 전용 504로 알린다`() {
    delay.set(800)
    mockMvc.get("/internal-docs/group").andExpect {
      status { isGatewayTimeout() }
      jsonPath("$.code") { value("GATEWAY_TIMEOUT") }
      jsonPath("$.type") { value("urn:problem-type:group-service-timeout") }
    }
    assertThat(hits.get()).isEqualTo(2)
  }

  @Test
  fun `명세 본문 수신 중 시간 초과는 재시도 없이 504로 알린다`() {
    bodyDelay.set(1000)
    mockMvc.get("/internal-docs/group").andExpect {
      status { isGatewayTimeout() }
      jsonPath("$.code") { value("GATEWAY_TIMEOUT") }
      jsonPath("$.type") { value("urn:problem-type:group-service-timeout") }
    }
    assertThat(hits.get()).isEqualTo(1)
  }

  @Test
  fun `잘못된 JSON과 명세 자료형은 재시도 없이 502로 알린다`() {
    val invalidBodies =
      listOf("{", "[]", "null", "", "{}", """{"openapi":3,"info":{},"paths":{}}""")
    for (invalid in invalidBodies) {
      body.set(invalid)
      mockMvc.get("/internal-docs/group").andExpect {
        status { isBadGateway() }
        jsonPath("$.code") { value("BAD_GATEWAY") }
        jsonPath("$.type") {
          value(
            if (invalid == "{") "urn:problem-type:group-response-invalid-json"
            else "urn:problem-type:group-response-contract-violation"
          )
        }
      }
    }
    assertThat(hits.get()).isEqualTo(invalidBodies.size)
  }

  @Test
  fun `JSON 매체 유형만 허용하고 JSON 접미사는 유지한다`() {
    for (invalid in listOf("text/plain", "invalid-media-type")) {
      contentType.set(invalid)
      mockMvc.get("/internal-docs/group").andExpect {
        status { isBadGateway() }
        jsonPath("$.code") { value("BAD_GATEWAY") }
        jsonPath("$.type") { value("urn:problem-type:group-response-unsupported-media-type") }
      }
    }
    contentType.set("application/vnd.openapi+json")
    mockMvc.get("/internal-docs/group").andExpect { status { isOk() } }
    assertThat(hits.get()).isEqualTo(3)
  }

  @Test
  fun `그룹 요약은 멤버의 고정 경로와 서버 키로 실행하며 반복 파라미터를 유지한다`() {
    body.set(SUMMARY)
    mockMvc
      .get("/internal-api/group/api/groups/summary") {
        param("memberIds", "tu3fgxd45k", "dj8fy4bewk")
        param("url", "http://127.0.0.1:1/forbidden")
        header("X-Internal-Service-Id", "browser")
        header("X-Internal-Service-Key", "user-supplied")
      }
      .andExpect {
        status { isOk() }
        content { json(SUMMARY) }
        header { string("Cache-Control", "no-store") }
        header { doesNotExist("Set-Cookie") }
        header { doesNotExist("Location") }
        header { doesNotExist("X-Internal-Service-Key") }
      }
    assertThat(receivedPath.get()).isEqualTo("/api/groups/summary")
    assertThat(receivedQuery.get()).isEqualTo("memberIds=tu3fgxd45k&memberIds=dj8fy4bewk")
    assertThat(receivedCaller.get()).isEqualTo("member-service")
    assertThat(receivedKey.get()).isEqualTo(TestProperties.internalKey)
    assertThat(hits.get()).isEqualTo(1)
  }

  @Test
  fun `그룹 상세와 업무 4xx는 유지하고 그룹 서버 실패는 중계 오류로 구분한다`() {
    body.set(DETAIL)
    mockMvc.get("/internal-api/group/api/group/tu3fgxd45k").andExpect {
      status { isOk() }
      content { json(DETAIL) }
    }
    assertThat(receivedPath.get()).isEqualTo("/api/group/tu3fgxd45k")
    for ((code, error, type) in
      listOf(
        Triple(400, "INVALID_PARAMETER", "about:blank"),
        Triple(403, "FORBIDDEN", "urn:problem-type:internal-access-denied"),
        Triple(404, "RESOURCE_NOT_FOUND", "urn:problem-type:group-membership-not-found"),
        Triple(422, "VALIDATION_FAILED", "about:blank"),
        Triple(500, "INTERNAL_SERVER_ERROR", "urn:problem-type:group-membership-ambiguous"),
        Triple(503, "SERVICE_UNAVAILABLE", "about:blank"),
      )) {
      status.set(code)
      contentType.set("application/json")
      val problem =
        """{"type":"$type","title":"합성 오류","status":$code,"detail":"합성 설명","instance":"/api/group/tu3fgxd45k","code":"$error"}"""
      body.set(problem)
      mockMvc.get("/internal-api/group/api/group/tu3fgxd45k").andExpect {
        status { isEqualTo(if (code == 500) 502 else code) }
        when (code) {
          500 -> {
            jsonPath("$.code") { value("BAD_GATEWAY") }
            jsonPath("$.type") { value("urn:problem-type:group-upstream-failure") }
          }
          503 -> {
            jsonPath("$.code") { value("SERVICE_UNAVAILABLE") }
            jsonPath("$.type") { value("urn:problem-type:group-service-unavailable") }
          }
          else -> content { json(problem) }
        }
      }
    }
    assertThat(hits.get()).isEqualTo(7)
  }

  @Test
  fun `그룹 중계는 임의 경로와 변경 메서드를 제공하지 않고 공개 멤버 명세에도 들어가지 않는다`() {
    mockMvc.get("/internal-api/group/actuator/env").andExpect { status { isNotFound() } }
    mockMvc.get("/internal-api/group/api/other").andExpect { status { isNotFound() } }
    for (method in listOf("POST", "HEAD", "OPTIONS")) {
      mockMvc
        .request(
          org.springframework.http.HttpMethod.valueOf(method),
          "/internal-api/group/api/groups/summary",
        )
        .andExpect {
          status { isMethodNotAllowed() }
          header { string("Allow", "GET") }
        }
    }
    mockMvc.get("/v3/api-docs").andExpect {
      jsonPath("$.paths['/internal-api/group/api/groups/summary']") { doesNotExist() }
      jsonPath("$.paths['/internal-api/group/api/group/{memberId}']") { doesNotExist() }
    }
    assertThat(hits.get()).isZero()
  }

  @Test
  fun `중계는 group read scope를 요구하고 명세 전달은 데이터가 없어 토큰 없이 열린다`() {
    body.set(DETAIL)
    mockMvc
      .get("/internal-api/group/api/group/tu3fgxd45k") { header("Authorization", "") }
      .andExpect {
        status { isUnauthorized() }
        jsonPath("$.code") { value("UNAUTHORIZED") }
      }
    mockMvc
      .get("/internal-api/group/api/group/tu3fgxd45k") {
        header("Authorization", TestJwt.bearer(TestJwt.token(scopes = listOf("member.read"))))
      }
      .andExpect {
        status { isForbidden() }
        jsonPath("$.code") { value("FORBIDDEN") }
      }
    assertThat(hits.get()).isZero()
    body.set(SPEC)
    mockMvc
      .get("/internal-docs/group") { header("Authorization", "") }
      .andExpect {
        status { isOk() }
      }
    assertThat(hits.get()).isEqualTo(1)
  }

  @Test
  fun `중계의 비JSON 응답 손상 오류 리다이렉트는 내부 원문을 노출하지 않는다`() {
    for (invalid in
      listOf(
        "not-json",
        "[]",
        "{} {}",
        "{\"group\":[],\"group\":[]}",
        "x".repeat(1024 * 1024 + 1),
      )) {
      body.set(invalid)
      mockMvc.get("/internal-api/group/api/groups/summary").andExpect {
        status { isBadGateway() }
        jsonPath("$.code") { value("BAD_GATEWAY") }
        jsonPath("$.type") {
          value(
            when {
              invalid == "[]" -> "urn:problem-type:group-response-contract-violation"
              invalid.length > 1024 * 1024 -> "urn:problem-type:group-response-too-large"
              else -> "urn:problem-type:group-response-invalid-json"
            }
          )
        }
      }
    }
    body.set("upstream-private-detail")
    contentType.set("text/html")
    mockMvc.get("/internal-api/group/api/group/tu3fgxd45k").andExpect {
      status { isBadGateway() }
      content {
        string(
          org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("upstream-private-detail"))
        )
      }
    }
    status.set(302)
    mockMvc.get("/internal-api/group/api/group/tu3fgxd45k").andExpect {
      status { isBadGateway() }
    }
    status.set(500)
    contentType.set("application/json")
    body.set("{\"status\":404,\"code\":\"NOT_FOUND\"}")
    mockMvc.get("/internal-api/group/api/group/tu3fgxd45k").andExpect {
      status { isBadGateway() }
    }
    assertThat(hits.get()).isEqualTo(8)
  }

  @Test
  fun `중계도 기존 제한 시간과 조회 재시도를 적용하고 응답 시간 초과는 504이다`() {
    body.set(SUMMARY)
    delay.set(800)
    mockMvc.get("/internal-api/group/api/groups/summary").andExpect {
      status { isGatewayTimeout() }
      jsonPath("$.code") { value("GATEWAY_TIMEOUT") }
      jsonPath("$.type") { value("urn:problem-type:group-service-timeout") }
    }
    assertThat(hits.get()).isEqualTo(2)
  }

  @Test
  fun `중계는 잘못된 입력의 특수문자도 URL 구조로 해석하지 않고 그룹 검증에 전달한다`() {
    body.set(SUMMARY)
    val invalidId = "bad /?&+{}"
    mockMvc
      .get("/internal-api/group/api/groups/summary") { param("memberIds", invalidId) }
      .andExpect { status { isOk() } }
    assertThat(receivedPath.get()).isEqualTo("/api/groups/summary")
    assertThat(java.net.URLDecoder.decode(receivedQuery.get(), Charsets.UTF_8))
      .isEqualTo("memberIds=" + invalidId)
    assertThat(hits.get()).isEqualTo(1)
  }

  @Test
  fun `중계는 모든 깊이의 추가 필드를 제거하고 여섯 상세 필드만 반환한다`() {
    val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
    val detail = mapper.readTree(DETAIL) as tools.jackson.databind.node.ObjectNode
    detail.put("futureField", "private-value")
    body.set(detail.toString())
    val response =
      mockMvc
        .get("/internal-api/group/api/group/tu3fgxd45k")
        .andExpect {
          status { isOk() }
          content { json(DETAIL) }
          jsonPath("$.futureField") { doesNotExist() }
        }
        .andReturn()
        .response
        .contentAsString
    assertThat(mapper.readTree(response).propertyNames().toSet())
      .containsExactlyInAnyOrder(
        "memberId",
        "groupId",
        "groupName",
        "registeredDate",
        "pricing",
        "permission",
      )
    val summary = mapper.readTree(SUMMARY) as tools.jackson.databind.node.ObjectNode
    summary.put("futureField", "private-value")
    val group = summary.path("group").path(0) as tools.jackson.databind.node.ObjectNode
    group.put("futureField", "private-value")
    (group.path("members").path(0) as tools.jackson.databind.node.ObjectNode).put(
      "futureField",
      "private-value",
    )
    body.set(summary.toString())
    val result =
      mockMvc
        .get("/internal-api/group/api/groups/summary")
        .andExpect {
          status { isOk() }
          content { json(SUMMARY) }
        }
        .andReturn()
        .response
        .contentAsString
    assertThat(result).doesNotContain("futureField", "private-value")
  }

  @Test
  fun `중계는 필수 누락 타입 null 시각과 여러 소속을 502 계약 오류로 구분한다`() {
    val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
    val changes: List<(tools.jackson.databind.node.ObjectNode) -> Unit> =
      listOf(
        { it.remove("permission") },
        { it.putNull("groupId") },
        { it.put("groupName", 123) },
        { it.put("registeredDate", "2026-02-30 12:00") },
        { it.put("memberId", "BAD") },
      )
    for (change in changes) {
      val value = mapper.readTree(DETAIL) as tools.jackson.databind.node.ObjectNode
      change(value)
      body.set(value.toString())
      mockMvc.get("/internal-api/group/api/group/tu3fgxd45k").andExpect {
        status { isBadGateway() }
        jsonPath("$.code") { value("BAD_GATEWAY") }
        jsonPath("$.type") { value("urn:problem-type:group-response-contract-violation") }
      }
    }
    val summary = mapper.readTree(SUMMARY) as tools.jackson.databind.node.ObjectNode
    val groups = summary.path("group") as tools.jackson.databind.node.ArrayNode
    val other = groups.path(0).deepCopy() as tools.jackson.databind.node.ObjectNode
    other.put("groupId", "group00002")
    groups.add(other)
    body.set(summary.toString())
    mockMvc.get("/internal-api/group/api/groups/summary").andExpect {
      status { isBadGateway() }
      jsonPath("$.code") { value("BAD_GATEWAY") }
      jsonPath("$.type") { value("urn:problem-type:group-response-contract-violation") }
    }
  }

  @Test
  fun `중계는 summary count와 반환 목록 크기가 다르면 502로 거부한다`() {
    for (count in listOf(0, 2)) {
      body.set(SUMMARY.replace("\"totalMemberCount\":1", "\"totalMemberCount\":$count"))
      mockMvc.get("/internal-api/group/api/groups/summary").andExpect {
        status { isBadGateway() }
        jsonPath("$.code") { value("BAD_GATEWAY") }
        jsonPath("$.type") { value("urn:problem-type:group-response-contract-violation") }
      }
    }
  }

  @Test
  fun `중계는 본문 수신 시간 초과를 504로 구분한다`() {
    body.set(DETAIL)
    bodyDelay.set(1000)
    mockMvc.get("/internal-api/group/api/group/tu3fgxd45k").andExpect {
      status { isGatewayTimeout() }
      jsonPath("$.code") { value("GATEWAY_TIMEOUT") }
      jsonPath("$.type") { value("urn:problem-type:group-service-timeout") }
    }
    assertThat(hits.get()).isEqualTo(1)
  }

  @Test
  fun `재시도로 성공한 헤더 뒤의 본문에도 해당 시도의 시간 제한을 적용한다`() {
    body.set(DETAIL)
    firstDelay.set(800)
    bodyDelay.set(1000)
    mockMvc.get("/internal-api/group/api/group/tu3fgxd45k").andExpect {
      status { isGatewayTimeout() }
      jsonPath("$.code") { value("GATEWAY_TIMEOUT") }
      jsonPath("$.type") { value("urn:problem-type:group-service-timeout") }
    }
    assertThat(hits.get()).isEqualTo(2)
  }

  @Test
  fun `명세 크기 초과와 잘못된 components 자료형은 전용 502로 구분한다`() {
    body.set(" ".repeat(2 * 1024 * 1024 + 1))
    mockMvc.get("/internal-docs/group").andExpect {
      status { isBadGateway() }
      jsonPath("$.code") { value("BAD_GATEWAY") }
      jsonPath("$.type") { value("urn:problem-type:group-response-too-large") }
    }
    body.set("""{"openapi":"3.1.0","info":{},"paths":{},"components":42}""")
    mockMvc.get("/internal-docs/group").andExpect {
      status { isBadGateway() }
      jsonPath("$.code") { value("BAD_GATEWAY") }
      jsonPath("$.type") { value("urn:problem-type:group-response-contract-violation") }
    }
  }

  companion object {
    // 합성 제공자 응답이며 실제 그룹 코드·테이블에 의존하지 않는다.
    private val status = AtomicInteger(200)
    private val delay = AtomicInteger()
    private val firstDelay = AtomicInteger()
    private val bodyDelay = AtomicInteger()
    private val hits = AtomicInteger()
    private val receivedKey = AtomicReference<String?>()
    private val receivedCaller = AtomicReference<String?>()
    private val receivedPath = AtomicReference<String?>()
    private val receivedQuery = AtomicReference<String?>()
    private val body = AtomicReference<String>()
    private val contentType = AtomicReference("application/json")
    private val executor = Executors.newCachedThreadPool()
    private val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
          val attempt = hits.incrementAndGet()
          receivedCaller.set(exchange.requestHeaders.getFirst("X-Internal-Service-Id"))
          receivedPath.set(exchange.requestURI.path)
          receivedQuery.set(exchange.requestURI.rawQuery)
          receivedKey.set(exchange.requestHeaders.getFirst("X-Internal-Service-Key"))
          val responseStatus = status.get()
          val responseBody = (body.get() ?: SPEC).toByteArray(Charsets.UTF_8)
          val pause = if (attempt == 1 && firstDelay.get() > 0) firstDelay.get() else delay.get()
          runCatching {
            Thread.sleep(pause.toLong())
            exchange.responseHeaders.set("Content-Type", contentType.get())
            exchange.responseHeaders.set("Location", "/unexpected-redirect")
            exchange.responseHeaders.set("Set-Cookie", "upstream=private")
            exchange.responseHeaders.set("X-Internal-Service-Key", TestProperties.internalKey)
            exchange.sendResponseHeaders(responseStatus, responseBody.size.toLong())
            exchange.responseBody.use {
              if (bodyDelay.get() > 0) {
                it.write(responseBody, 0, 1)
                it.flush()
                Thread.sleep(bodyDelay.get().toLong())
                it.write(responseBody, 1, responseBody.size - 1)
              } else it.write(responseBody)
            }
          }
          exchange.close()
        }
        this.executor = GroupApiDocsTests.executor
        start()
      }

    @JvmStatic
    @AfterAll
    fun stop() {
      server.stop(0)
      executor.shutdownNow()
    }

    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) {
      TestProperties.register(registry)
      registry.add("task.group-client.base-url") { "http://127.0.0.1:${server.address.port}" }
      registry.add("task.group-client.read-timeout") { Duration.ofMillis(200) }
    }

    private const val SUMMARY =
      """{"group":[{"groupId":"arutjf9348","groupName":"Test1","totalMemberCount":1,"members":[{"memberId":"tu3fgxd45k","permission":"Editor"}]}]}"""
    private const val DETAIL =
      """{"memberId":"tu3fgxd45k","groupId":"arutjf9348","groupName":"Test1","registeredDate":"2026-08-15 13:00","pricing":"Premium","permission":"Editor"}"""

    private const val SPEC =
      """{
      "openapi":"3.1.0", "info":{"title":"그룹","version":"0.1.0"},
      "servers":[{"url":"http://172.30.104.3:8082"}],
      "security":[{"internalServiceKey":[]}],
      "components":{"securitySchemes":{"internalServiceKey":{"type":"apiKey","in":"header","name":"X-Internal-Service-Key"}}},
      "paths":{"/api/group/{memberId}":{"get":{"responses":{"200":{"description":"상세"}}}},"/api/groups/summary":{"servers":[{"url":"http://172.30.104.3:8082"}],"get":{
        "summary":"그룹 조회", "security":[{"internalServiceKey":[]}],
        "servers":[{"url":"http://172.30.104.3:8082"}],"responses":{"200":{"description":"조회 성공"}}
      }}}
    }"""
  }
}
