package task.member.infrastructure.client

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import task.member.application.exception.GroupServiceConfigurationException
import task.member.infrastructure.client.group.GroupMembershipDecoder
import task.member.support.GroupMembershipSamples
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** 필수 값 누락·타입 강제 변환·중복·선택 정보의 계약 경계를 검증한다(POL-TEST-006). */
class GroupMembershipDecoderTests {
  private val mapper = JsonMapper.builder().build()
  private val decoder = GroupMembershipDecoder(mapper)

  @Test
  fun `단일 소속과 새 권한 과금 코드를 허용하고 그룹 ID 순서와 마이크로초를 보존한다`() {
    val result = decoder.decode(GroupMembershipSamples.RESPONSE.toByteArray())
    val groups = result.getValue("member0001")
    assertEquals(listOf("group00002"), groups.map { it.groupId })
    assertEquals("FutureOwner", groups[0].permission)
    assertEquals("NewPlan", groups[0].pricing)
    assertEquals(Instant.parse("2026-11-04T23:59:59.123456Z"), groups[0].joinedAt)
    assertEquals(emptyList(), result.getValue("member0002"))
  }

  @Test
  fun `알 수 없는 필드와 서로 다른 멤버의 같은 그룹 소속은 허용한다`() {
    val root = sample()
    root.put("futureField", true)
    member(root).put("futureMemberField", 42)
    group(root).set("futureGroupField", mapper.createObjectNode().put("x", true))
    val other = (root.get("members") as ArrayNode).get(0) as ObjectNode
    (other.get("memberships") as ArrayNode).add(group(root).deepCopy())
    val decoded = decoder.decode(mapper.writeValueAsBytes(root))
    assertEquals("group00002", decoded.getValue("member0002").single().groupId)
  }

  @Test
  fun `선택 필드의 null과 부재를 허용하고 빈 과금 코드는 null로 정규화한다`() {
    for (pricing in listOf("", " ", "\t")) {
      val root = sample()
      group(root).put("pricing", pricing).putNull("joinedAt")
      val value = decoder.decode(mapper.writeValueAsBytes(root)).getValue("member0001")[0]
      assertNull(value.pricing)
      assertNull(value.joinedAt)
    }
    val root = sample()
    group(root).put("joinedAt", "2026-11-04T23:59:59.123456+00:00").putNull("pricing")
    assertEquals(
      Instant.parse("2026-11-04T23:59:59.123456Z"),
      decoder.decode(mapper.writeValueAsBytes(root)).getValue("member0001")[0].joinedAt,
    )
  }

  @Test
  fun `루트와 필수 배열은 없거나 null이거나 다른 타입이면 거부한다`() {
    for (body in
      listOf(
        "",
        "null",
        "[]",
        "{}",
        "{\"members\":null}",
        "{\"members\":{}}",
        "{\"members\":[null]}",
        "{\"members\":[42]}",
      )) {
      rejected(body.toByteArray())
    }
    for (raw in listOf("null", "{}", "42", "\"value\"")) {
      val root = sample()
      member(root).set("memberships", mapper.readTree(raw))
      rejected(mapper.writeValueAsBytes(root))
    }
    val root = sample()
    member(root).remove("memberships")
    rejected(mapper.writeValueAsBytes(root))
  }

  @Test
  fun `필수 그룹 필드는 누락 null 공백 숫자 불리언 배열 객체를 문자열로 바꾸지 않는다`() {
    for (field in listOf("groupId", "groupName", "permission")) {
      val omitted = sample()
      group(omitted).remove(field)
      rejected(mapper.writeValueAsBytes(omitted))
      for (raw in listOf("null", "\"\"", "\" \"", "42", "true", "[]", "{}")) {
        val root = sample()
        group(root).set(field, mapper.readTree(raw))
        rejected(mapper.writeValueAsBytes(root))
      }
    }
    val root = sample()
    (member(root).get("memberships") as ArrayNode).addNull()
    rejected(mapper.writeValueAsBytes(root))
  }

  @Test
  fun `식별자의 누락과 형식 위반을 거부한다`() {
    val root = sample()
    member(root).remove("memberId")
    rejected(mapper.writeValueAsBytes(root))
    for (raw in
      listOf(
        "null",
        "42",
        "true",
        "\"short\"",
        "\"MEMBER0001\"",
        "\"member0001 \"",
        "\"멤버00000001\"",
      )) {
      val changed = sample()
      member(changed).set("memberId", mapper.readTree(raw))
      rejected(mapper.writeValueAsBytes(changed))
    }
    for (value in listOf("GROUP00001", "short", "group00001 ")) {
      val changed = sample()
      group(changed).put("groupId", value)
      rejected(mapper.writeValueAsBytes(changed))
    }
  }

  @Test
  fun `같은 멤버 항목과 같은 멤버 안의 그룹 중복은 덮어쓰지 않고 거부한다`() {
    val duplicateMember = sample()
    (duplicateMember.get("members") as ArrayNode).add(member(duplicateMember).deepCopy())
    rejected(mapper.writeValueAsBytes(duplicateMember))
    val duplicateGroup = sample()
    (member(duplicateGroup).get("memberships") as ArrayNode).add(group(duplicateGroup).deepCopy())
    rejected(mapper.writeValueAsBytes(duplicateGroup))
  }

  @Test
  fun `선택 시각도 잘못된 날짜 UTC 외 시차 과도한 정밀도이면 거부한다`() {
    for (value in
      listOf(
        "",
        " ",
        "2026-02-30T00:00:00Z",
        "2026-11-05T00:00:00+09:00",
        "2026-11-05T00:00:00",
        "2026-11-05T00:00:00.1234567Z",
        "2026-11-05",
        "2026-11-05T24:00:00Z",
      )) {
      val root = sample()
      group(root).put("joinedAt", value)
      rejected(mapper.writeValueAsBytes(root))
    }
    for (field in listOf("pricing", "joinedAt")) {
      val root = sample()
      group(root).put(field, 42)
      rejected(mapper.writeValueAsBytes(root))
    }
  }

  @Test
  fun `잘못된 JSON 중복 키 후행 문서를 거부하고 원문을 예외에 포함하지 않는다`() {
    for (body in
      listOf(
        "{private-invalid",
        "{\"members\":[],\"members\":[]}",
        GroupMembershipSamples.RESPONSE + " {}",
      )) {
      val error = rejected(body.toByteArray())
      assertEquals("그룹 호출 설정 또는 응답 계약 오류", error.message)
      assertNull(error.cause)
    }
  }

  private fun sample() = mapper.readTree(GroupMembershipSamples.RESPONSE) as ObjectNode

  private fun member(root: ObjectNode) = (root.get("members") as ArrayNode).get(1) as ObjectNode

  private fun group(root: ObjectNode) =
    (member(root).get("memberships") as ArrayNode).get(0) as ObjectNode

  private fun rejected(bytes: ByteArray) =
    assertFailsWith<GroupServiceConfigurationException> { decoder.decode(bytes) }
}
