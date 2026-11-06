package task.group.application.query

import java.time.Instant
import kotlin.test.*
import org.junit.jupiter.api.Test
import task.group.application.exception.*
import task.group.application.model.*
import task.group.application.port.output.*
import task.group.application.query.detail.*
import task.group.application.query.summary.*
import task.group.domain.*

/** 순수 조회와 다건 방어, 입력 범위를 검증한다(POL-GROUP-001·002). */
class GroupQueryTests {
  private fun row(member: String = "member0001", group: String = "group00001") =
    GroupMembershipRecord(
      member,
      group,
      "합성 그룹",
      Instant.parse("2026-08-15T04:00:59.123456Z"),
      GroupPricingType.FREE,
      GroupPermissionType.VIEWER,
    )

  @Test
  fun `상세는 미소속 단일 다건을 구분하고 대표 행을 임의로 고르지 않는다`() {
    val query = MemberGroupDetailQuery("member0001")
    assertFailsWith<GroupMembershipNotFoundException> {
      MemberGroupDetailQueryHandler(MemberGroupReader { emptyList() }).execute(query)
    }
    assertEquals(
      row(),
      MemberGroupDetailQueryHandler(MemberGroupReader { listOf(row()) }).execute(query),
    )
    assertFailsWith<GroupMembershipAmbiguousException> {
      MemberGroupDetailQueryHandler(MemberGroupReader { listOf(row(), row(group = "group00002")) })
        .execute(query)
    }
  }

  @Test
  fun `요청 멤버를 묶고 실제 반환 인원과 정렬을 보존한다`() {
    val rows = listOf(row("member0002"), row("member0001"))
    val result =
      GroupSummaryQueryHandler(GroupSummaryReader { GroupReadSnapshot(rows) })
        .execute(GroupSummaryQuery(listOf("member0002", "member0001")))
    assertEquals(2, result.single().totalMemberCount)
    assertEquals(listOf("member0001", "member0002"), result.single().members.map { it.memberId })
  }

  @Test
  fun `summary도 같은 멤버의 서로 다른 그룹 소속을 오류로 인지한다`() {
    assertFailsWith<GroupMembershipAmbiguousException> {
      GroupSummaryQueryHandler(
          GroupSummaryReader {
            GroupReadSnapshot(listOf(row(), row(group = "group00002")))
          }
        )
        .execute(GroupSummaryQuery(listOf("member0001")))
    }
  }

  @Test
  fun `누락 빈 값 형식 위반 콤마 결합은 보정하지 않고 형식 위반이다`() {
    val invalid =
      listOf(
        emptyList(),
        listOf(""),
        listOf(" "),
        listOf("MEMBER0001"),
        listOf("member0001,member0002"),
        listOf("member0001", "bad"),
      )
    invalid.forEach { ids ->
      assertFailsWith<InvalidGroupQueryException> { GroupSummaryQuery(ids) }
    }
  }

  @Test
  fun `원래 입력이 100개를 넘으면 중복을 빼도 처리 불가이고 100개까지는 허용한다`() {
    val hundred = (1..100).map { "m" + it.toString().padStart(9, '0') }
    assertEquals(100, GroupSummaryQuery(hundred).memberIds.size)
    assertFailsWith<UnprocessableGroupQueryException> {
      GroupSummaryQuery((1..101).map { "m" + it.toString().padStart(9, '0') })
    }
    assertFailsWith<UnprocessableGroupQueryException> {
      GroupSummaryQuery(hundred + hundred.first())
    }
  }

  @Test
  fun `중복 ID는 거부하지 않고 처음 나온 순서로 한 번만 조회하며 중복된 ID를 따로 남긴다`() {
    val query =
      GroupSummaryQuery(
        listOf("member0002", "member0001", "member0002", "member0003", "member0001")
      )
    assertEquals(listOf("member0002", "member0001", "member0003"), query.memberIds)
    assertEquals(listOf("member0002", "member0001"), query.duplicateIds)
    assertEquals(5, query.requestedIds.size)
    assertEquals(emptyList(), GroupSummaryQuery(listOf("member0001")).duplicateIds)
  }

  @Test
  fun `코드의 내부 메타데이터와 정확한 문자열 변환을 검증한다`() {
    GroupPermissionType.entries.forEach {
      assertEquals(it, GroupPermissionType.fromCode(it.code))
      assertTrue(it.codeName.isNotBlank())
      assertTrue(it.description.isNotBlank())
      assertEquals("ACTIVE", it.status)
    }
    GroupPricingType.entries.forEach {
      assertEquals(it, GroupPricingType.fromCode(it.code))
      assertTrue(it.codeName.isNotBlank())
      assertTrue(it.description.isNotBlank())
    }
    assertFailsWith<IllegalArgumentException> { GroupPricingType.fromCode("free") }
    assertFailsWith<IllegalArgumentException> { GroupPermissionType.fromCode("unknown") }
  }
}
