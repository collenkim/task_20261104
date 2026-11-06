package task.member.infrastructure.web

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

/** 호출자별 토큰 버킷의 허용·거부·보충·추적 상한을 단조 시계를 바꿔 가며 확인한다(POL-SEC-007). */
class TokenBucketRateLimiterTests {
  private var now = 0L
  private val second = 1_000_000_000L

  private fun limiter(capacity: Int = 2, refill: Int = 1, maxClients: Int = 10) =
    TokenBucketRateLimiter(capacity, refill, maxClients) { now }

  @Test
  fun `용량까지 허용하고 넘으면 다시 시도할 초를 돌려준다`() {
    val limiter = limiter()
    assertNull(limiter.tryAcquire("a"))
    assertNull(limiter.tryAcquire("a"))
    assertEquals(1L, limiter.tryAcquire("a"))
  }

  @Test
  fun `호출자마다 버킷이 따로다`() {
    val limiter = limiter(capacity = 1)
    assertNull(limiter.tryAcquire("a"))
    assertEquals(1L, limiter.tryAcquire("a"))
    assertNull(limiter.tryAcquire("b"))
  }

  @Test
  fun `시간이 지나면 보충 속도만큼 다시 허용하고 용량을 넘겨 쌓지 않는다`() {
    val limiter = limiter(capacity = 2, refill = 1)
    repeat(2) { assertNull(limiter.tryAcquire("a")) }
    assertEquals(1L, limiter.tryAcquire("a"))
    now += second
    assertNull(limiter.tryAcquire("a"))
    assertEquals(1L, limiter.tryAcquire("a"))
    now += 60 * second
    repeat(2) { assertNull(limiter.tryAcquire("a")) }
    assertEquals(1L, limiter.tryAcquire("a"))
  }

  @Test
  fun `보충이 느리면 Retry-After 초를 올림해 돌려준다`() {
    val limiter = TokenBucketRateLimiter(1, 1, 10) { now }
    assertNull(limiter.tryAcquire("a"))
    now += second / 4
    assertEquals(1L, limiter.tryAcquire("a"))
  }

  @Test
  fun `추적 상한에 닿으면 가득 찬 유휴 버킷을 지우고 새 호출자를 받는다`() {
    val limiter = limiter(capacity = 1, refill = 1, maxClients = 1)
    assertNull(limiter.tryAcquire("a"))
    now += 2 * second
    assertNull(limiter.tryAcquire("b"))
    assertEquals(1L, limiter.tryAcquire("b"))
  }

  @Test
  fun `설정 범위를 벗어나면 기동하지 않는다`() {
    assertFailsWith<IllegalArgumentException> { RateLimitProperties(capacity = 0) }
    assertFailsWith<IllegalArgumentException> { RateLimitProperties(refillPerSecond = 0) }
    assertFailsWith<IllegalArgumentException> { RateLimitProperties(maxClients = 0) }
  }
}
