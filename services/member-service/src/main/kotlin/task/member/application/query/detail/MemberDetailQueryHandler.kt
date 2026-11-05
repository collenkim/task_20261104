package task.member.application.query.detail

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import task.member.application.exception.MemberNotFoundException
import task.member.application.port.input.GetMemberDetailUseCase
import task.member.application.port.output.GroupMembershipReader
import task.member.application.port.output.MemberReader
import task.member.application.query.MembershipView

/** DB 트랜잭션이 종료된 뒤 단일 그룹 상세를 조회한다(POL-CQRS-004). */
@Service
class MemberDetailQueryHandler(
  private val members: MemberReader,
  private val memberships: GroupMembershipReader,
) : GetMemberDetailUseCase {
  private val log = LoggerFactory.getLogger(javaClass)

  override fun execute(query: MemberDetailQuery): MemberDetailView {
    log.info("event=business.started operation=member.detail")
    val member = members.findById(query.id) ?: throw MemberNotFoundException()
    log.info("event=business.progress operation=member.detail stage=membership.lookup")
    val group = memberships.findByMemberId(member.id)
    val result =
      MemberDetailView(
        member.id,
        member.name,
        member.email,
        member.address,
        member.registeredAt,
        member.createdAt,
        member.createdBy,
        listOfNotNull(group).map(MembershipView::from),
      )
    log.info("event=business.completed operation=member.detail groups={}", result.groups.size)
    return result
  }
}
