package task.member.application.exception

/** 요청 파라미터의 형식 위반(400). 요청 원문을 보관하지 않는다(POL-API-003). */
class InvalidMemberQueryException : RuntimeException("멤버 조회 파라미터 형식이 올바르지 않습니다.")

/** 형식은 맞지만 허용 범위·허용 값을 벗어난 조회 조건(422). 요청 원문을 보관하지 않는다(POL-API-003). */
class UnprocessableMemberQueryException : RuntimeException("멤버 조회 조건을 처리할 수 없습니다.")

/** 존재하지 않는 멤버의 조회 결과(POL-API-002). */
class MemberNotFoundException : RuntimeException("요청한 멤버가 없습니다.")
