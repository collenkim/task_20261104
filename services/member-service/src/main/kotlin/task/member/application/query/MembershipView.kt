package task.member.application.query

import java.time.Instant
import task.member.application.model.GroupMembership

/** 전송 DTO와 독립적인 소속 조회 결과. 선택 정보의 부재를 보존한다(POL-CQRS-004). */
data class MembershipView(
  val id: String,
  val name: String,
  val permission: String,
  val permissionName: String,
  val joinedAt: Instant?,
  val pricing: String?,
) {
  companion object {
    /** 공급자의 값을 용도별 공개 응답으로 투영하기 전 내부 조회 값으로 만든다. */
    fun from(membership: GroupMembership) =
      MembershipView(
        membership.groupId,
        membership.groupName,
        membership.permission,
        membership.permissionName,
        membership.joinedAt,
        membership.pricing,
      )
  }
}
