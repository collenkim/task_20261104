package task.member.application.port.output

import task.member.application.model.MemberNameSort
import task.member.application.model.MemberPageRequest
import task.member.application.model.MemberRecord
import task.member.application.model.MemberSummaryRecord
import task.member.application.model.PageResult

/** 멤버 저장소 조회 경계. 구현은 트랜잭션 안에서 모든 값을 읽어 반환한다(POL-CQRS-002·004). */
interface MemberReader {
  /** DB 이름 비교 규칙과 ID 오름차순 보조 정렬로 페이지를 조회한다(POL-API-003). */
  fun findPage(page: MemberPageRequest, sort: MemberNameSort): PageResult<MemberSummaryRecord>

  /** 식별자에 해당하는 멤버만 읽고, 존재하지 않으면 null을 반환한다(POL-CQRS-002). */
  fun findById(id: String): MemberRecord?
}
