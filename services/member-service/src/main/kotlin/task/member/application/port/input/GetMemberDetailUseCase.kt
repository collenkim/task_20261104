package task.member.application.port.input

import task.member.application.query.detail.MemberDetailQuery
import task.member.application.query.detail.MemberDetailView

/** 상세 조회의 재사용 가능한 입력 포트(POL-CQRS-004). */
fun interface GetMemberDetailUseCase {
  fun execute(query: MemberDetailQuery): MemberDetailView
}
