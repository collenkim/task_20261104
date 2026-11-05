package task.group.infrastructure.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping

/** 요청 단위 MDC를 관리하며 경로의 식별자를 기록하지 않는다(POL-CODE-004). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestTraceFilter : OncePerRequestFilter() {
  private val log = LoggerFactory.getLogger(javaClass)

  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    chain: FilterChain,
  ) {
    val old = MDC.getCopyOfContextMap()
    val ids = request.getHeaders("X-Request-Id").toList()
    val id =
      ids.singleOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,64}")) }
        ?: UUID.randomUUID().toString()
    val callId =
      request.getHeader("X-Internal-Call-Id")?.takeIf { it.matches(Regex("[A-Za-z0-9-]{1,64}")) }
    val attempt = request.getHeader("X-Internal-Attempt")?.takeIf { it.matches(Regex("[1-4]")) }
    if (callId != null) MDC.put("callId", callId)
    if (attempt != null) MDC.put("attempt", attempt)
    val start = System.nanoTime()
    MDC.put("requestId", id)
    MDC.put("service", "group-service")
    response.setHeader("X-Request-Id", id)
    log.info(
      "event=request.received requestId={} service=group-service method={} route=pending",
      id,
      request.method,
    )
    try {
      chain.doFilter(request, response)
    } finally {
      val route =
        request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()
          ?: "unmatched"
      log.info(
        "event=request.completed requestId={} service=group-service method={} route={} status={} code={} durationMs={}",
        id,
        request.method,
        route,
        response.status,
        request.getAttribute("errorCode") ?: "-",
        (System.nanoTime() - start) / 1_000_000,
      )
      MDC.clear()
      if (old != null) MDC.setContextMap(old)
    }
  }
}
