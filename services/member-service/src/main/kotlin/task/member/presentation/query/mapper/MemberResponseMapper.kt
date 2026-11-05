package task.member.presentation.query.mapper

import task.member.application.model.PageResult
import task.member.application.query.detail.MemberDetailView
import task.member.application.query.summary.MemberSummaryView
import task.member.presentation.query.response.GroupMembershipDetailResponse
import task.member.presentation.query.response.GroupMembershipSummaryResponse
import task.member.presentation.query.response.MemberDetailResponse
import task.member.presentation.query.response.MemberPageResponse
import task.member.presentation.query.response.MemberSummaryPageResponse
import task.member.presentation.query.response.MemberSummaryResponse

/** 내부 조회 값을 공개 계약에 투영하는 순수 변환. 공급자·저장소를 참조하지 않는다(POL-CQRS-004). */
object MemberResponseMapper {
  fun summaries(view: PageResult<MemberSummaryView>) =
    MemberSummaryPageResponse(
      view.items.map { member ->
        MemberSummaryResponse(
          member.id,
          member.name,
          member.groups.map {
            GroupMembershipSummaryResponse(it.id, it.name, it.permission, it.permissionName)
          },
        )
      },
      MemberPageResponse(view.size, view.number, view.totalElements, view.totalPages),
    )

  fun detail(view: MemberDetailView) =
    MemberDetailResponse(
      view.id,
      view.name,
      view.email,
      view.address,
      view.registeredDate,
      view.createdAt,
      view.createdBy,
      view.groups.map {
        GroupMembershipDetailResponse(
          it.id,
          it.name,
          it.permission,
          it.permissionName,
          it.joinedAt,
          it.pricing,
        )
      },
    )
}
