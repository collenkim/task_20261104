package task.group.application.query.summary

import task.group.application.exception.InvalidGroupQueryException

/** 1~100개 원시 ID를 보정 없이 검증한다(POL-GROUP-002). */
data class GroupSummaryQuery(val memberIds: List<String>) {
  init {
    if (
      memberIds.size !in 1..100 ||
        memberIds.distinct().size != memberIds.size ||
        memberIds.any { !it.matches(Regex("[a-z0-9]{10}")) }
    )
      throw InvalidGroupQueryException()
  }
}
