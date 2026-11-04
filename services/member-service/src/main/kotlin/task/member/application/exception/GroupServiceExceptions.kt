package task.member.application.exception

/** 그룹의 연결·시간 초과·서버 오류를 알린다. 내부 주소·응답·키를 포함하지 않는다(POL-COMM-004). */
class GroupServiceUnavailableException : RuntimeException("그룹 서비스 이용 불가")

/** 내부 키 거부 또는 계약과 다른 명세 응답이다. 사용자에게 상세를 전달하지 않는다(POL-COMM-004). */
class GroupServiceConfigurationException : RuntimeException("그룹 호출 설정 또는 명세 오류")
