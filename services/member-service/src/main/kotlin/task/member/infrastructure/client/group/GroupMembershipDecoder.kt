package task.member.infrastructure.client.group

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.model.GroupMembership
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** JSON 타입을 강제 변환하지 않고 전송 DTO로 읽는다. 잘못된 원문은 예외에 담지 않는다(POL-COMM-004·005). */
class GroupMembershipDecoder(mapper: JsonMapper) {
  private val strictMapper =
    mapper
      .rebuild()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
      .build()

  /** 필수 필드·타입·순서 독립성·중복을 검사하고 기술 독립 모델로 변환한다(POL-MEMBER-001). */
  fun decode(bytes: ByteArray): Map<String, List<GroupMembership>> {
    val root =
      try {
        strictMapper.readTree(bytes)
      } catch (_: JacksonException) {
        throw GroupServiceConfigurationException()
      }
    val members =
      array(objectNode(root).get("members")).map { member ->
        val node = objectNode(member)
        MemberMembershipPayload(
          text(node, "memberId"),
          array(node.get("memberships")).map { item ->
            val group = objectNode(item)
            MembershipPayload(
              text(group, "groupId"),
              text(group, "groupName"),
              text(group, "permission"),
              text(group, "permissionName"),
              optionalText(group, "joinedAt")?.let(::parseTimestamp),
              optionalText(group, "pricing"),
            )
          },
        )
      }
    return GroupMembershipContract.toMemberships(GroupMembershipPayload(members))
  }

  private fun objectNode(node: JsonNode?): JsonNode =
    node?.takeIf { it.isObject } ?: throw GroupServiceConfigurationException()

  private fun array(node: JsonNode?): List<JsonNode> =
    node?.takeIf { it.isArray }?.toList() ?: throw GroupServiceConfigurationException()

  private fun text(node: JsonNode, name: String): String =
    node.get(name)?.takeIf { it.isString }?.stringValue()
      ?: throw GroupServiceConfigurationException()

  private fun optionalText(node: JsonNode, name: String): String? {
    val value = node.get(name) ?: return null
    if (value.isNull) return null
    return value.takeIf { it.isString }?.stringValue() ?: throw GroupServiceConfigurationException()
  }

  private fun parseTimestamp(value: String) =
    try {
      if (!UTC_TIMESTAMP.matches(value)) throw GroupServiceConfigurationException()
      OffsetDateTime.parse(value).toInstant()
    } catch (_: DateTimeParseException) {
      throw GroupServiceConfigurationException()
    }

  private companion object {
    val UTC_TIMESTAMP =
      Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?(Z|\\+00:00)")
  }
}
