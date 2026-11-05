package task.group.presentation.query.mapper

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import task.group.application.model.GroupMembershipRecord
import task.group.application.model.GroupSummaryView
import task.group.presentation.query.response.*

/** 응답 경계에서만 표시 형식을 적용한다(POL-API-005). */
object GroupResponseMapper {
  private val formatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))

  fun summaries(views: List<GroupSummaryView>) =
    GroupSummaryResponse(
      views.map { view ->
        GroupSummaryItem(
          view.groupId,
          view.groupName,
          view.totalMemberCount,
          view.members.map { GroupMemberSummary(it.memberId, it.permission.code) },
        )
      }
    )

  fun detail(view: GroupMembershipRecord) =
    MemberGroupDetailResponse(
      view.memberId,
      view.groupId,
      view.groupName,
      formatter.format(view.registeredAt),
      view.pricing.code,
      view.permission.code,
    )
}
