package task.member.infrastructure.persistence.member

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

/** 멤버 소유 테이블 매핑. 외부 소속 관계를 보유하지 않고 ID로 동등성을 판정한다(POL-CODE-003). */
@Entity
@Table(name = "member")
class MemberEntity(
  @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(nullable = false, length = 10) val id: String,
  @Column(nullable = false, length = 100) val name: String,
  @Column(nullable = false, length = 254) val email: String,
  @Column(nullable = false, length = 255) val address: String,
  @Column(name = "registered_at", nullable = false, columnDefinition = "timestamp(6)")
  val registeredAt: Instant,
  @Column(name = "created_at", nullable = false, columnDefinition = "timestamp(6)")
  val createdAt: Instant,
  @Column(name = "created_by", nullable = false, length = 50) val createdBy: String,
) {
  final override fun equals(other: Any?): Boolean =
    this === other || (other is MemberEntity && id == other.id)

  final override fun hashCode(): Int = id.hashCode()
}
