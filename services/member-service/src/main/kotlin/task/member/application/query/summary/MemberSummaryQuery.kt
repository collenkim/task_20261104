package task.member.application.query.summary

import task.member.application.model.MemberNameSort
import task.member.application.model.MemberPageRequest

/** HTTP 바인딩과 독립적인 목록 조회 조건(POL-CQRS-004). */
data class MemberSummaryQuery(
  val page: MemberPageRequest = MemberPageRequest(),
  val sort: MemberNameSort = MemberNameSort.ASCENDING,
)
