package task.member.application.model

/** 입력 경계에서 보정된 페이지 조건. HTTP·Spring Data와 독립적으로 offset을 제한한다(POL-API-003). */
data class MemberPageRequest(val number: Int = 0, val size: Int = DEFAULT_SIZE) {
  init {
    require(number >= 0) { "페이지 번호는 0 이상이어야 합니다." }
    require(size in 1..MAX_SIZE) { "적용된 페이지 크기는 1부터 100까지여야 합니다." }
    require(number.toLong() * size <= MAX_OFFSET) { "조회 offset이 허용 범위를 초과했습니다." }
  }

  val offset: Long
    get() = number.toLong() * size

  companion object {
    const val DEFAULT_SIZE = 20
    const val MAX_SIZE = 100
    const val MAX_OFFSET = 2147483647L
  }
}
