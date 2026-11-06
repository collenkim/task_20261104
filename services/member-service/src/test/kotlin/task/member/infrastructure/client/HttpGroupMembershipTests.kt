package task.member.infrastructure.client

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.web.client.RestClient
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.exception.GroupServiceUnavailableException
import task.member.application.port.output.GroupMembershipReader
import task.member.infrastructure.client.group.GroupMembershipDecoder
import task.member.infrastructure.client.group.HttpGroupMembershipReader
import task.member.infrastructure.fixture.group.FixtureGroupMembershipReader
import task.member.support.GroupMembershipSamples
import task.member.support.TestProperties
import tools.jackson.databind.json.JsonMapper

/** 실제 HTTP로 직렬화·키·장애 분류·기존 재시도 경계를 확인한다. 실제 그룹 제공자 검증은 아니다(POL-TEST-004·006). */
class HttpGroupMembershipTests {
  private val status = AtomicInteger(200)
  private val headerDelay = AtomicInteger()
  private val bodyDelay = AtomicInteger()
  private val body = AtomicReference(GroupMembershipSamples.SUMMARY)
  private val contentType = AtomicReference("application/json")
  private val requests = ConcurrentLinkedQueue<Request>()
  private val executor = Executors.newCachedThreadPool()
  private lateinit var server: HttpServer
  private val decoder = GroupMembershipDecoder(JsonMapper.builder().build())

  @BeforeEach
  fun startServer() {
    server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
          exchange.use {
            requests.add(
              Request(
                it.requestMethod,
                it.requestURI.path,
                it.requestURI.rawQuery ?: "",
                it.requestHeaders[GroupClientConfiguration.INTERNAL_HEADER].orEmpty(),
                it.requestBody.readAllBytes().size,
              )
            )
            try {
              Thread.sleep(headerDelay.get().toLong())
              val bytes = body.get().toByteArray(Charsets.UTF_8)
              it.responseHeaders.set("Content-Type", contentType.get())
              it.responseHeaders.set("Location", "/unexpected-redirect")
              it.sendResponseHeaders(status.get(), bytes.size.toLong())
              it.responseBody.use { out ->
                if (bodyDelay.get() > 0 && bytes.isNotEmpty()) {
                  out.write(bytes, 0, 1)
                  out.flush()
                  Thread.sleep(bodyDelay.get().toLong())
                  out.write(bytes, 1, bytes.size - 1)
                } else out.write(bytes)
              }
            } catch (_: IOException) {
              // 시간 초과 검증에서 클라이언트가 먼저 연결을 닫는다.
            } catch (_: InterruptedException) {
              Thread.currentThread().interrupt()
            }
          }
        }
        this.executor = this@HttpGroupMembershipTests.executor
        start()
      }
  }

  @AfterEach
  fun stopServer() {
    server.stop(0)
    executor.shutdownNow()
  }

  @Test
  fun `업무 응답은 1MiB 경계를 허용하고 초과하면 재시도 없이 계약 오류다`() {
    val valid = """{"group":[]}"""
    body.set(valid + " ".repeat(1024 * 1024 - valid.toByteArray().size))
    assertTrue(reader().findByMemberIds(listOf("member0001")).getValue("member0001").isEmpty())
    requests.clear()
    body.set(body.get() + " ")
    assertFailsWith<GroupServiceConfigurationException> {
      reader().findByMemberIds(listOf("member0001"))
    }
    assertEquals(1, requests.size)
  }

  @Test
  fun `GET 반복 파라미터와 서버 키를 한 번 보내고 순서와 무관하게 소속을 결합한다`() {
    val result = reader().findByMemberIds(listOf("member0001", "member0002"))
    assertEquals(listOf("member0001", "member0002"), result.keys.toList())
    assertEquals(
      listOf("group00002"),
      result.getValue("member0001").map { it.groupId },
    )
    assertEquals(emptyList(), result.getValue("member0002"))
    val request = requests.single()
    assertEquals("GET", request.method)
    assertEquals("/api/groups/summary", request.path)
    assertEquals(
      listOf("memberIds=member0001", "memberIds=member0002"),
      request.query.split('&').map { URLDecoder.decode(it, StandardCharsets.UTF_8) },
    )
    assertEquals(listOf(TestProperties.internalKey), request.keys)
    assertEquals(0, request.bodySize)
  }

  @Test
  fun `100개 경계까지 단일 일괄 요청을 허용하고 요청 위반은 전송 전에 거부한다`() {
    val ids = (0 until 100).map { "m" + it.toString().padStart(9, '0') }
    body.set("""{"group":[]}""")
    assertEquals(ids.toSet(), reader().findByMemberIds(ids).keys)
    assertEquals(100, requests.single().query.split('&').size)
    requests.clear()
    for (invalid in
      listOf(
        emptyList(),
        ids + "x000000000",
        listOf("member0001", "member0001"),
        listOf("MEMBER0001"),
        listOf("member0001&x=y"),
      )) {
      assertFailsWith<GroupServiceConfigurationException> { reader().findByMemberIds(invalid) }
    }
    assertTrue(requests.isEmpty())
  }

  @Test
  fun `응답 ID의 누락 추가 중복과 핵심 계약 오류를 실패로 처리하고 재시도하지 않는다`() {
    for (payload in
      listOf(
        "{\"members\":[]}",
        "{\"members\":[{\"memberId\":\"member0001\",\"memberships\":[]},{\"memberId\":\"member0003\",\"memberships\":[]}]}",
        "{\"members\":[{\"memberId\":\"member0001\",\"memberships\":[]},{\"memberId\":\"member0001\",\"memberships\":[]}]}",
        "{\"members\":[{\"memberId\":\"member0001\"}]}",
        "not-json",
      )) {
      requests.clear()
      body.set(payload)
      assertFailsWith<GroupServiceConfigurationException> {
        reader().findByMemberIds(listOf("member0001"))
      }
      assertEquals(1, requests.size)
    }
  }

  @Test
  fun `그룹 4xx와 리다이렉트는 내부 설정 오류이며 본문 키 주소를 예외에 담지 않는다`() {
    for (code in listOf(400, 401, 403, 404, 429, 301, 302, 307)) {
      requests.clear()
      status.set(code)
      body.set("upstream-private-body")
      val error =
        assertFailsWith<GroupServiceConfigurationException> {
          reader().findByMemberIds(listOf("member0001"))
        }
      assertEquals("그룹 호출 설정 또는 응답 계약 오류", error.message)
      assertNull(error.cause)
      assertEquals(1, requests.size)
      assertEquals("/api/groups/summary", requests.single().path)
    }
  }

  @Test
  fun `그룹 5xx는 이용 불가이며 임시 데이터로 대체하거나 재시도하지 않는다`() {
    for (code in listOf(500, 502, 503)) {
      requests.clear()
      status.set(code)
      body.set("upstream-private-body")
      val error =
        assertFailsWith<GroupServiceUnavailableException> {
          reader().findByMemberIds(listOf("tu3fgxd45k"))
        }
      assertEquals("그룹 서비스 이용 불가", error.message)
      assertNull(error.cause)
      assertEquals(1, requests.size)
    }
  }

  @Test
  fun `응답 전 시간 초과는 기존 전송 정책으로 한 번 재시도한 뒤 이용 불가이다`() {
    headerDelay.set(1200)
    assertFailsWith<GroupServiceUnavailableException> {
      reader(Duration.ofMillis(300)).findByMemberIds(listOf("member0001", "member0002"))
    }
    assertEquals(2, requests.size)
  }

  @Test
  fun `본문 수신 중 시간 초과는 이용 불가이며 이미 받은 응답을 재시도하지 않는다`() {
    bodyDelay.set(1200)
    assertFailsWith<GroupServiceUnavailableException> {
      reader(Duration.ofMillis(300)).findByMemberIds(listOf("member0001", "member0002"))
    }
    assertEquals(1, requests.size)
  }

  @Test
  fun `연결 실패는 빈 응답이나 계약 오류가 아닌 이용 불가이다`() {
    val port = ServerSocket(0).use { it.localPort }
    assertFailsWith<GroupServiceUnavailableException> {
      reader(url = "http://127.0.0.1:$port").findByMemberIds(listOf("member0001"))
    }
    assertTrue(requests.isEmpty())
  }

  @Test
  fun `성공 상태라도 JSON이 아닌 응답이나 계약 외 상태를 거부한다`() {
    for (type in listOf("text/html", "text/plain", "invalid")) {
      contentType.set(type)
      assertFailsWith<GroupServiceConfigurationException> {
        reader().findByMemberIds(listOf("member0001", "member0002"))
      }
    }
    contentType.set("application/json")
    status.set(201)
    assertFailsWith<GroupServiceConfigurationException> {
      reader().findByMemberIds(listOf("member0001", "member0002"))
    }
  }

  @Test
  fun `같은 포트에서 HTTP와 fixture를 교체해도 소속 의미와 반환 모델은 같다`() {
    val resource = ClassPathResource("fixtures/local/group-memberships.json")
    val fixtureText = resource.getContentAsString(Charsets.UTF_8)
    val ids = listOf("q1w2e3r4t5", "j0n8k2m4p6", "k2m9x0p1q3", "dj8fy4bewk", "tu3fgxd45k")
    body.set(GroupMembershipSamples.summary(fixtureText, ids))
    val fixture: GroupMembershipReader = FixtureGroupMembershipReader(resource, decoder)
    val http: GroupMembershipReader = reader()
    val result = http.findByMemberIds(ids)
    assertEquals(fixture.findByMemberIds(ids), result)
    assertEquals(listOf(1, 0, 0, 1, 1), ids.map { result.getValue(it).size })
    assertEquals(1, requests.size)
  }

  private fun reader(
    timeout: Duration = Duration.ofSeconds(3),
    url: String = "http://127.0.0.1:${server.address.port}",
  ): GroupMembershipReader {
    val configuration = GroupClientConfiguration()
    val properties = GroupClientProperties(url, readTimeout = timeout)
    val client =
      configuration.groupRestClient(
        RestClient.builder(),
        properties,
        InternalServiceProperties(TestProperties.internalKey),
        configuration.groupRetryInterceptor(
          properties,
          configuration.retryJitter(),
          configuration.retrySleeper(),
        ),
      )
    return HttpGroupMembershipReader(client, decoder)
  }

  private data class Request(
    val method: String,
    val path: String,
    val query: String,
    val keys: List<String>,
    val bodySize: Int,
  )

  @Test
  fun `상세는 단일 경로와 여섯 필드 계약을 해석하고 목록 한명도 summary를 사용한다`() {
    body.set(
      """{"memberId":"member0001","groupId":"group00001","groupName":"합성 그룹","registeredDate":"2026-08-16 00:30","pricing":"Premium","permission":"Editor"}"""
    )
    val result = kotlin.test.assertNotNull(reader().findByMemberId("member0001"))
    assertEquals(java.time.Instant.parse("2026-08-15T15:30:00Z"), result.joinedAt)
    assertEquals("Editor", result.permission)
    assertEquals("/api/group/member0001", requests.single().path)
    requests.clear()
    body.set("""{"group":[]}""")
    assertTrue(reader().findByMemberIds(listOf("member0001")).getValue("member0001").isEmpty())
    assertEquals("/api/groups/summary", requests.single().path)
  }

  @Test
  fun `상세의 전용 미소속 404만 빈 목록으로 바꾸며 다른 상태와 계약 위반은 숨기지 않는다`() {
    status.set(404)
    contentType.set("application/problem+json")
    val membershipType = "urn:problem-type:group-membership-not-found"
    body.set("""{"type":"$membershipType","status":404,"code":"RESOURCE_NOT_FOUND"}""")
    assertNull(reader().findByMemberId("member0001"))
    for (invalid in
      listOf(
        // 경로 404(type 기본값)와 code·status·type 중 하나라도 다른 응답은 미소속으로 보지 않는다.
        """{"type":"about:blank","status":404,"code":"RESOURCE_NOT_FOUND"}""",
        """{"status":404,"code":"RESOURCE_NOT_FOUND"}""",
        """{"type":"$membershipType","status":404,"code":"GROUP_MEMBERSHIP_NOT_FOUND"}""",
        """{"type":"$membershipType","status":4294967700,"code":"RESOURCE_NOT_FOUND"}""",
        """{"type":"$membershipType","status":"404","code":"RESOURCE_NOT_FOUND"}""",
      )) {
      requests.clear()
      body.set(invalid)
      assertFailsWith<GroupServiceConfigurationException> { reader().findByMemberId("member0001") }
      assertEquals(1, requests.size)
    }
    status.set(200)
    contentType.set("application/json")
    val valid =
      """{"memberId":"member0001","groupId":"group00001","groupName":"합성","registeredDate":"2026-08-15 13:00","pricing":"Premium","permission":"Editor"}"""
    for (invalid in
      listOf(
        valid.replace("member0001", "member0002"),
        valid.replace("2026-08-15 13:00", "2026-02-30 13:00"),
        valid.replace("2026-08-15 13:00", "2026-08-15T04:00:00Z"),
        valid.replace("2026-08-15 13:00", "2026-08-15 13:00:00"),
        "[$valid]",
        valid.replace("\"permission\":\"Editor\"", "\"permission\":null"),
      )) {
      requests.clear()
      body.set(invalid)
      assertFailsWith<GroupServiceConfigurationException> { reader().findByMemberId("member0001") }
      assertEquals(1, requests.size)
    }
    status.set(500)
    body.set(
      """{"type":"urn:problem-type:group-membership-ambiguous","status":500,"code":"INTERNAL_SERVER_ERROR"}"""
    )
    assertFailsWith<GroupServiceUnavailableException> { reader().findByMemberId("member0001") }
  }
}
