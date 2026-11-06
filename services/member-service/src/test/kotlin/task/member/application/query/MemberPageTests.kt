package task.member.application.query

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import task.member.application.model.MemberNameSort
import task.member.application.model.MemberPageRequest
import task.member.application.model.PageResult

/** HTTP 자동 보정과 독립적인 조회 모델의 범위·큰 건수 계산을 검증한다(POL-API-003). */
class MemberPageTests {
  @Test
  fun `기본 조회 조건은 첫 페이지와 크기 20이다`() {
    val page = MemberPageRequest()
    assertEquals(0, page.number)
    assertEquals(20, page.size)
    assertEquals(0L, page.offset)
  }

  @Test
  fun `음수 페이지와 적용 범위 밖 크기는 거부한다`() {
    assertFailsWith<IllegalArgumentException> { MemberPageRequest(-1, 20) }
    for (size in listOf(Int.MIN_VALUE, -1, 0, 101, Int.MAX_VALUE)) {
      assertFailsWith<IllegalArgumentException> { MemberPageRequest(0, size) }
    }
  }

  @Test
  fun `offset의 정수 최댓값은 허용하고 넘는 곱은 오버플로 없이 거부한다`() {
    assertEquals(Int.MAX_VALUE.toLong(), MemberPageRequest(Int.MAX_VALUE, 1).offset)
    assertEquals(2147483600L, MemberPageRequest(21474836, 100).offset)
    assertFailsWith<IllegalArgumentException> { MemberPageRequest(21474837, 100) }
    assertFailsWith<IllegalArgumentException> { MemberPageRequest(Int.MAX_VALUE, 100) }
  }

  @Test
  fun `전체 건수가 없으면 페이지 수는 0이고 나머지 행도 한 페이지로 센다`() {
    assertEquals(0L, PageResult(emptyList<String>(), 0, 20, 0).totalPages)
    assertEquals(1L, PageResult(listOf("a"), 0, 20, 20).totalPages)
    assertEquals(2L, PageResult(listOf("a"), 0, 20, 21).totalPages)
    assertEquals(2L, PageResult(emptyList<String>(), 10, 20, 21).totalPages)
  }

  @Test
  fun `페이지 수는 큰 건수에서도 덧셈 오버플로와 소수 정밀도 손실 없이 계산한다`() {
    assertEquals(Long.MAX_VALUE, PageResult(emptyList<String>(), 0, 1, Long.MAX_VALUE).totalPages)
    assertEquals(
      4611686018427387904L,
      PageResult(emptyList<String>(), 0, 2, Long.MAX_VALUE).totalPages,
    )
  }

  @Test
  fun `결과 페이지는 잘못된 메타데이터나 크기를 넘는 항목을 허용하지 않는다`() {
    assertFailsWith<IllegalArgumentException> { PageResult(emptyList<String>(), -1, 20, 0) }
    assertFailsWith<IllegalArgumentException> { PageResult(emptyList<String>(), 0, 0, 0) }
    assertFailsWith<IllegalArgumentException> { PageResult(emptyList<String>(), 0, 20, -1) }
    assertFailsWith<IllegalArgumentException> { PageResult(listOf("a", "b"), 0, 1, 2) }
  }

  @Test
  fun `정렬 코드에는 방향과 한국어 이름과 설명이 있으며 이름 정렬 두 가지만 허용한다`() {
    assertEquals(setOf("name,asc", "name,desc"), MemberNameSort.entries.map { it.code }.toSet())
    assertEquals(setOf("ASC", "DESC"), MemberNameSort.entries.map { it.status }.toSet())
    assertTrue(
      MemberNameSort.entries.all { it.codeName.isNotBlank() && it.description.isNotBlank() }
    )
  }
}
