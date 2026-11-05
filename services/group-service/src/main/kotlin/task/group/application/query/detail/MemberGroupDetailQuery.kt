package task.group.application.query.detail

import task.group.application.exception.InvalidGroupQueryException

/** 멤버 식별자 형식은 소비자 계약과 일치한다(POL-GROUP-001). */
data class MemberGroupDetailQuery(val memberId: String) {
  init {
    if (!memberId.matches(Regex("[a-z0-9]{10}"))) throw InvalidGroupQueryException()
  }
}
