package task.member.infrastructure.persistence.member

import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import task.member.application.model.MemberNameSort
import task.member.application.model.MemberPageRequest
import task.member.application.model.MemberRecord
import task.member.application.model.MemberSummaryRecord
import task.member.application.model.PageResult
import task.member.application.port.output.MemberReader

/** 읽기 트랜잭션 안에서 엔티티·투영을 값으로 변환하고 외부 호출 전에 반환한다(POL-CQRS-002). */
@Repository
class JpaMemberReader(private val repository: MemberJpaRepository) : MemberReader {
  private val log = LoggerFactory.getLogger(javaClass)

  @Transactional(readOnly = true)
  override fun findPage(
    page: MemberPageRequest,
    sort: MemberNameSort,
  ): PageResult<MemberSummaryRecord> {
    val start = System.nanoTime()
    log.info("event=query.started queryName=member.page page={} size={}", page.number, page.size)
    val direction =
      when (sort) {
        MemberNameSort.ASCENDING -> Sort.Direction.ASC
        MemberNameSort.DESCENDING -> Sort.Direction.DESC
      }
    val pageable =
      PageRequest.of(
        page.number,
        page.size,
        Sort.by(Sort.Order(direction, "name"), Sort.Order.asc("id")),
      )
    val result = repository.findSummaries(pageable)
    log.info(
      "event=query.completed queryName=member.page rows={} durationMs={}",
      result.content.size,
      (System.nanoTime() - start) / 1_000_000,
    )
    return PageResult(
      items = result.content.map { MemberSummaryRecord(it.id, it.name) },
      number = page.number,
      size = page.size,
      totalElements = result.totalElements,
    )
  }

  @Transactional(readOnly = true)
  override fun findById(id: String): MemberRecord? {
    val start = System.nanoTime()
    log.info("event=query.started queryName=member.detail")
    val result =
      repository.findById(id)?.let {
        MemberRecord(
          it.id,
          it.name,
          it.email,
          it.address,
          it.registeredAt,
          it.createdAt,
          it.createdBy,
        )
      }
    log.info(
      "event=query.completed queryName=member.detail found={} durationMs={}",
      result != null,
      (System.nanoTime() - start) / 1_000_000,
    )
    return result
  }
}
