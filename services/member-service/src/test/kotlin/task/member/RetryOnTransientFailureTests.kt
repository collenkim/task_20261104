package task.member

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import task.member.infrastructure.client.ExponentialRetryBackoff
import task.member.infrastructure.client.RetryJitter
import task.member.infrastructure.client.RetryOnTransientFailureInterceptor
import task.member.infrastructure.client.RetrySleeper

/** 전송 횟수와 대기 순서를 관찰한다. 가짜 대기로 시간 경과에 의존하지 않는다(POL-TEST-006). */
class RetryOnTransientFailureTests {
  @Test
  fun `최초 성공은 난수 추출과 대기가 없다`() {
    val events = mutableListOf<String>()
    val retry = interceptor(events)
    val response = MockClientHttpResponse(byteArrayOf(), HttpStatus.OK)
    assertSame(
      response,
      retry.intercept(
        request(),
        byteArrayOf(),
        ClientHttpRequestExecution { _, _ ->
          events += "전송"
          response
        },
      ),
    )
    assertEquals(listOf("전송"), events)
  }

  @Test
  fun `재시도 0이면 첫 실패를 보존하고 대기하지 않는다`() {
    val events = mutableListOf<String>()
    val failure = ConnectException()
    val result =
      assertFailsWith<ConnectException> {
        interceptor(events, retries = 0)
          .intercept(
            request(),
            byteArrayOf(),
            ClientHttpRequestExecution { _, _ ->
              events += "전송"
              throw failure
            },
          )
      }
    assertSame(failure, result)
    assertEquals(listOf("전송"), events)
  }

  @Test
  fun `추가 재시도 세 번은 지수 상한 안에서 매번 추출하고 마지막 실패 뒤에는 기다리지 않는다`() {
    val events = mutableListOf<String>()
    val failure = SocketTimeoutException()
    val retry = interceptor(events, retries = 3)
    assertSame(
      failure,
      assertFailsWith<SocketTimeoutException> {
        retry.intercept(
          request(),
          byteArrayOf(),
          ClientHttpRequestExecution { _, _ ->
            events += "전송"
            throw failure
          },
        )
      },
    )
    assertEquals(
      listOf("전송", "상한200", "대기100", "전송", "상한400", "대기200", "전송", "상한800", "대기400", "전송"),
      events,
    )
  }

  @Test
  fun `지터의 0 중간 상한 값과 cap 적용을 실제 대기에 전달한다`() {
    for ((sample, expected) in
      listOf<Pair<(Long) -> Long, List<Long>>>(
        ({ _: Long -> 0L }) to listOf(0L, 0L, 0L),
        ({ upper: Long -> upper / 2 }) to listOf(100L, 175L, 175L),
        ({ upper: Long -> upper }) to listOf(200L, 350L, 350L),
      )) {
      val waits = mutableListOf<Long>()
      val retry =
        RetryOnTransientFailureInterceptor(
          3,
          ExponentialRetryBackoff(Duration.ofMillis(200), Duration.ofMillis(350)),
          RetryJitter(sample),
          RetrySleeper { waits += it },
        )
      assertFailsWith<HttpTimeoutException> {
        retry.intercept(
          request(),
          byteArrayOf(),
          ClientHttpRequestExecution { _, _ -> throw HttpTimeoutException("시간 초과") },
        )
      }
      assertEquals(expected, waits)
    }
  }

  @Test
  fun `GET과 HEAD의 중첩된 연결 실패만 재시도하고 성공 뒤에는 즉시 반환한다`() {
    for (method in listOf(HttpMethod.GET, HttpMethod.HEAD)) {
      val events = mutableListOf<String>()
      var calls = 0
      val response = MockClientHttpResponse(byteArrayOf(), HttpStatus.OK)
      assertSame(
        response,
        interceptor(events, retries = 3)
          .intercept(
            request(method),
            byteArrayOf(),
            ClientHttpRequestExecution { _, _ ->
              events += "전송"
              if (++calls == 1) throw IOException(ConnectException())
              response
            },
          ),
      )
      assertEquals(listOf("전송", "상한200", "대기100", "전송"), events)
    }
  }

  @Test
  fun `변경 요청과 일반 IO 및 계약 오류는 재시도하지 않는다`() {
    for (method in
      listOf(
        HttpMethod.POST,
        HttpMethod.PUT,
        HttpMethod.PATCH,
        HttpMethod.DELETE,
        HttpMethod.OPTIONS,
      )) {
      val events = mutableListOf<String>()
      assertFailsWith<ConnectException> {
        interceptor(events)
          .intercept(
            request(method),
            byteArrayOf(),
            ClientHttpRequestExecution { _, _ ->
              events += "전송"
              throw ConnectException()
            },
          )
      }
      assertEquals(listOf("전송"), events)
    }
    for (failure in listOf(IOException("일반 읽기 오류"), IllegalArgumentException("계약 오류"))) {
      val events = mutableListOf<String>()
      val result =
        assertFailsWith<Exception> {
          interceptor(events)
            .intercept(
              request(),
              byteArrayOf(),
              ClientHttpRequestExecution { _, _ ->
                events += "전송"
                throw failure
              },
            )
        }
      assertSame(failure, result)
      assertEquals(listOf("전송"), events)
    }
  }

  @Test
  fun `응답 상태와 본문 수신 중 실패는 재시도 경계 밖이다`() {
    for (status in
      listOf(
        HttpStatus.FOUND,
        HttpStatus.FORBIDDEN,
        HttpStatus.TOO_MANY_REQUESTS,
        HttpStatus.SERVICE_UNAVAILABLE,
      )) {
      val events = mutableListOf<String>()
      val response = MockClientHttpResponse(byteArrayOf(), status)
      assertSame(
        response,
        interceptor(events)
          .intercept(
            request(),
            byteArrayOf(),
            ClientHttpRequestExecution { _, _ ->
              events += "전송"
              response
            },
          ),
      )
      assertEquals(listOf("전송"), events)
    }
    val events = mutableListOf<String>()
    val body =
      object : java.io.InputStream() {
        override fun read(): Int = throw SocketTimeoutException()
      }
    val response = MockClientHttpResponse(body, HttpStatus.OK)
    val result =
      interceptor(events)
        .intercept(
          request(),
          byteArrayOf(),
          ClientHttpRequestExecution { _, _ ->
            events += "전송"
            response
          },
        )
    assertFailsWith<SocketTimeoutException> { result.body.read() }
    assertEquals(listOf("전송"), events)
  }

  @Test
  fun `대기 중 인터럽트는 플래그를 복구하고 추가 전송 없이 중단한다`() {
    var calls = 0
    val retry =
      RetryOnTransientFailureInterceptor(
        3,
        policy(),
        RetryJitter { it },
        RetrySleeper { throw InterruptedException() },
      )
    assertFalse(Thread.currentThread().isInterrupted)
    try {
      assertFailsWith<InterruptedIOException> {
        retry.intercept(
          request(),
          byteArrayOf(),
          ClientHttpRequestExecution { _, _ ->
            calls++
            throw ConnectException()
          },
        )
      }
      assertEquals(1, calls)
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun `이미 중단된 호출은 전송이나 대기를 시작하지 않는다`() {
    val events = mutableListOf<String>()
    try {
      Thread.currentThread().interrupt()
      assertFailsWith<InterruptedIOException> {
        interceptor(events)
          .intercept(
            request(),
            byteArrayOf(),
            ClientHttpRequestExecution { _, _ ->
              events += "전송"
              throw ConnectException()
            },
          )
      }
      assertTrue(events.isEmpty())
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun `같은 인터셉터를 재사용해도 새 요청의 재시도 번호는 처음부터 시작한다`() {
    val events = mutableListOf<String>()
    val retry = interceptor(events)
    repeat(2) {
      assertFailsWith<ConnectException> {
        retry.intercept(
          request(),
          byteArrayOf(),
          ClientHttpRequestExecution { _, _ ->
            events += "전송"
            throw ConnectException()
          },
        )
      }
    }
    assertEquals(List(2) { listOf("전송", "상한200", "대기100", "전송") }.flatten(), events)
  }

  @Test
  fun `허용되지 않은 재시도 횟수나 난수 결과는 조용히 보정하지 않는다`() {
    for (count in listOf(-1, 4, Int.MAX_VALUE)) {
      assertFailsWith<IllegalArgumentException> { interceptor(mutableListOf(), count) }
    }
    for (sample in listOf(-1L, 201L)) {
      val retry =
        RetryOnTransientFailureInterceptor(
          1,
          policy(),
          RetryJitter { sample },
          RetrySleeper { error("대기하면 안 됩니다") },
        )
      assertFailsWith<IllegalStateException> {
        retry.intercept(
          request(),
          byteArrayOf(),
          ClientHttpRequestExecution { _, _ -> throw ConnectException() },
        )
      }
    }
  }

  private fun policy() = ExponentialRetryBackoff(Duration.ofMillis(200), Duration.ofSeconds(2))

  @Test
  fun `전송이나 대기에서 중단 플래그가 설정되면 예외가 없어도 추가 요청을 보내지 않는다`() {
    for (duringTransport in listOf(true, false)) {
      var calls = 0
      val retry =
        RetryOnTransientFailureInterceptor(
          3,
          policy(),
          RetryJitter { 0 },
          RetrySleeper { Thread.currentThread().interrupt() },
        )
      try {
        assertFailsWith<InterruptedIOException> {
          retry.intercept(
            request(),
            byteArrayOf(),
            ClientHttpRequestExecution { _, _ ->
              calls++
              if (duringTransport) Thread.currentThread().interrupt()
              throw ConnectException()
            },
          )
        }
        assertEquals(1, calls)
        assertTrue(Thread.currentThread().isInterrupted)
      } finally {
        Thread.interrupted()
      }
    }
  }

  @Test
  fun `동시 요청은 같은 인터셉터에서 재시도 횟수와 지연 상한을 공유하지 않는다`() {
    val calls = ConcurrentHashMap<String, AtomicInteger>()
    val upperBounds = ConcurrentLinkedQueue<Long>()
    val waits = ConcurrentLinkedQueue<Long>()
    val firstAttempts = CountDownLatch(2)
    val retry =
      RetryOnTransientFailureInterceptor(
        1,
        policy(),
        RetryJitter {
          upperBounds += it
          it / 2
        },
        RetrySleeper { waits += it },
      )
    val execution = ClientHttpRequestExecution { request, _ ->
      if (calls.computeIfAbsent(request.uri.path) { AtomicInteger() }.incrementAndGet() == 1) {
        firstAttempts.countDown()
        check(firstAttempts.await(5, TimeUnit.SECONDS))
        throw ConnectException()
      }
      MockClientHttpResponse(byteArrayOf(), HttpStatus.OK)
    }
    val executor = Executors.newFixedThreadPool(2)
    try {
      val results =
        (1..2).map { index ->
          executor.submit(
            Callable {
              retry
                .intercept(
                  MockClientHttpRequest(HttpMethod.GET, URI("http://127.0.0.1:9/internal/$index")),
                  byteArrayOf(),
                  execution,
                )
                .use { it.statusCode.value() }
            }
          )
        }
      assertEquals(listOf(200, 200), results.map { it.get(10, TimeUnit.SECONDS) })
      assertEquals(listOf(2, 2), calls.values.map { it.get() })
      assertEquals(listOf(200L, 200L), upperBounds.toList())
      assertEquals(listOf(100L, 100L), waits.toList())
    } finally {
      executor.shutdownNow()
    }
  }

  private fun request(method: HttpMethod = HttpMethod.GET) =
    MockClientHttpRequest(method, URI("http://127.0.0.1:9/internal/group-memberships"))

  private fun interceptor(events: MutableList<String>, retries: Int = 1) =
    RetryOnTransientFailureInterceptor(
      retries,
      policy(),
      RetryJitter {
        events += "상한$it"
        it / 2
      },
      RetrySleeper { events += "대기$it" },
    )
}
