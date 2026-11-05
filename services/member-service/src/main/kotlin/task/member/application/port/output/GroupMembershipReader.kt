package task.member.application.port.output

import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.model.GroupMembership

/** 그룹 소속 조회 경계. 전송 기술·임시 데이터 선택을 호출자에게 노출하지 않는다(POL-CQRS-004). */
interface GroupMembershipReader {
  /**
   * 중복 없는 멤버 ID 1~100개에 대해 각 ID의 0~1개 소속을 반환한다(POL-MEMBER-001). 소속은 그룹 ID 순이며 누락된 ID나 호출 실패를 빈 소속으로
   * 대체하지 않는다.
   */
  fun findByMemberIds(memberIds: List<String>): Map<String, List<GroupMembership>>

  /** 단일 상세 조회. HTTP 구현은 상세 경로를 사용한다(POL-COMM-005). */
  fun findByMemberId(memberId: String): GroupMembership? {
    val groups =
      findByMemberIds(listOf(memberId))[memberId] ?: throw GroupServiceConfigurationException()
    if (groups.size > 1) throw GroupServiceConfigurationException()
    return groups.singleOrNull()
  }
}
