package task.member.infrastructure.client

import java.time.Duration

/** 상한 있는 지수 증가를 계산한다. 난수·실제 대기·HTTP와 독립적이다(POL-COMM-003). */
class ExponentialRetryBackoff(baseDelay: Duration, maxDelay: Duration) {
  private val baseMillis: Long
  private val maxMillis: Long

  init {
    require(validDelays(baseDelay, maxDelay)) { "재시도 지연 설정이 허용 범위를 벗어났습니다." }
    baseMillis = baseDelay.toMillis()
    maxMillis = maxDelay.toMillis()
  }

  /** 1부터 시작하는 재시도 번호. cap에 도달하면 반복을 끝내 큰 번호에도 overflow가 없다. */
  fun upperBoundMillis(retryNumber: Int): Long {
    require(retryNumber >= 1) { "재시도 번호는 1 이상이어야 합니다." }
    var bound = baseMillis
    var number = 1
    while (number < retryNumber && bound < maxMillis) {
      bound = if (bound > maxMillis / 2) maxMillis else bound * 2
      number++
    }
    return bound
  }

  companion object {
    private val MIN_DELAY = Duration.ofMillis(1)
    private val MAX_DELAY = Duration.ofSeconds(60)

    /** 정수 밀리초 1~60000, base <= cap. 큰 Duration을 밀리초로 바꾸기 전에 검증한다. */
    fun validDelays(baseDelay: Duration, maxDelay: Duration): Boolean =
      validDuration(baseDelay) && validDuration(maxDelay) && baseDelay <= maxDelay

    private fun validDuration(value: Duration): Boolean =
      value >= MIN_DELAY && value <= MAX_DELAY && value.nano % 1_000_000 == 0
  }
}

/** 매 재시도의 0~상한(포함) 밀리초를 균등 추출하는 경계(POL-COMM-003). */
fun interface RetryJitter {
  fun sample(upperBoundMillis: Long): Long
}

/** 실제 대기와 테스트 대역을 교체한다. 인터럽트를 호출자에게 전달한다(POL-COMM-003). */
fun interface RetrySleeper {
  @Throws(InterruptedException::class) fun sleep(millis: Long)
}
