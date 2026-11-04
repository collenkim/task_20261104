package task.member.application.model

/** 저장 기술에 의존하지 않는 조회 페이지. 건수는 멤버 수 기준이다(POL-CQRS-004, POL-API-003). */
data class PageResult<T>(
  val items: List<T>,
  val number: Int,
  val size: Int,
  val totalElements: Long,
) {
  init {
    require(number >= 0 && size > 0 && totalElements >= 0) { "페이지 메타데이터가 올바르지 않습니다." }
    require(items.size <= size) { "조회 결과가 페이지 크기를 초과했습니다." }
  }

  val totalPages: Long
    get() = totalElements / size + if (totalElements % size == 0L) 0 else 1
}
