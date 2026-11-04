package task.group

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import task.group.infrastructure.client.MemberClientConfiguration
import task.group.infrastructure.client.MemberClientProperties

/** 멤버 서비스 호출 설정: 토큰 자동 첨부, 연결 실패·시간 초과만 1회 재시도, 실패를 숨기지 않음, 기동 시 설정 검증. */
class MemberClientTests {
  private val hits = AtomicInteger()
  private val authorization = AtomicReference<String?>()
  private var server: HttpServer? = null
  // 요청마다 다른 스레드: 지연 중인 첫 요청이 재시도 요청 처리를 막지 않게 한다.
  private val executor: ExecutorService = Executors.newCachedThreadPool()

  @AfterEach
  fun stop() {
    server?.stop(0)
    executor.shutdownNow()
  }

  private fun start(status: Int, delay: Duration = Duration.ZERO): String {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/") { exchange ->
      hits.incrementAndGet()
      authorization.set(exchange.requestHeaders.getFirst("Authorization"))
      Thread.sleep(delay.toMillis())
      val body = "{}".toByteArray()
      exchange.sendResponseHeaders(status, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    s.executor = executor
    s.start()
    server = s
    return "http://127.0.0.1:${s.address.port}"
  }

  private fun client(baseUrl: String, readTimeout: Duration = Duration.ofSeconds(3)): RestClient =
    MemberClientConfiguration()
      .memberRestClient(
        RestClient.builder(),
        MemberClientProperties(
          baseUrl = baseUrl,
          token = "test-member-client-token",
          readTimeout = readTimeout,
        ),
      )

  @Test
  fun `서비스 간 토큰을 자동으로 붙인다`() {
    client(start(200)).get().uri("/members").retrieve().body<String>()
    assertThat(authorization.get()).isEqualTo("Bearer test-member-client-token")
    assertThat(hits.get()).isEqualTo(1)
  }

  @Test
  fun `응답 시간 초과는 1회 재시도하고 실패를 그대로 알린다`() {
    val url = start(200, delay = Duration.ofMillis(800))
    assertThatThrownBy {
        client(url, readTimeout = Duration.ofMillis(200))
          .get()
          .uri("/members")
          .retrieve()
          .body<String>()
      }
      .isInstanceOf(ResourceAccessException::class.java)
    assertThat(hits.get()).isEqualTo(2)
  }

  @Test
  fun `응답을 받은 오류는 재시도하지 않는다`() {
    val url = start(503)
    assertThatThrownBy { client(url).get().uri("/members").retrieve().body<String>() }
      .isInstanceOf(HttpServerErrorException::class.java)
    assertThat(hits.get()).isEqualTo(1)
  }

  @Test
  fun `조회가 아닌 요청은 시간 초과여도 재시도하지 않는다`() {
    val url = start(200, delay = Duration.ofMillis(800))
    assertThatThrownBy {
        client(url, readTimeout = Duration.ofMillis(200))
          .post()
          .uri("/members")
          .retrieve()
          .body<String>()
      }
      .isInstanceOf(ResourceAccessException::class.java)
    assertThat(hits.get()).isEqualTo(1)
  }

  @Test
  fun `멤버 서비스에 연결할 수 없으면 빈 결과가 아니라 오류로 알린다`() {
    val closedPort = ServerSocket(0).use { it.localPort }
    assertThatThrownBy {
        client("http://127.0.0.1:$closedPort").get().uri("/members").retrieve().body<String>()
      }
      .isInstanceOf(ResourceAccessException::class.java)
  }

  @Test
  fun `주소나 토큰이 없으면 기동하지 않는다`() {
    val runner =
      ApplicationContextRunner()
        .withUserConfiguration(MemberClientConfiguration::class.java)
        .withBean(RestClient.Builder::class.java, { RestClient.builder() })
    runner.withPropertyValues("task.member-client.token=x").run {
      assertThat(it).hasFailed()
      assertThat(it.startupFailure).rootCause().hasMessageContaining("TASK_MEMBER_CLIENT_BASE_URL")
    }
    runner.withPropertyValues("task.member-client.base-url=http://127.0.0.1:9").run {
      assertThat(it).hasFailed()
      assertThat(it.startupFailure).rootCause().hasMessageContaining("generateLocalSecrets")
    }
    runner
      .withPropertyValues(
        "task.member-client.base-url=http://127.0.0.1:9",
        "task.member-client.token=x",
      )
      .run { assertThat(it).hasNotFailed().hasBean(MemberClientConfiguration.MEMBER_REST_CLIENT) }
  }
}
