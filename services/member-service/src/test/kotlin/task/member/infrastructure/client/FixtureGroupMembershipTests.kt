package task.member.infrastructure.client

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.port.output.GroupMembershipReader
import task.member.infrastructure.client.group.GroupMembershipDecoder
import task.member.infrastructure.fixture.group.FixtureGroupMembershipReader
import tools.jackson.databind.json.JsonMapper

/** 임시 데이터의 명시적 미소속·단일 소속·오류 규칙을 검증한다(POL-MEMBER-002). */
class FixtureGroupMembershipTests {
  private val decoder = GroupMembershipDecoder(JsonMapper.builder().build())
  private val reader: GroupMembershipReader =
    FixtureGroupMembershipReader(
      ClassPathResource("fixtures/local/group-memberships.json"),
      decoder,
    )

  @Test
  fun `계획된 다섯 멤버를 포함하며 미소속과 단일 소속을 구분한다`() {
    val ids = listOf("tu3fgxd45k", "dj8fy4bewk", "k2m9x0p1q3", "j0n8k2m4p6", "q1w2e3r4t5")
    val result = reader.findByMemberIds(ids)
    assertEquals(ids.toSet(), result.keys)
    assertEquals(listOf(1, 1, 0, 0, 1), ids.map { result.getValue(it).size })
    assertEquals(listOf("Editor"), result.getValue("tu3fgxd45k").map { it.permission })
    assertEquals("Test1", result.getValue("dj8fy4bewk").single().groupName)
    assertEquals("프로젝트팀", result.getValue("q1w2e3r4t5").single().groupName)
    assertEquals(
      listOf("q1w2e3r4t5", "tu3fgxd45k"),
      reader.findByMemberIds(listOf("q1w2e3r4t5", "tu3fgxd45k")).keys.toList(),
    )
  }

  @Test
  fun `없는 ID와 잘못된 요청을 임의의 미소속으로 대체하지 않는다`() {
    for (ids in
      listOf(
        emptyList(),
        listOf("unknown001"),
        listOf("tu3fgxd45k", "tu3fgxd45k"),
        listOf("bad"),
        List(101) { "m" + it.toString().padStart(9, '0') },
      )) {
      assertFailsWith<GroupServiceConfigurationException> { reader.findByMemberIds(ids) }
    }
  }

  @Test
  fun `누락 파일과 손상된 내용과 빈 공급자는 생성 시 거부한다`() {
    assertFailsWith<GroupServiceConfigurationException> {
      FixtureGroupMembershipReader(ClassPathResource("fixtures/local/absent.json"), decoder)
    }
    for (body in
      listOf("broken", "{}", "{\"members\":[]}", "{\"members\":[{\"memberId\":\"member0001\"}]}")) {
      assertFailsWith<GroupServiceConfigurationException> {
        FixtureGroupMembershipReader(ByteArrayResource(body.toByteArray()), decoder)
      }
    }
  }

  @Test
  fun `반환된 소속을 수정해서 이후 요청의 임시 데이터를 오염시킬 수 없다`() {
    val result = reader.findByMemberIds(listOf("tu3fgxd45k"))
    assertFailsWith<UnsupportedOperationException> {
      (result.getValue("tu3fgxd45k") as MutableList).clear()
    }
    assertEquals(1, reader.findByMemberIds(listOf("tu3fgxd45k")).getValue("tu3fgxd45k").size)
  }
}
