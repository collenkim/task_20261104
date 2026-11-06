package task.member.infrastructure.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import task.member.presentation.error.ErrorCode
import task.member.presentation.error.ProblemResponseWriter

/** 호출자별 요청 제한 조건(POL-SEC-007). 기본값은 정상 화면 사용을 막지 않는 수준이다. */
@ConfigurationProperties("task.rate-limit")
class RateLimitProperties(
  val enabled: Boolean = true,
  val capacity: Int = 100,
  val refillPerSecond: Int = 50,
  val maxClients: Int = 10_000,
) {
  init {
    require(capacity in 1..100_000) { "요청 제한 용량은 1~100000이어야 합니다." }
    require(refillPerSecond in 1..100_000) { "요청 제한 보충 속도는 1~100000이어야 합니다." }
    require(maxClients in 1..1_000_000) { "요청 제한 추적 호출자 수는 1~1000000이어야 합니다." }
  }
}

/**
 * 인증된 호출자(JWT sub)별 토큰 버킷. 서버 한 대의 메모리 안에서만 센다(분산 제한은 게이트웨이 책임).
 *
 * 추적 호출자가 상한을 넘으면 가득 찬(유휴) 버킷부터 지운다. 시간은 단조 시계(nanoTime)로 잰다.
 */
class TokenBucketRateLimiter(
  private val capacity: Int,
  private val refillPerSecond: Int,
  private val maxClients: Int,
  private val nanoTime: () -> Long = System::nanoTime,
) {
  private val buckets = ConcurrentHashMap<String, Bucket>()

  /** 허용하면 null, 거부하면 다시 시도할 수 있을 때까지의 초(1 이상)를 돌려준다. */
  fun tryAcquire(client: String): Long? {
    if (buckets.size >= maxClients && !buckets.containsKey(client)) evictIdle()
    return buckets.computeIfAbsent(client) { Bucket(capacity.toDouble(), nanoTime()) }.take()
  }

  private fun evictIdle() {
    val now = nanoTime()
    buckets.entries.removeIf { it.value.isFull(now) }
  }

  private inner class Bucket(private var tokens: Double, private var updatedAt: Long) {
    @Synchronized
    fun take(): Long? {
      refill(nanoTime())
      if (tokens >= 1.0) {
        tokens -= 1.0
        return null
      }
      return max(1L, ceil((1.0 - tokens) / refillPerSecond).toLong())
    }

    @Synchronized
    fun isFull(now: Long): Boolean {
      refill(now)
      return tokens >= capacity
    }

    private fun refill(now: Long) {
      val elapsed = max(0L, now - updatedAt)
      tokens = min(capacity.toDouble(), tokens + elapsed / 1_000_000_000.0 * refillPerSecond)
      updatedAt = now
    }
  }
}

/** 보안 필터가 인증한 뒤 업무·중계 경로에만 적용한다. 초과하면 429와 Retry-After를 돌려준다(POL-SEC-007). */
class RateLimitFilter(
  private val limiter: TokenBucketRateLimiter,
  private val writer: ProblemResponseWriter,
) : OncePerRequestFilter() {
  override fun shouldNotFilter(request: HttpServletRequest): Boolean {
    val path = request.requestURI.removePrefix(request.contextPath)
    return LIMITED_PREFIXES.none { path.startsWith(it) }
  }

  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    chain: FilterChain,
  ) {
    val authentication =
      SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
    if (authentication == null) {
      chain.doFilter(request, response)
      return
    }
    val retryAfter = limiter.tryAcquire(authentication.name)
    if (retryAfter == null) {
      chain.doFilter(request, response)
      return
    }
    response.setHeader(HttpHeaders.RETRY_AFTER, retryAfter.toString())
    writer.write(request, response, ErrorCode.TOO_MANY_REQUESTS)
  }

  companion object {
    val LIMITED_PREFIXES = listOf("/api/", "/internal-api/")
  }
}
