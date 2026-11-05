package task.member.application.port.input

import task.member.application.model.PageResult
import task.member.application.query.summary.MemberSummaryQuery
import task.member.application.query.summary.MemberSummaryView

/** 호출 채널과 공급자 구현에 의존하지 않는 목록 입력 포트(POL-CQRS-004). */
fun interface GetMemberSummariesUseCase {
  fun execute(query: MemberSummaryQuery): PageResult<MemberSummaryView>
}
