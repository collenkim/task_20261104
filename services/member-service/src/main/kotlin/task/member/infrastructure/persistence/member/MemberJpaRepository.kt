package task.member.infrastructure.persistence.member

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

/** 쓰기 메서드를 노출하지 않는 저장 기술 내부 저장소(POL-CQRS-002). */
interface MemberJpaRepository : Repository<MemberEntity, String> {
  /** 목록에서는 ID·이름 열만 조회한다. 전체 건수는 소속 관계와 무관하다(POL-API-003). */
  @Query(
    value = "select m.id as id, m.name as name from MemberEntity m",
    countQuery = "select count(m) from MemberEntity m",
  )
  fun findSummaries(pageable: Pageable): Page<MemberSummaryProjection>

  /** 영속 엔티티는 저장소 어댑터 밖으로 반환하지 않는다(POL-CQRS-004). */
  fun findById(id: String): MemberEntity?
}

/** Spring Data 투영은 저장소 내부에서만 사용한다(POL-CQRS-004). */
interface MemberSummaryProjection {
  val id: String
  val name: String
}
