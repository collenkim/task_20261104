package task.member.infrastructure.client.group

import java.time.Instant

/** 그룹 전송 계약 DTO. 필수 목록의 부재를 기본 빈 배열로 숨기지 않는다(POL-COMM-005). */
internal data class GroupMembershipPayload(val members: List<MemberMembershipPayload>)

/** 멤버 하나의 전송 항목. application 포트에 노출하지 않는다(POL-CQRS-004). */
internal data class MemberMembershipPayload(
  val memberId: String,
  val memberships: List<MembershipPayload>,
)

/** 제공자 필드를 명시적으로 보존하는 내부 전송 DTO(POL-COMM-005). */
internal data class MembershipPayload(
  val groupId: String,
  val groupName: String,
  val permission: String,
  val permissionName: String,
  val joinedAt: Instant?,
  val pricing: String?,
)
