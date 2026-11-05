package task.group.infrastructure.persistence.group

import jakarta.persistence.EntityManager
import jakarta.persistence.Tuple
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import task.group.application.model.GroupMembershipRecord
import task.group.application.model.GroupReadSnapshot
import task.group.application.port.output.GroupSummaryReader
import task.group.application.port.output.MemberGroupReader

/** 두 조회를 같은 일관 읽기 스냅샷에서 수행한다(POL-GROUP-002). */
@Repository
class JpaGroupReader(private val entityManager: EntityManager) :
  GroupSummaryReader, MemberGroupReader {
  private val log = LoggerFactory.getLogger(javaClass)

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  override fun findSummaries(memberIds: List<String>): GroupReadSnapshot {
    val start = System.nanoTime()
    log.info("event=query.started queryName=group.summary requested={}", memberIds.size)
    val rows =
      entityManager
        .createQuery(SELECT + " where m.memberId in :ids", GroupMembershipRecord::class.java)
        .setParameter("ids", memberIds)
        .resultList
    val counts =
      if (rows.isEmpty()) emptyMap()
      else
        entityManager
          .createQuery(
            "select m.groupId as groupId, count(m) as total from GroupMembershipEntity m where m.groupId in :ids group by m.groupId",
            Tuple::class.java,
          )
          .setParameter("ids", rows.map { it.groupId }.distinct())
          .resultList
          .associate {
            it.get("groupId", String::class.java) to
              it.get("total", Long::class.javaObjectType).toLong()
          }
    log.info(
      "event=query.completed queryName=group.summary rows={} durationMs={}",
      rows.size,
      (System.nanoTime() - start) / 1_000_000,
    )
    return GroupReadSnapshot(rows, counts)
  }

  @Transactional(readOnly = true)
  override fun findByMemberId(memberId: String): List<GroupMembershipRecord> {
    val start = System.nanoTime()
    log.info("event=query.started queryName=group.detail")
    // 2행이면 이미 단일 소속 위반이다. 대표 행 선택이 아닌 다건 탐지용 제한이다.
    val rows =
      entityManager
        .createQuery(SELECT + " where m.memberId = :id", GroupMembershipRecord::class.java)
        .setParameter("id", memberId)
        .setMaxResults(2)
        .resultList
    log.info(
      "event=query.completed queryName=group.detail rows={} durationMs={}",
      rows.size,
      (System.nanoTime() - start) / 1_000_000,
    )
    return rows
  }

  private companion object {
    const val SELECT =
      "select new task.group.application.model.GroupMembershipRecord(m.memberId, g.id, g.name, m.joinedAt, g.pricing, m.permission) from GroupMembershipEntity m join GroupEntity g on g.id = m.groupId"
  }
}
