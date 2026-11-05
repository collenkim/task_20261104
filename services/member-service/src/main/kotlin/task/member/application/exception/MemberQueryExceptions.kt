package task.member.application.exception

/** 요청 원문을 보관하지 않는 멤버 조회 입력 오류(POL-API-003). */
class InvalidMemberQueryException : RuntimeException("멤버 조회 조건이 올바르지 않습니다.")

/** 존재하지 않는 멤버의 조회 결과(POL-API-002). */
class MemberNotFoundException : RuntimeException("요청한 멤버가 없습니다.")
