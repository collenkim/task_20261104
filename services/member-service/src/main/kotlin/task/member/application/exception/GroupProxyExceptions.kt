package task.member.application.exception

/** 중계 실패의 의미만 전달한다. 원격 본문·주소·키와 전송 계층 타입은 보관하지 않는다. */
sealed class GroupProxyException(message: String) : RuntimeException(message)

class GroupResponseJsonException : GroupProxyException("그룹 JSON 형식 오류")

class GroupResponseContractException : GroupProxyException("그룹 응답 규격 위반")

class GroupResponseMediaException : GroupProxyException("그룹 응답 매체 유형 오류")

class GroupResponseSizeException : GroupProxyException("그룹 응답 크기 초과")

class GroupUpstreamFailureException : GroupProxyException("그룹 서버 오류")

class GroupServiceTimeoutException : GroupProxyException("그룹 응답 시간 초과")
