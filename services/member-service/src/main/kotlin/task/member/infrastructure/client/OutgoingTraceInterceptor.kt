package task.member.infrastructure.client

import java.util.UUID
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

/** 호출과 전송 시도를 연결하고 원문 URL·헤더를 기록하지 않는다(POL-CODE-004). */
class OutgoingTraceInterceptor : ClientHttpRequestInterceptor {
  private val log = LoggerFactory.getLogger(javaClass)

  override fun intercept(
    request: HttpRequest,
    body: ByteArray,
    execution: ClientHttpRequestExecution,
  ): ClientHttpResponse {
    val old = MDC.getCopyOfContextMap()
    val callId = UUID.randomUUID().toString()
    val requestId = MDC.get("requestId") ?: UUID.randomUUID().toString()
    val start = System.nanoTime()
    MDC.put("requestId", requestId)
    MDC.put("callId", callId)
    request.headers.set("X-Request-Id", requestId)
    request.headers.set("X-Internal-Call-Id", callId)
    val operation =
      when {
        request.uri.path == "/api/groups/summary" -> "group.summary"
        request.uri.path.startsWith("/api/group/") -> "group.detail"
        request.uri.path == "/v3/api-docs" -> "group.docs"
        else -> "group.other"
      }
    log.info(
      "event=remote.started dependency=group-service operation={} callId={}",
      operation,
      callId,
    )
    try {
      return execution.execute(request, body).also {
        log.info(
          "event=remote.completed phase=response.headers operation={} callId={} status={} durationMs={}",
          operation,
          callId,
          it.statusCode.value(),
          (System.nanoTime() - start) / 1_000_000,
        )
      }
    } finally {
      MDC.clear()
      if (old != null) MDC.setContextMap(old)
    }
  }
}
