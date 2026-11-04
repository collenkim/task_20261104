package task.member.presentation.query

/** 오류 예제는 실제 고정 코드·문구 계약을 따른다. 값이나 내부 주소를 노출하지 않는다(POL-API-002·004). */
object MemberQueryExamples {
  const val INVALID_SUMMARY =
    """{"type":"about:blank","title":"입력값 검증 실패","status":400,"detail":"요청 값이 허용된 조건을 충족하지 않습니다.","instance":"/api/members","code":"VALIDATION_FAILED"}"""
  const val INVALID_DETAIL =
    """{"type":"about:blank","title":"입력값 검증 실패","status":400,"detail":"요청 값이 허용된 조건을 충족하지 않습니다.","instance":"/api/member/invalid","code":"VALIDATION_FAILED"}"""
  const val NOT_FOUND =
    """{"type":"about:blank","title":"대상 없음","status":404,"detail":"요청한 경로나 자원을 찾을 수 없습니다.","instance":"/api/member/missing001","code":"NOT_FOUND"}"""
  const val INTERNAL_ERROR =
    """{"type":"about:blank","title":"내부 처리 오류","status":500,"detail":"요청을 처리하지 못했습니다.","instance":"/api/members","code":"INTERNAL_ERROR"}"""
  const val GROUP_UNAVAILABLE =
    """{"type":"about:blank","title":"그룹 서비스 이용 불가","status":503,"detail":"그룹 정보를 일시적으로 조회할 수 없습니다.","instance":"/api/members","code":"GROUP_SERVICE_UNAVAILABLE"}"""
}
