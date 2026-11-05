package task.member

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
import org.springframework.test.web.servlet.post
import task.member.support.TestProperties
import task.member.support.TestcontainersConfiguration

/** 실제 HTTP로 그룹 명세를 읽어 공개 경로까지 전달하는 계약·실패 변환 검증(POL-API-004). */
@SpringBootTest(properties = [TestProperties.NO_LOCAL_SECRETS])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class GroupApiDocsTests {
  @Autowired lateinit var mockMvc: MockMvc

  @BeforeEach
  fun reset() {
    status.set(200)
    delay.set(0)
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
          jsonPath("$.paths['/groups'].get.summary") { value("그룹 조회") }
          jsonPath("$.servers[0].url") { value("/internal-api/group") }
          jsonPath("$.security") { doesNotExist() }
          jsonPath("$.paths['/groups'].servers") { doesNotExist() }
          jsonPath("$.paths['/groups'].get.security") { doesNotExist() }
          jsonPath("$.components.securitySchemes") { doesNotExist() }
        }
        .andReturn()
        .response
        .contentAsString
    assertThat(receivedKey.get()).isEqualTo(TestProperties.internalKey)
    assertThat(response).doesNotContain(TestProperties.internalKey, "172.30.104.3")
    assertThat(hits.get()).isEqualTo(1)
  }

  @Test
  fun `그룹 5xx는 전용 503으로 바꾸며 응답 본문을 노출하거나 재시도하지 않는다`() {
    status.set(503)
    body.set("upstream-private-detail")
    mockMvc.get("/internal-docs/group").andExpect {
      status { isServiceUnavailable() }
      jsonPath("$.code") { value("GROUP_SERVICE_UNAVAILABLE") }
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
        status { isInternalServerError() }
        jsonPath("$.code") { value("INTERNAL_ERROR") }
      }
    }
    assertThat(hits.get()).isEqualTo(2)
  }

  @Test
  fun `다른 주소로 리다이렉트하지 않고 잘못된 명세는 설정 오류로 알린다`() {
    status.set(302)
    mockMvc.get("/internal-docs/group").andExpect { status { isInternalServerError() } }
    assertThat(hits.get()).isEqualTo(1)
    status.set(200)
    body.set("{}")
    mockMvc.get("/internal-docs/group").andExpect {
      status { isInternalServerError() }
      jsonPath("$.code") { value("INTERNAL_ERROR") }
    }
  }

  @Test
  fun `응답 시간 초과는 한 번 재시도하고 전용 503으로 알린다`() {
    delay.set(800)
    mockMvc.get("/internal-docs/group").andExpect {
      status { isServiceUnavailable() }
      jsonPath("$.code") { value("GROUP_SERVICE_UNAVAILABLE") }
    }
    assertThat(hits.get()).isEqualTo(2)
  }

  @Test
  fun `명세 본문 수신 중 시간 초과는 재시도 없이 503으로 알린다`() {
    bodyDelay.set(1000)
    mockMvc.get("/internal-docs/group").andExpect {
      status { isServiceUnavailable() }
      jsonPath("$.code") { value("GROUP_SERVICE_UNAVAILABLE") }
    }
    assertThat(hits.get()).isEqualTo(1)
  }

  @Test
  fun `잘못된 JSON과 명세 자료형은 재시도 없이 500으로 알린다`() {
    val invalidBodies =
      listOf("{", "[]", "null", "", "{}", """{"openapi":3,"info":{},"paths":{}}""")
    for (invalid in invalidBodies) {
      body.set(invalid)
      mockMvc.get("/internal-docs/group").andExpect {
        status { isInternalServerError() }
        jsonPath("$.code") { value("INTERNAL_ERROR") }
      }
    }
    assertThat(hits.get()).isEqualTo(invalidBodies.size)
  }

  @Test
  fun `JSON 매체 유형만 허용하고 JSON 접미사는 유지한다`() {
    for (invalid in listOf("text/plain", "invalid-media-type")) {
      contentType.set(invalid)
      mockMvc.get("/internal-docs/group").andExpect {
        status { isInternalServerError() }
        jsonPath("$.code") { value("INTERNAL_ERROR") }
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
  fun `그룹 상세 여섯 필드와 그룹 오류 상태 본문은 중계에서 변환하지 않는다`() {
    body.set(DETAIL)
    mockMvc.get("/internal-api/group/api/group/tu3fgxd45k").andExpect {
      status { isOk() }
      content { json(DETAIL) }
    }
    assertThat(receivedPath.get()).isEqualTo("/api/group/tu3fgxd45k")
    for ((code, error) in
      listOf(
        400 to "VALIDATION_FAILED",
        403 to "INTERNAL_ACCESS_DENIED",
        404 to "GROUP_MEMBERSHIP_NOT_FOUND",
        500 to "GROUP_MEMBERSHIP_AMBIGUOUS",
        503 to "SERVICE_UNAVAILABLE",
      )) {
      status.set(code)
      contentType.set("application/problem+json")
      val problem =
        """{"type":"about:blank","title":"합성 오류","status":$code,"detail":"합성 설명","instance":"/api/group/tu3fgxd45k","code":"$error"}"""
      body.set(problem)
      mockMvc.get("/internal-api/group/api/group/tu3fgxd45k").andExpect {
        status { isEqualTo(code) }
        content { json(problem) }
      }
    }
    assertThat(hits.get()).isEqualTo(6)
  }

  @Test
  fun `그룹 중계는 임의 경로와 변경 메서드를 제공하지 않고 공개 멤버 명세에도 들어가지 않는다`() {
    mockMvc.get("/internal-api/group/actuator/env").andExpect { status { isNotFound() } }
    mockMvc.get("/internal-api/group/api/other").andExpect { status { isNotFound() } }
    mockMvc.post("/internal-api/group/api/groups/summary").andExpect {
      status { isMethodNotAllowed() }
    }
    mockMvc.get("/v3/api-docs").andExpect {
      jsonPath("$.paths['/internal-api/group/api/groups/summary']") { doesNotExist() }
      jsonPath("$.paths['/internal-api/group/api/group/{memberId}']") { doesNotExist() }
    }
    assertThat(hits.get()).isZero()
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
        status { isInternalServerError() }
        jsonPath("$.code") { value("INTERNAL_ERROR") }
      }
    }
    body.set("upstream-private-detail")
    contentType.set("text/html")
    mockMvc.get("/internal-api/group/api/group/tu3fgxd45k").andExpect {
      status { isInternalServerError() }
      content {
        string(
          org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("upstream-private-detail"))
        )
      }
    }
    status.set(302)
    mockMvc.get("/internal-api/group/api/group/tu3fgxd45k").andExpect {
      status { isInternalServerError() }
    }
    status.set(500)
    contentType.set("application/problem+json")
    body.set("{\"status\":404,\"code\":\"NOT_FOUND\"}")
    mockMvc.get("/internal-api/group/api/group/tu3fgxd45k").andExpect {
      status { isInternalServerError() }
    }
    assertThat(hits.get()).isEqualTo(8)
  }

  @Test
  fun `중계도 기존 제한 시간과 조회 재시도를 적용하고 연결 장애는 503이다`() {
    body.set(SUMMARY)
    delay.set(800)
    mockMvc.get("/internal-api/group/api/groups/summary").andExpect {
      status { isServiceUnavailable() }
      jsonPath("$.code") { value("GROUP_SERVICE_UNAVAILABLE") }
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

  companion object {
    private val status = AtomicInteger(200)
    private val delay = AtomicInteger()
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
          hits.incrementAndGet()
          receivedCaller.set(exchange.requestHeaders.getFirst("X-Internal-Service-Id"))
          receivedPath.set(exchange.requestURI.path)
          receivedQuery.set(exchange.requestURI.rawQuery)
          receivedKey.set(exchange.requestHeaders.getFirst("X-Internal-Service-Key"))
          val responseStatus = status.get()
          val responseBody = (body.get() ?: SPEC).toByteArray(Charsets.UTF_8)
          val pause = delay.get()
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
      """{"group":[{"groupId":"arutjf9348","groupName":"Test1","totalMemberCount":2,"members":[{"memberId":"tu3fgxd45k","permission":"Editor"}]}]}"""
    private const val DETAIL =
      """{"memberId":"tu3fgxd45k","groupId":"arutjf9348","groupName":"Test1","registeredDate":"2026-08-15 13:00","pricing":"Premium","permission":"Editor"}"""

    private const val SPEC =
      """{
      "openapi":"3.1.0", "info":{"title":"그룹","version":"0.1.0"},
      "servers":[{"url":"http://172.30.104.3:8082"}],
      "security":[{"internalServiceKey":[]}],
      "components":{"securitySchemes":{"internalServiceKey":{"type":"apiKey","in":"header","name":"X-Internal-Service-Key"}}},
      "paths":{"/groups":{"servers":[{"url":"http://172.30.104.3:8082"}],"get":{
        "summary":"그룹 조회", "security":[{"internalServiceKey":[]}],
        "servers":[{"url":"http://172.30.104.3:8082"}],"responses":{"200":{"description":"조회 성공"}}
      }}}
    }"""
  }
}
