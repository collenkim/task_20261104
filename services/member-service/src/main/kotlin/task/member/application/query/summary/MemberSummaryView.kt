package task.member.application.query.summary

import task.member.application.query.MembershipView

/** 멤버 한 명과 여러 소속을 결합한 조회 값. 감사 정보는 읽지 않는다(POL-CQRS-002). */
data class MemberSummaryView(val id: String, val name: String, val groups: List<MembershipView>)
