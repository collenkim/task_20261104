package task.group.application.exception

/** 원시 입력이나 데이터 내용을 예외 메시지에 담지 않는다(POL-API-002). */
class InvalidGroupQueryException : RuntimeException("그룹 조회 조건이 올바르지 않습니다.")

/** 미소속과 잘못된 HTTP 경로를 구분한다(POL-GROUP-001). */
class GroupMembershipNotFoundException : RuntimeException("그룹 소속을 찾을 수 없습니다.")

/** 복수 행을 대표 그룹으로 임의 선택하지 않는다(POL-GROUP-001). */
class GroupMembershipAmbiguousException : RuntimeException("단일 소속 조건에 맞지 않습니다.")
