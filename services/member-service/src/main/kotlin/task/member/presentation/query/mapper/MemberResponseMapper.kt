package task.member.presentation.query.mapper

import task.member.application.model.PageResult
import task.member.application.query.detail.MemberDetailView
import task.member.application.query.summary.MemberSummaryView
import task.member.presentation.query.response.GroupMembershipDetailResponse
import task.member.presentation.query.response.MemberDetailResponse
import task.member.presentation.query.response.MemberPageResponse
import task.member.presentation.query.response.MemberSummaryPageResponse
import task.member.presentation.query.response.MemberSummaryResponse

/** 내부 조회 값을 공개 계약에 투영하는 순수 변환. 공급자·저장소를 참조하지 않는다(POL-CQRS-004). */
object MemberResponseMapper {
  fun summaries(view: PageResult<MemberSummaryView>) =
    MemberSummaryPageResponse(
      view.items.map { member ->
        check(member.groups.size <= 1) { "멤버의 단일 소속 계약 위반" }
        val group = member.groups.singleOrNull()
        MemberSummaryResponse(
          member.id,
          member.name,
          group?.name,
          group?.permission,
        )
      },
      MemberPageResponse(view.size, view.number, view.totalElements, view.totalPages),
    )

  fun detail(view: MemberDetailView): MemberDetailResponse {
    check(view.groups.size <= 1) { "멤버의 단일 소속 계약 위반" }
    return MemberDetailResponse(
      view.id,
      view.name,
      view.email,
      view.address,
      ApiTime.format(view.registeredDate),
      view.groups.singleOrNull()?.let {
        GroupMembershipDetailResponse(
          it.id,
          it.name,
          it.permission,
          it.joinedAt?.let(ApiTime::format),
          it.pricing,
        )
      },
    )
  }
}
