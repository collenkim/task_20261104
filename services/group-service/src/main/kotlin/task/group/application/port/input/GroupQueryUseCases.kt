package task.group.application.port.input

import task.group.application.model.GroupMembershipRecord
import task.group.application.model.GroupSummaryView
import task.group.application.query.detail.MemberGroupDetailQuery
import task.group.application.query.summary.GroupSummaryQuery

/** HTTP 이외의 진입점도 재사용할 수 있는 조회 계약(POL-CQRS-004). */
fun interface GetGroupSummariesUseCase {
  fun execute(query: GroupSummaryQuery): List<GroupSummaryView>
}

/** 상세는 단일 소속을 반환하며 미소속·다건을 구분한다(POL-GROUP-001). */
fun interface GetMemberGroupDetailUseCase {
  fun execute(query: MemberGroupDetailQuery): GroupMembershipRecord
}
