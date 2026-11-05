package task.member.application.query.detail

import org.springframework.stereotype.Service
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.exception.MemberNotFoundException
import task.member.application.port.input.GetMemberDetailUseCase
import task.member.application.port.output.GroupMembershipReader
import task.member.application.port.output.MemberReader
import task.member.application.query.MembershipView

/** 멤버가 있을 때만 소속을 조회하며 저장소 트랜잭션 밖에서 결합한다(POL-CQRS-004). */
@Service
class MemberDetailQueryHandler(
  private val members: MemberReader,
  private val memberships: GroupMembershipReader,
) : GetMemberDetailUseCase {
  override fun execute(query: MemberDetailQuery): MemberDetailView {
    val member = members.findById(query.id) ?: throw MemberNotFoundException()
    val groups =
      memberships.findByMemberIds(listOf(member.id))[member.id]
        ?: throw GroupServiceConfigurationException()
    return MemberDetailView(
      member.id,
      member.name,
      member.email,
      member.address,
      member.registeredAt,
      member.createdAt,
      member.createdBy,
      groups.sortedBy { it.groupId }.map(MembershipView::from),
    )
  }
}
