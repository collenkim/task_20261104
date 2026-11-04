package task.member.application.exception

/** 그룹의 연결·시간 초과·서버 오류를 알린다. 내부 주소·응답·키를 포함하지 않는다(POL-COMM-004). */
class GroupServiceUnavailableException : RuntimeException("그룹 서비스 이용 불가")

/** 내부 키 거부 또는 응답 계약 위반이다. 원문·주소·키를 예외에 담지 않는다(POL-COMM-004). */
class GroupServiceConfigurationException : RuntimeException("그룹 호출 설정 또는 응답 계약 오류")
