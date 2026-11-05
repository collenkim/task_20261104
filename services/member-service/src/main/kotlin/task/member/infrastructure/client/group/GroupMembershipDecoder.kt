package task.member.infrastructure.client.group

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.model.GroupMembership
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** 원격 계약과 local fixture를 별도로 해석한다. 원문을 예외에 담지 않는다(POL-COMM-005). */
class GroupMembershipDecoder(mapper: JsonMapper) {
  private val strictMapper =
    mapper
      .rebuild()
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
      .build()

  /** fixture 전용 형식. 원격 API의 그룹 중심 응답과 결합하지 않는다(POL-MEMBER-002). */
  fun decode(bytes: ByteArray): Map<String, List<GroupMembership>> {
    val members =
      array(root(bytes).get("members")).map { member ->
        val node = obj(member)
        MemberMembershipPayload(
          text(node, "memberId"),
          array(node.get("memberships")).map { raw ->
            val group = obj(raw)
            MembershipPayload(
              text(group, "groupId"),
              text(group, "groupName"),
              text(group, "permission"),
              optionalText(group, "joinedAt")?.let(::parseFixtureTimestamp),
              optionalText(group, "pricing"),
            )
          },
        )
      }
    return GroupMembershipContract.toMemberships(GroupMembershipPayload(members))
  }

  /** 성공한 sparse summary만 요청 ID별 미소속으로 초기화한다(POL-COMM-005). */
  fun decodeSummary(bytes: ByteArray, memberIds: List<String>): Map<String, List<GroupMembership>> {
    GroupMembershipContract.validateRequest(memberIds)
    val result = memberIds.associateWith { emptyList<GroupMembership>() }.toMutableMap()
    val groupIds = mutableSetOf<String>()
    val seen = mutableSetOf<String>()
    for (raw in array(root(bytes).get("group"))) {
      val group = obj(raw)
      val id = text(group, "groupId")
      val name = text(group, "groupName")
      if (!ID.matches(id) || !groupIds.add(id)) fail()
      val members = array(group.get("members"))
      val total = group.get("totalMemberCount")
      if (
        members.isEmpty() ||
          total == null ||
          !total.isIntegralNumber ||
          !total.canConvertToLong() ||
          total.longValue() < members.size
      )
        fail()
      for (rawMember in members) {
        val member = obj(rawMember)
        val memberId = text(member, "memberId")
        if (memberId !in result || !seen.add(memberId)) fail()
        result[memberId] = listOf(GroupMembership(id, name, text(member, "permission"), null, null))
      }
    }
    return result
  }

  /** 그룹 상세의 기존 6개 필드를 해석한다. 시각은 분 정밀도임을 보존한다(POL-API-005). */
  fun decodeDetail(bytes: ByteArray, memberId: String): GroupMembership {
    val node = root(bytes)
    if (text(node, "memberId") != memberId) fail()
    val id = text(node, "groupId")
    if (!ID.matches(id)) fail()
    val instant =
      try {
        val value = text(node, "registeredDate")
        if (!MINUTE_PATTERN.matches(value)) fail()
        LocalDateTime.parse(value, MINUTE_FORMAT).atZone(ZoneId.of("Asia/Seoul")).toInstant()
      } catch (_: DateTimeParseException) {
        fail()
      }
    return GroupMembership(
      id,
      text(node, "groupName"),
      text(node, "permission"),
      instant,
      text(node, "pricing"),
    )
  }

  /** 경로 404와 구분되는 정상 미소속만 허용한다(POL-COMM-004). */
  fun isMembershipNotFound(bytes: ByteArray): Boolean {
    val node = root(bytes)
    return text(node, "code") == "GROUP_MEMBERSHIP_NOT_FOUND" &&
      node.get("status")?.let {
        it.isIntegralNumber && it.canConvertToInt() && it.intValue() == 404
      } == true
  }

  private fun root(bytes: ByteArray): JsonNode =
    try {
      obj(strictMapper.readTree(bytes))
    } catch (_: JacksonException) {
      fail()
    }

  private fun obj(node: JsonNode?): JsonNode = node?.takeIf { it.isObject } ?: fail()

  private fun array(node: JsonNode?): List<JsonNode> =
    node?.takeIf { it.isArray }?.toList() ?: fail()

  private fun text(node: JsonNode, name: String): String =
    node.get(name)?.takeIf { it.isString }?.stringValue()?.takeIf { it.isNotBlank() } ?: fail()

  private fun optionalText(node: JsonNode, name: String): String? {
    val value = node.get(name) ?: return null
    if (value.isNull) return null
    return value.takeIf { it.isString }?.stringValue() ?: fail()
  }

  private fun parseFixtureTimestamp(value: String): Instant =
    try {
      if (!UTC_TIMESTAMP.matches(value)) fail()
      OffsetDateTime.parse(value).toInstant()
    } catch (_: DateTimeParseException) {
      fail()
    }

  private fun fail(): Nothing = throw GroupServiceConfigurationException()

  private companion object {
    val ID = Regex("[a-z0-9]{10}")
    val MINUTE_PATTERN = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}")
    val MINUTE_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
    val UTC_TIMESTAMP =
      Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?(Z|\\+00:00)")
  }
}
