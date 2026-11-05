package task.member.infrastructure.fixture.group

import java.io.IOException
import org.springframework.core.io.Resource
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.model.GroupMembership
import task.member.application.port.output.GroupMembershipReader
import task.member.infrastructure.client.group.GroupMembershipContract
import task.member.infrastructure.client.group.GroupMembershipDecoder

/** local 개발용 소속 공급자. 원격 장애를 숨기는 fallback으로 사용하지 않는다(POL-MEMBER-002). */
class FixtureGroupMembershipReader(resource: Resource, decoder: GroupMembershipDecoder) :
  GroupMembershipReader {
  private val memberships =
    try {
      resource.inputStream
        .use { decoder.decode(it.readAllBytes()) }
        .also {
          if (it.isEmpty()) throw GroupServiceConfigurationException()
        }
    } catch (_: IOException) {
      throw GroupServiceConfigurationException()
    }

  override fun findByMemberIds(memberIds: List<String>): Map<String, List<GroupMembership>> {
    GroupMembershipContract.validateRequest(memberIds)
    if (!memberships.keys.containsAll(memberIds)) throw GroupServiceConfigurationException()
    return memberIds.associateWith { memberships.getValue(it) }
  }
}
