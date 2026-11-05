package task.group.application.query.summary

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import task.group.application.exception.GroupMembershipAmbiguousException
import task.group.application.model.GroupSummaryView
import task.group.application.port.input.GetGroupSummariesUseCase
import task.group.application.port.output.GroupSummaryReader

/** 트랜잭션 밖에서 그룹을 묶고 정렬한다(POL-GROUP-002, POL-CQRS-002). */
@Service
class GroupSummaryQueryHandler(private val reader: GroupSummaryReader) : GetGroupSummariesUseCase {
  private val log = LoggerFactory.getLogger(javaClass)

  override fun execute(query: GroupSummaryQuery): List<GroupSummaryView> {
    log.info("event=business.started operation=group.summary requested={}", query.memberIds.size)
    val snapshot = reader.findSummaries(query.memberIds)
    if (snapshot.memberships.map { it.memberId }.distinct().size != snapshot.memberships.size) {
      throw GroupMembershipAmbiguousException()
    }
    val result =
      snapshot.memberships
        .groupBy { it.groupId }
        .toSortedMap()
        .map { (id, members) ->
          GroupSummaryView(
            id,
            members.first().groupName,
            snapshot.counts.getValue(id),
            members.sortedBy { it.memberId },
          )
        }
    log.info(
      "event=business.completed operation=group.summary groups={} members={}",
      result.size,
      snapshot.memberships.size,
    )
    return result
  }
}
