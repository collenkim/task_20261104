package task.member.infrastructure.client

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
import task.member.support.TestProperties

/** 그룹 서비스 호출 설정: 키 자동 첨부, 연결 실패·시간 초과만 1회 재시도, 실패를 숨기지 않음, 기동 시 설정 검증. */
class GroupClientTests {
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
      authorization.set(exchange.requestHeaders.getFirst("X-Internal-Service-Key"))
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

  private fun client(baseUrl: String, readTimeout: Duration = Duration.ofSeconds(3)): RestClient {
    val configuration = GroupClientConfiguration()
    val properties = GroupClientProperties(baseUrl = baseUrl, readTimeout = readTimeout)
    return configuration.groupRestClient(
      RestClient.builder(),
      properties,
      InternalServiceProperties(TestProperties.internalKey),
      configuration.groupRetryInterceptor(
        properties,
        configuration.retryJitter(),
        configuration.retrySleeper(),
      ),
    )
  }

  @Test
  fun `사용자 지정 내부 헤더는 서버 설정 키로 대체한다`() {
    client(start(200))
      .get()
      .uri("/groups")
      .header(GroupClientConfiguration.INTERNAL_HEADER, "caller-supplied")
      .retrieve()
      .body<String>()
    assertThat(authorization.get()).isEqualTo(TestProperties.internalKey)
  }

  @Test
  fun `숫자 IP와 명시적 포트만 허용하고 잘못된 주소와 제한 시간은 기동 시 거부한다`() {
    val runner =
      ApplicationContextRunner()
        .withUserConfiguration(GroupClientConfiguration::class.java)
        .withBean(RestClient.Builder::class.java, { RestClient.builder() })
        .withPropertyValues("task.internal-service.key=${TestProperties.internalKey}")
    for (url in
      listOf(
        "http://localhost:8082",
        "http://127.0.0.1",
        "http://127.0.0.1:8082/path",
        "http://300.1.1.1:8082",
      )) {
      runner.withPropertyValues("task.group-client.base-url=$url").run {
        assertThat(it).hasFailed()
      }
    }
    for (url in listOf("http://127.0.0.1:8082", "http://[::1]:8082")) {
      runner.withPropertyValues("task.group-client.base-url=$url").run {
        assertThat(it).hasNotFailed()
      }
    }
    runner
      .withPropertyValues(
        "task.group-client.base-url=http://127.0.0.1:8082",
        "task.group-client.read-timeout=0s",
      )
      .run { assertThat(it).hasFailed() }
  }

  @Test
  fun `서비스 간 키을 자동으로 붙인다`() {
    client(start(200)).get().uri("/groups").retrieve().body<String>()
    assertThat(authorization.get()).isEqualTo(TestProperties.internalKey)
    assertThat(hits.get()).isEqualTo(1)
  }

  @Test
  fun `응답 시간 초과는 1회 재시도하고 실패를 그대로 알린다`() {
    val url = start(200, delay = Duration.ofMillis(800))
    assertThatThrownBy {
        client(url, readTimeout = Duration.ofMillis(200))
          .get()
          .uri("/groups")
          .retrieve()
          .body<String>()
      }
      .isInstanceOf(ResourceAccessException::class.java)
    assertThat(hits.get()).isEqualTo(2)
  }

  @Test
  fun `응답을 받은 오류는 재시도하지 않는다`() {
    val url = start(503)
    assertThatThrownBy { client(url).get().uri("/groups").retrieve().body<String>() }
      .isInstanceOf(HttpServerErrorException::class.java)
    assertThat(hits.get()).isEqualTo(1)
  }

  @Test
  fun `조회가 아닌 요청은 시간 초과여도 재시도하지 않는다`() {
    val url = start(200, delay = Duration.ofMillis(800))
    assertThatThrownBy {
        client(url, readTimeout = Duration.ofMillis(200))
          .post()
          .uri("/groups")
          .retrieve()
          .body<String>()
      }
      .isInstanceOf(ResourceAccessException::class.java)
    assertThat(hits.get()).isEqualTo(1)
  }

  @Test
  fun `그룹 서비스에 연결할 수 없으면 빈 결과가 아니라 오류로 알린다`() {
    val closedPort = ServerSocket(0).use { it.localPort }
    assertThatThrownBy {
        client("http://127.0.0.1:$closedPort").get().uri("/groups").retrieve().body<String>()
      }
      .isInstanceOf(ResourceAccessException::class.java)
  }

  @Test
  fun `주소나 키이 없으면 기동하지 않는다`() {
    val runner =
      ApplicationContextRunner()
        .withUserConfiguration(GroupClientConfiguration::class.java)
        .withBean(RestClient.Builder::class.java, { RestClient.builder() })
    runner.withPropertyValues("task.internal-service.key=${TestProperties.internalKey}").run {
      assertThat(it).hasFailed()
      assertThat(it.startupFailure).rootCause().hasMessageContaining("TASK_GROUP_CLIENT_BASE_URL")
    }
    runner.withPropertyValues("task.group-client.base-url=http://127.0.0.1:9").run {
      assertThat(it).hasFailed()
      assertThat(it.startupFailure).rootCause().hasMessageContaining("generateLocalSecrets")
    }
    runner
      .withPropertyValues(
        "task.group-client.base-url=http://127.0.0.1:9",
        "task.internal-service.key=${TestProperties.internalKey}",
      )
      .run { assertThat(it).hasNotFailed().hasBean(GroupClientConfiguration.GROUP_REST_CLIENT) }
  }
}
