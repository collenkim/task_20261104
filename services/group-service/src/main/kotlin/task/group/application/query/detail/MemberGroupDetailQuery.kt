package task.group.application.query.detail

import task.group.application.exception.InvalidGroupQueryException

/** 멤버 식별자 형식은 소비자 계약과 일치한다. 형식 위반은 400이다(POL-GROUP-001). */
data class MemberGroupDetailQuery(val memberId: String) {
  init {
    if (!ID.matches(memberId)) throw InvalidGroupQueryException()
  }

  private companion object {
    val ID = Regex("[a-z0-9]{10}")
  }
}
