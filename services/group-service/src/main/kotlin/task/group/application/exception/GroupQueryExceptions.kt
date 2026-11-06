package task.group.application.exception

/** 파라미터 형식 위반(400): 누락·빈 값·ID 형식·콤마 결합. 원시 입력을 예외 메시지에 담지 않는다(POL-API-002). */
class InvalidGroupQueryException : RuntimeException("그룹 조회 파라미터 형식이 올바르지 않습니다.")

/** 형식은 맞지만 처리할 수 없는 조건(422): memberIds 100개 초과(POL-GROUP-002). */
class UnprocessableGroupQueryException : RuntimeException("그룹 조회 조건을 처리할 수 없습니다.")

/** 미소속과 잘못된 HTTP 경로를 구분한다(POL-GROUP-001). */
class GroupMembershipNotFoundException : RuntimeException("그룹 소속을 찾을 수 없습니다.")

/** 복수 행을 대표 그룹으로 임의 선택하지 않는다(POL-GROUP-001). */
class GroupMembershipAmbiguousException : RuntimeException("단일 소속 조건에 맞지 않습니다.")
