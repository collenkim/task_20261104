package task.member.presentation.query.request

import task.member.application.exception.InvalidMemberQueryException
import task.member.application.model.MemberNameSort
import task.member.application.model.MemberPageRequest
import task.member.application.query.summary.MemberSummaryQuery

/** 원시 입력의 반복·형식을 먼저 검증한다. 생략만 기본값으로 처리한다(POL-API-003). */
class MemberSummaryRequest
private constructor(
  val page: String?,
  val size: String?,
  val sort: String?,
) {
  fun toQuery(): MemberSummaryQuery {
    val number = integer(page, 0, 0)
    val effectiveSize =
      integer(size, MemberPageRequest.DEFAULT_SIZE, 1).coerceAtMost(MemberPageRequest.MAX_SIZE)
    if (number.toLong() * effectiveSize > MemberPageRequest.MAX_OFFSET) invalid()
    val direction =
      when (sort) {
        null,
        "name,asc" -> MemberNameSort.ASCENDING
        "name,desc" -> MemberNameSort.DESCENDING
        else -> invalid()
      }
    return MemberSummaryQuery(MemberPageRequest(number, effectiveSize), direction)
  }

  companion object {
    private val DIGITS = Regex("[0-9]+")

    /** 순서나 값의 동일 여부와 무관하게 각 파라미터를 한 번만 받는다. */
    fun from(parameters: Map<String, List<String>>): MemberSummaryRequest {
      for (name in listOf("page", "size", "sort")) {
        if (parameters.containsKey(name) && parameters.getValue(name).size != 1) invalid()
      }
      return MemberSummaryRequest(
        parameters["page"]?.single(),
        parameters["size"]?.single(),
        parameters["sort"]?.single(),
      )
    }

    private fun integer(raw: String?, default: Int, minimum: Int): Int {
      if (raw == null) return default
      if (!DIGITS.matches(raw)) invalid()
      val value = raw.toIntOrNull() ?: invalid()
      if (value < minimum) invalid()
      return value
    }

    private fun invalid(): Nothing = throw InvalidMemberQueryException()
  }
}
