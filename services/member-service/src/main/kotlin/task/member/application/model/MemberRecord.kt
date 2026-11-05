package task.member.application.model

import java.time.Instant

/** 멤버 DB의 상세 조회 값. 엔티티·원격 그룹 DTO·공개 응답과 분리한다(POL-CQRS-004). */
data class MemberRecord(
  val id: String,
  val name: String,
  val email: String,
  val address: String,
  val registeredAt: Instant,
  val createdAt: Instant,
  val createdBy: String,
)

/** 목록에 필요한 멤버 기본 값만 읽으며 개인정보 열은 조회하지 않는다(POL-CQRS-002). */
data class MemberSummaryRecord(val id: String, val name: String)
