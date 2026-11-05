package task.member.infrastructure.client

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

/**
 * 연결 실패·시간 초과일 때만 조회 요청(GET·HEAD)을 지수 백오프·Full Jitter로 다시 보낸다(POL-COMM-003).
 *
 * 응답을 받은 경우(4xx·5xx 포함)는 재시도하지 않는다. 마지막 실패는 그대로 던져 호출 쪽이 장애를 알 수 있게 한다. 이 인터셉터는 RestClient의 마지막
 * 인터셉터로 둔다(재실행 시 뒤 인터셉터를 다시 거치지 않으므로).
 */
class RetryOnTransientFailureInterceptor(
  private val maxRetries: Int,
  private val backoff: ExponentialRetryBackoff,
  private val jitter: RetryJitter,
  private val sleeper: RetrySleeper,
) : ClientHttpRequestInterceptor {
  private val log = LoggerFactory.getLogger(javaClass)

  init {
    require(maxRetries in 0..3) { "추가 재시도 횟수는 0부터 3까지여야 합니다." }
  }

  override fun intercept(
    request: HttpRequest,
    body: ByteArray,
    execution: ClientHttpRequestExecution,
  ): ClientHttpResponse {
    var retries = 0
    while (true) {
      stopIfInterrupted()
      try {
        return execution.execute(request, body)
      } catch (e: IOException) {
        stopIfInterrupted()
        if (retries >= maxRetries || !isRetryable(request, e)) throw e
        retries++
        val upper = backoff.upperBoundMillis(retries)
        val delay = jitter.sample(upper)
        check(delay in 0..upper) { "재시도 난수 결과가 허용 범위를 벗어났습니다." }
        try {
          sleeper.sleep(delay)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          throw interrupted()
        }
        stopIfInterrupted()
        log.warn(
          "그룹 서비스 호출 재시도 {}/{}: {} {} ({})",
          retries,
          maxRetries,
          request.method,
          request.uri.path,
          e.javaClass.simpleName,
        )
      }
    }
  }

  private fun stopIfInterrupted() {
    if (Thread.currentThread().isInterrupted) throw interrupted()
  }

  private fun interrupted() = InterruptedIOException("그룹 호출이 중단되었습니다.")

  private fun isRetryable(request: HttpRequest, e: IOException): Boolean =
    request.method in RETRYABLE_METHODS &&
      generateSequence<Throwable>(e) { it.cause }
        .any {
          it is ConnectException || it is HttpTimeoutException || it is SocketTimeoutException
        }

  private companion object {
    val RETRYABLE_METHODS = setOf(HttpMethod.GET, HttpMethod.HEAD)
  }
}
