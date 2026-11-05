package task.member.application.query.detail

import java.time.Instant
import task.member.application.query.MembershipView

/** DB 값과 소속을 결합한 상세 조회 결과. JPA·HTTP 타입을 노출하지 않는다(POL-CQRS-004). */
data class MemberDetailView(
  val id: String,
  val name: String,
  val email: String,
  val address: String,
  val registeredDate: Instant,
  val createdAt: Instant,
  val createdBy: String,
  val groups: List<MembershipView>,
)
