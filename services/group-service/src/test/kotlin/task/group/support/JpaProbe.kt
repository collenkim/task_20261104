package task.group.support

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.springframework.data.jpa.repository.JpaRepository

/** JPA 매핑·생성자·문자셋·시각 왕복 검증용 엔티티. 운영 산출물에는 포함하지 않는다. */
@Entity
@Table(name = "jpa_probe")
class JpaProbe(
  @Column(nullable = false) val label: String,
  @Column(name = "observed_at", nullable = false, columnDefinition = "datetime(6)")
  val observedAt: Instant,
) {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
}

/** 서비스별 Spring Data JPA 저장소 구동 검증용. */
interface JpaProbeRepository : JpaRepository<JpaProbe, Long>
