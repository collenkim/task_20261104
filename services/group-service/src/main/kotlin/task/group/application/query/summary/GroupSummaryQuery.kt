package task.group.application.query.summary

import task.group.application.exception.InvalidGroupQueryException
import task.group.application.exception.UnprocessableGroupQueryException

/**
 * 원시 ID 목록을 보정 없이 검증한 뒤 중복을 제외한다(POL-GROUP-002).
 *
 * 1. 누락(0개)·빈 값·형식 위반(소문자 영숫자 10자리 아님, 콤마 결합 포함)은 400이다. 중복 제거로 잘못된 값을 숨기지 않는다.
 * 2. 원래 입력 수가 100개를 넘으면 422다(중복 포함 개수).
 * 3. 중복은 거부하지 않는다. 처음 나온 순서로 한 번만 조회하고 중복된 ID는 [duplicateIds]로 남겨 처리기가 로그로 기록한다.
 */
data class GroupSummaryQuery(val requestedIds: List<String>) {
  init {
    if (requestedIds.isEmpty() || requestedIds.any { !ID.matches(it) }) {
      throw InvalidGroupQueryException()
    }
    if (requestedIds.size > MAX_IDS) throw UnprocessableGroupQueryException()
  }

  /** 중복을 제외한 조회 대상(입력 순서 유지). */
  val memberIds: List<String> = requestedIds.distinct()

  /** 두 번 이상 전달된 ID(처음 나온 순서). */
  val duplicateIds: List<String> =
    requestedIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()

  companion object {
    const val MAX_IDS = 100
    private val ID = Regex("[a-z0-9]{10}")
  }
}
