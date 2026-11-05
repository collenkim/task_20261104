package task.group.application.query.detail

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import task.group.application.exception.GroupMembershipAmbiguousException
import task.group.application.exception.GroupMembershipNotFoundException
import task.group.application.model.GroupMembershipRecord
import task.group.application.port.input.GetMemberGroupDetailUseCase
import task.group.application.port.output.MemberGroupReader

/** DB 제약과 별도로 다건 조회를 오류로 처리한다(POL-GROUP-001). */
@Service
class MemberGroupDetailQueryHandler(private val reader: MemberGroupReader) :
  GetMemberGroupDetailUseCase {
  private val log = LoggerFactory.getLogger(javaClass)

  override fun execute(query: MemberGroupDetailQuery): GroupMembershipRecord {
    log.info("event=business.started operation=group.detail")
    val rows = reader.findByMemberId(query.memberId)
    if (rows.size > 1) throw GroupMembershipAmbiguousException()
    val result = rows.singleOrNull() ?: throw GroupMembershipNotFoundException()
    log.info("event=business.completed operation=group.detail found=true")
    return result
  }
}
