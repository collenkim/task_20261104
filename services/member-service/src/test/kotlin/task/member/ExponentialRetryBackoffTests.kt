package task.member

import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import task.member.infrastructure.client.ExponentialRetryBackoff

/** 실제 대기 없이 지수 증가·cap·큰 입력·정밀도 경계를 검사한다(POL-COMM-003). */
class ExponentialRetryBackoffTests {
  @Test
  fun `기본 지연은 첫 재시도부터 200 400 800 밀리초로 증가한다`() {
    val policy = ExponentialRetryBackoff(Duration.ofMillis(200), Duration.ofSeconds(2))
    assertEquals(listOf(200L, 400L, 800L), (1..3).map(policy::upperBoundMillis))
    assertEquals(2000L, policy.upperBoundMillis(Int.MAX_VALUE))
  }

  @Test
  fun `홀수 cap과 최대 설정에서도 상한을 넘거나 overflow하지 않는다`() {
    val capped = ExponentialRetryBackoff(Duration.ofMillis(200), Duration.ofMillis(350))
    assertEquals(listOf(200L, 350L, 350L), (1..3).map(capped::upperBoundMillis))
    assertEquals(
      5L,
      ExponentialRetryBackoff(Duration.ofMillis(3), Duration.ofMillis(5)).upperBoundMillis(2),
    )
    assertEquals(
      60000L,
      ExponentialRetryBackoff(Duration.ofMillis(1), Duration.ofSeconds(60))
        .upperBoundMillis(Int.MAX_VALUE),
    )
    assertEquals(
      60000L,
      ExponentialRetryBackoff(Duration.ofSeconds(60), Duration.ofSeconds(60)).upperBoundMillis(3),
    )
  }

  @Test
  fun `정수 밀리초 범위를 벗어나거나 기본 지연이 cap보다 크면 생성하지 않는다`() {
    val maximum = Duration.ofSeconds(60)
    for (invalid in
      listOf(
        Duration.ZERO,
        Duration.ofMillis(-1),
        Duration.ofNanos(1),
        Duration.ofNanos(1_500_000),
        Duration.ofMillis(60001),
        Duration.ofSeconds(Long.MAX_VALUE),
      )) {
      assertFailsWith<IllegalArgumentException> { ExponentialRetryBackoff(invalid, maximum) }
      assertFailsWith<IllegalArgumentException> {
        ExponentialRetryBackoff(Duration.ofMillis(1), invalid)
      }
    }
    assertFailsWith<IllegalArgumentException> {
      ExponentialRetryBackoff(Duration.ofMillis(2), Duration.ofMillis(1))
    }
  }

  @Test
  fun `재시도 번호는 양수여야 하고 최소 지연 설정도 유지한다`() {
    val policy = ExponentialRetryBackoff(Duration.ofMillis(1), Duration.ofMillis(1))
    assertEquals(1L, policy.upperBoundMillis(1))
    assertFailsWith<IllegalArgumentException> { policy.upperBoundMillis(0) }
    assertFailsWith<IllegalArgumentException> { policy.upperBoundMillis(-1) }
  }
}
