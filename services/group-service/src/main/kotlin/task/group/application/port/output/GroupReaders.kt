package task.group.application.port.output

import task.group.application.model.GroupMembershipRecord
import task.group.application.model.GroupReadSnapshot

/** 여러 멤버의 소속과 그룹 인원을 한 읽기 트랜잭션에서 조회한다(POL-GROUP-002). */
fun interface GroupSummaryReader {
  fun findSummaries(memberIds: List<String>): GroupReadSnapshot
}

/** 다건 여부를 숨기지 않는 단일 멤버 소속 조회 경계(POL-GROUP-001). */
fun interface MemberGroupReader {
  fun findByMemberId(memberId: String): List<GroupMembershipRecord>
}
