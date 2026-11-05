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
          jsonPath("$.servers") { doesNotExist() }
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

  companion object {
    private val status = AtomicInteger(200)
    private val delay = AtomicInteger()
    private val bodyDelay = AtomicInteger()
    private val hits = AtomicInteger()
    private val receivedKey = AtomicReference<String?>()
    private val body = AtomicReference<String>()
    private val contentType = AtomicReference("application/json")
    private val executor = Executors.newCachedThreadPool()
    private val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v3/api-docs") { exchange ->
          hits.incrementAndGet()
          receivedKey.set(exchange.requestHeaders.getFirst("X-Internal-Service-Key"))
          val responseStatus = status.get()
          val responseBody = (body.get() ?: SPEC).toByteArray(Charsets.UTF_8)
          val pause = delay.get()
          runCatching {
            Thread.sleep(pause.toLong())
            exchange.responseHeaders.set("Content-Type", contentType.get())
            exchange.responseHeaders.set("Location", "/unexpected-redirect")
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
