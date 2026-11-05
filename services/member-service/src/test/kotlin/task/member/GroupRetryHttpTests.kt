package task.member

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import task.member.infrastructure.client.GroupClientConfiguration
import task.member.infrastructure.client.GroupClientProperties
import task.member.infrastructure.client.InternalServiceProperties
import task.member.infrastructure.client.RetryJitter
import task.member.infrastructure.client.RetrySleeper
import task.member.support.TestProperties

/** 실제 JDK HTTP 전송은 유지하고 지터·대기만 대역으로 관찰한다(POL-TEST-004·006). */
class GroupRetryHttpTests {
  private val hits = AtomicInteger()
  private val delayedRequests = AtomicInteger()
  private val status = AtomicInteger(200)
  private val requests = CopyOnWriteArrayList<Pair<String, List<String>>>()
  private val upperBounds = mutableListOf<Long>()
  private val waits = mutableListOf<Long>()
  private val executor = Executors.newCachedThreadPool()
  private lateinit var server: HttpServer

  @BeforeEach
  fun start() {
    server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
          exchange.use {
            val number = hits.incrementAndGet()
            requests +=
              it.requestMethod to
                it.requestHeaders[GroupClientConfiguration.INTERNAL_HEADER].orEmpty()
            try {
              if (number <= delayedRequests.get()) Thread.sleep(1800)
              it.responseHeaders.set("Content-Type", "application/json")
              it.responseHeaders.set("Location", "/unexpected")
              if (it.requestMethod == "HEAD") it.sendResponseHeaders(status.get(), -1)
              else {
                val bytes = "{}".toByteArray()
                it.sendResponseHeaders(status.get(), bytes.size.toLong())
                it.responseBody.use { output -> output.write(bytes) }
              }
            } catch (_: IOException) {
              // 시간 초과한 클라이언트가 연결을 먼저 종료한다.
            } catch (_: InterruptedException) {
              Thread.currentThread().interrupt()
            }
          }
        }
        this.executor = this@GroupRetryHttpTests.executor
        start()
      }
  }

  @AfterEach
  fun stop() {
    server.stop(0)
    executor.shutdownNow()
  }

  @Test
  fun `응답 전 시간 초과 뒤 재시도는 성공하고 매 전송에 내부 키 한 개를 보낸다`() {
    delayedRequests.set(1)
    assertEquals("{}", client(3).get().uri("/internal/group-memberships").retrieve().body<String>())
    assertEquals(2, hits.get())
    assertEquals(listOf(200L), upperBounds)
    assertEquals(listOf(100L), waits)
    assertEquals(List(2) { "GET" to listOf(TestProperties.internalKey) }, requests.toList())
  }

  @Test
  fun `응답 전 시간 초과가 계속되면 추가 세 번과 지수 지연 후 실패한다`() {
    delayedRequests.set(4)
    assertFailsWith<ResourceAccessException> {
      client(3).get().uri("/v3/api-docs").retrieve().body<String>()
    }
    assertEquals(4, hits.get())
    assertEquals(listOf(200L, 400L, 800L), upperBounds)
    assertEquals(listOf(100L, 200L, 400L), waits)
  }

  @Test
  fun `재시도를 끄면 실제 전송 한 번 뒤 실패하고 지터와 대기를 사용하지 않는다`() {
    delayedRequests.set(1)
    assertFailsWith<ResourceAccessException> {
      client(0).get().uri("/v3/api-docs").retrieve().body<String>()
    }
    assertEquals(1, hits.get())
    assertEquals(emptyList(), upperBounds)
    assertEquals(emptyList(), waits)
  }

  @Test
  fun `HEAD도 동일한 전송 재시도 정책을 사용한다`() {
    delayedRequests.set(1)
    assertEquals(
      200,
      client(1).head().uri("/v3/api-docs").retrieve().toBodilessEntity().statusCode.value(),
    )
    assertEquals(2, hits.get())
    assertEquals(listOf("HEAD", "HEAD"), requests.map { it.first })
    assertEquals(listOf(100L), waits)
  }

  @Test
  fun `오류 상태나 리다이렉트 응답을 받으면 지터 추출과 추가 전송을 하지 않는다`() {
    val client = client(3)
    for (code in listOf(302, 400, 429, 500, 503)) {
      hits.set(0)
      status.set(code)
      assertEquals(
        code,
        client.get().uri("/v3/api-docs").exchange { _, response -> response.statusCode.value() },
      )
      assertEquals(1, hits.get())
      assertEquals(emptyList(), upperBounds)
      assertEquals(emptyList(), waits)
    }
  }

  private fun client(retries: Int): RestClient {
    val configuration = GroupClientConfiguration()
    val properties =
      GroupClientProperties(
        "http://127.0.0.1:${server.address.port}",
        readTimeout = Duration.ofMillis(300),
        maxRetries = retries,
      )
    val retry =
      configuration.groupRetryInterceptor(
        properties,
        RetryJitter { upper ->
          upperBounds += upper
          upper / 2
        },
        RetrySleeper { waits += it },
      )
    return configuration.groupRestClient(
      RestClient.builder(),
      properties,
      InternalServiceProperties(TestProperties.internalKey),
      retry,
    )
  }
}
