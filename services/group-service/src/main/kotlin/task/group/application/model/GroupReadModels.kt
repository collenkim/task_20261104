package task.group.application.model

import java.time.Instant
import task.group.domain.GroupPermissionType
import task.group.domain.GroupPricingType

/** 트랜잭션 밖으로 전달하는 값. JPA 엔티티를 노출하지 않는다(POL-CQRS-002). */
data class GroupMembershipRecord(
  val memberId: String,
  val groupId: String,
  val groupName: String,
  val registeredAt: Instant,
  val pricing: GroupPricingType,
  val permission: GroupPermissionType,
)

/** 같은 DB 스냅샷에서 조회한 소속과 전체 인원(POL-GROUP-002). */
data class GroupReadSnapshot(
  val memberships: List<GroupMembershipRecord>,
  val counts: Map<String, Long>,
)

/** 그룹별 묶기 결과. 실제 응답 DTO와 분리한다(POL-CQRS-004). */
data class GroupSummaryView(
  val groupId: String,
  val groupName: String,
  val totalMemberCount: Long,
  val members: List<GroupMembershipRecord>,
)
