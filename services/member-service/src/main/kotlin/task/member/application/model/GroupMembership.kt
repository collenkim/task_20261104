package task.member.application.model

import java.time.Instant

/** 그룹 소속 조회 값. 제공자의 권한 코드를 열린 문자열로 수용한다(POL-MEMBER-001, POL-CQRS-004). */
data class GroupMembership(
  val groupId: String,
  val groupName: String,
  val permission: String,
  val joinedAt: Instant?,
  val pricing: String?,
)
