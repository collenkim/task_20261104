package task.group.application.port.output

import task.group.application.model.GroupMembershipRecord
import task.group.application.model.GroupReadSnapshot

/** 요청 멤버의 소속을 한 읽기 트랜잭션에서 조회한다. 인원은 반환 행에서 계산한다(POL-GROUP-002). */
fun interface GroupSummaryReader {
  fun findSummaries(memberIds: List<String>): GroupReadSnapshot
}

/** 다건 여부를 숨기지 않는 단일 멤버 소속 조회 경계(POL-GROUP-001). */
fun interface MemberGroupReader {
  fun findByMemberId(memberId: String): List<GroupMembershipRecord>
}
