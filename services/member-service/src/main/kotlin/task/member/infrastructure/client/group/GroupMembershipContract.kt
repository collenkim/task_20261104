package task.member.infrastructure.client.group

import java.util.Collections
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.model.GroupMembership

/** HTTP와 fixture가 공유하는 순수 소속 계약 검사. JSON·전송·Spring에 의존하지 않는다(POL-MEMBER-001). */
internal object GroupMembershipContract {
  private val idPattern = Regex("[a-z0-9]{10}")

  fun validateRequest(memberIds: List<String>) {
    if (
      memberIds.size !in 1..100 ||
        memberIds.distinct().size != memberIds.size ||
        memberIds.any { !idPattern.matches(it) }
    )
      throw GroupServiceConfigurationException()
  }

  fun toMemberships(payload: GroupMembershipPayload): Map<String, List<GroupMembership>> {
    val members = payload.members
    if (members.map { it.memberId }.distinct().size != members.size) {
      throw GroupServiceConfigurationException()
    }
    val mapped = members.associate { member ->
      if (
        !idPattern.matches(member.memberId) ||
          member.memberships.size > 1 ||
          member.memberships.map { it.groupId }.distinct().size != member.memberships.size
      )
        throw GroupServiceConfigurationException()
      val groups =
        member.memberships
          .map { group ->
            if (
              !idPattern.matches(group.groupId) ||
                group.groupName.isBlank() ||
                group.permission.isBlank()
            )
              throw GroupServiceConfigurationException()
            GroupMembership(
              group.groupId,
              group.groupName,
              group.permission,
              group.joinedAt,
              group.pricing?.takeUnless { it.isBlank() },
            )
          }
          .sortedBy { it.groupId }
      member.memberId to Collections.unmodifiableList(groups)
    }
    return Collections.unmodifiableMap(mapped)
  }
}
