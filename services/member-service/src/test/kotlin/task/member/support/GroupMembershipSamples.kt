package task.member.support

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import tools.jackson.databind.json.JsonMapper

/** 전송 시험용 합성 계약과 fixture 투영. 실제 제공자는 그룹 서비스 시험으로 따로 검증한다(POL-TEST-006). */
object GroupMembershipSamples {
  const val RESPONSE =
    """{"members":[
  {"memberId":"member0002","memberships":[]},
  {"memberId":"member0001","memberships":[{"groupId":"group00002","groupName":"두 번째 그룹","permission":"FutureOwner","joinedAt":"2026-11-04T23:59:59.123456Z","pricing":"NewPlan"}]}
 ]}"""
  const val SUMMARY =
    """{"group":[{"groupId":"group00002","groupName":"두 번째 그룹","totalMemberCount":1,"members":[{"memberId":"member0001","permission":"FutureOwner"}]}]}"""
  const val DETAIL =
    """{"memberId":"member0001","groupId":"group00002","groupName":"두 번째 그룹","registeredDate":"2026-11-05 08:59","pricing":"NewPlan","permission":"FutureOwner"}"""
  private val json = JsonMapper.builder().build()

  fun summary(fixture: String, ids: List<String>): String {
    val rows =
      json.readTree(fixture).path("members").toList().flatMap { m ->
        m.path("memberships").toList().map { g -> m.path("memberId").stringValue() to g }
      }
    val groups =
      rows
        .filter { it.first in ids }
        .groupBy { it.second.path("groupId").stringValue() }
        .map { (id, members) ->
          mapOf(
            "groupId" to id,
            "groupName" to members.first().second.path("groupName").stringValue(),
            "totalMemberCount" to members.size,
            "members" to
              members.map {
                mapOf(
                  "memberId" to it.first,
                  "permission" to it.second.path("permission").stringValue(),
                )
              },
          )
        }
    return json.writeValueAsString(mapOf("group" to groups))
  }

  fun detail(fixture: String, id: String): String {
    val member =
      json.readTree(fixture).path("members").single { it.path("memberId").stringValue() == id }
    val group = member.path("memberships").single()
    return json.writeValueAsString(
      mapOf(
        "memberId" to id,
        "groupId" to group.path("groupId").stringValue(),
        "groupName" to group.path("groupName").stringValue(),
        "permission" to group.path("permission").stringValue(),
        "pricing" to group.path("pricing").stringValue(),
        "registeredDate" to
          DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Seoul"))
            .format(Instant.parse(group.path("joinedAt").stringValue())),
      )
    )
  }
}
