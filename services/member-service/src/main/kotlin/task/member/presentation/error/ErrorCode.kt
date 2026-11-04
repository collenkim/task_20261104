package task.member.presentation.error

import org.springframework.http.HttpStatusCode

/** 오류 응답(ProblemDetail)의 `code` 값. 목록과 의미는 docs/common/api-conventions.md (POL-API-002). */
enum class ErrorCode(
  val code: String,
  val status: Int,
  val codeName: String,
  val description: String,
) {
  BAD_REQUEST("BAD_REQUEST", 400, "잘못된 요청", "요청 형식이 올바르지 않습니다."),
  VALIDATION_FAILED("VALIDATION_FAILED", 400, "입력값 검증 실패", "요청 값이 허용된 조건을 충족하지 않습니다."),
  UNAUTHORIZED("UNAUTHORIZED", 401, "인증 필요", "인증이 필요합니다."),
  FORBIDDEN("FORBIDDEN", 403, "접근 거부", "접근 권한이 없습니다."),
  NOT_FOUND("NOT_FOUND", 404, "대상 없음", "요청한 경로나 자원을 찾을 수 없습니다."),
  METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", 405, "허용되지 않은 요청 방법", "지원하지 않는 HTTP 메서드입니다."),
  NOT_ACCEPTABLE("NOT_ACCEPTABLE", 406, "응답 형식 불일치", "요청한 형식으로 응답할 수 없습니다."),
  CONFLICT("CONFLICT", 409, "상태 충돌", "현재 자원의 상태와 요청이 충돌합니다."),
  UNSUPPORTED_MEDIA_TYPE("UNSUPPORTED_MEDIA_TYPE", 415, "지원하지 않는 본문 형식", "요청 본문의 형식을 지원하지 않습니다."),
  INTERNAL_ERROR("INTERNAL_ERROR", 500, "내부 처리 오류", "요청을 처리하지 못했습니다."),
  GROUP_SERVICE_UNAVAILABLE(
    "GROUP_SERVICE_UNAVAILABLE",
    503,
    "그룹 서비스 이용 불가",
    "그룹 정보를 일시적으로 조회할 수 없습니다.",
  ),
  SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", 503, "서비스 이용 불가", "일시적으로 요청을 처리할 수 없습니다.");

  companion object {
    /** 상태 코드에 맞는 일반 코드. 목록에 없는 상태는 4xx면 BAD_REQUEST, 5xx면 INTERNAL_ERROR. */
    fun of(status: HttpStatusCode): ErrorCode =
      entries.firstOrNull {
        it.status == status.value() && it !in setOf(VALIDATION_FAILED, GROUP_SERVICE_UNAVAILABLE)
      } ?: if (status.is5xxServerError) INTERNAL_ERROR else BAD_REQUEST
  }
}
