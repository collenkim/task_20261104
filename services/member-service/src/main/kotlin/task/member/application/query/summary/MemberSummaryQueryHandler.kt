package task.member.application.query.summary

import org.springframework.stereotype.Service
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.model.PageResult
import task.member.application.port.input.GetMemberSummariesUseCase
import task.member.application.port.output.GroupMembershipReader
import task.member.application.port.output.MemberReader
import task.member.application.query.MembershipView

/** DB 읽기 종료 후 페이지의 ID를 한 번에 조회한다. 원격 호출을 트랜잭션으로 감싸지 않는다(POL-CQRS-004). */
@Service
class MemberSummaryQueryHandler(
  private val members: MemberReader,
  private val memberships: GroupMembershipReader,
) : GetMemberSummariesUseCase {
  override fun execute(query: MemberSummaryQuery): PageResult<MemberSummaryView> {
    val page = members.findPage(query.page, query.sort)
    val groups =
      if (page.items.isEmpty()) emptyMap()
      else memberships.findByMemberIds(page.items.map { it.id })
    return PageResult(
      page.items.map { member ->
        val memberGroups = groups[member.id] ?: throw GroupServiceConfigurationException()
        MemberSummaryView(
          member.id,
          member.name,
          memberGroups.sortedBy { it.groupId }.map(MembershipView::from),
        )
      },
      page.number,
      page.size,
      page.totalElements,
    )
  }
}
