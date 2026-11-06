package task.member.presentation.query

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import task.member.application.exception.InvalidMemberQueryException
import task.member.application.exception.UnprocessableMemberQueryException
import task.member.application.model.MemberNameSort
import task.member.application.query.detail.MemberDetailQuery
import task.member.presentation.query.request.MemberSummaryRequest

/** HTTP 바인딩의 자동 보정을 거치지 않는 요청 경계 검사(POL-TEST-006). 형식 위반은 400, 의미상 처리 불가는 422다(POL-API-003). */
class MemberQueryRequestTests {
  @Test
  fun `생략만 기본값이며 선행 0과 정상 경계는 숫자로 해석한다`() {
    val default = MemberSummaryRequest.from(emptyMap()).toQuery()
    assertEquals(0, default.page.number)
    assertEquals(20, default.page.size)
    assertEquals(MemberNameSort.ASCENDING, default.sort)
    val leading = query("page" to "00001", "size" to "00002", "sort" to "name,desc")
    assertEquals(1, leading.page.number)
    assertEquals(2, leading.page.size)
    assertEquals(MemberNameSort.DESCENDING, leading.sort)
    assertEquals(2147483647L, query("page" to "2147483647", "size" to "1").page.offset)
    assertEquals(100, query("size" to "101").page.size)
    assertEquals(100, query("size" to "2147483647").page.size)
  }

  @Test
  fun `빈 값 공백 부호 소수 비ASCII 숫자 정수 초과는 크기 보정 전에 형식 위반으로 거부한다`() {
    for (name in listOf("page", "size")) {
      for (value in
        listOf(
          "",
          " ",
          "\t",
          " 1",
          "1 ",
          "+1",
          "-1",
          "1.0",
          "1e2",
          "abc",
          "１",
          "١",
          "2147483648",
          "9".repeat(80),
        )) {
        assertFailsWith<InvalidMemberQueryException>("$name 입력 형식") { query(name to value) }
      }
    }
  }

  @Test
  fun `형식은 맞지만 size 0과 offset 상한 초과는 처리 불가다`() {
    assertFailsWith<UnprocessableMemberQueryException> { query("size" to "0") }
    assertFailsWith<UnprocessableMemberQueryException> { query("size" to "000") }
    assertFailsWith<UnprocessableMemberQueryException> {
      query("page" to "21474837", "size" to "100")
    }
    assertFailsWith<UnprocessableMemberQueryException> { query("page" to "2147483647") }
    assertEquals(21474836, query("page" to "21474836", "size" to "100").page.number)
  }

  @Test
  fun `반복과 빈 정렬은 형식 위반이고 허용하지 않는 정렬 기준은 처리 불가다`() {
    for (name in listOf("page", "size", "sort")) {
      for (values in listOf(emptyList(), listOf("1", "1"), listOf("1", "2"))) {
        assertFailsWith<InvalidMemberQueryException> {
          MemberSummaryRequest.from(mapOf(name to values)).toQuery()
        }
      }
    }
    for (sort in listOf("", " ", "\t")) {
      assertFailsWith<InvalidMemberQueryException> { query("sort" to sort) }
    }
    for (sort in
      listOf("name", "name,ASC", "id,asc", "name,asc ", " name,asc", "name,desc,id,asc")) {
      assertFailsWith<UnprocessableMemberQueryException> { query("sort" to sort) }
    }
  }

  @Test
  fun `상세 ID는 HTTP 외 입력에서도 소문자 영숫자 열 자리만 허용한다`() {
    assertEquals("tu3fgxd45k", MemberDetailQuery("tu3fgxd45k").id)
    for (id in
      listOf(
        "",
        "member001",
        "member00001",
        "MEMBER0001",
        "member000_",
        "member000 ",
        "member0001\n",
      )) {
      assertFailsWith<InvalidMemberQueryException> { MemberDetailQuery(id) }
    }
  }

  private fun query(vararg parameters: Pair<String, String>) =
    MemberSummaryRequest.from(parameters.associate { it.first to listOf(it.second) }).toQuery()
}
