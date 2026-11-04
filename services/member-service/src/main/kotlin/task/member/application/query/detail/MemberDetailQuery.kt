package task.member.application.query.detail

import task.member.application.exception.InvalidMemberQueryException

/** HTTP 외 호출자도 같은 식별자 규칙으로 조회한다(POL-API-003). */
data class MemberDetailQuery(val id: String) {
  init {
    if (!ID.matches(id)) throw InvalidMemberQueryException()
  }

  companion object {
    private val ID = Regex("^[a-z0-9]{10}$")
  }
}
