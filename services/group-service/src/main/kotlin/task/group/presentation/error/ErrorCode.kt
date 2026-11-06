package task.group.presentation.error

import org.springframework.http.HttpStatusCode

/**
 * 오류 응답(ProblemDetail)의 `code` 값. 상태 코드마다 하나이며 두 서비스가 같은 목록을 쓴다(POL-API-002).
 *
 * 같은 상태 안의 세부 원인은 `code`를 늘리지 않고 [ProblemType]의 `type`으로 구분한다.
 */
enum class ErrorCode(
  val code: String,
  val status: Int,
  val codeName: String,
  val description: String,
) {
  INVALID_PARAMETER("INVALID_PARAMETER", 400, "잘못된 요청", "요청 파라미터의 형식이 올바르지 않습니다."),
  UNAUTHORIZED("UNAUTHORIZED", 401, "인증 실패", "유효한 인증 정보가 필요합니다."),
  FORBIDDEN("FORBIDDEN", 403, "접근 권한 없음", "요청한 자원에 접근할 권한이 없습니다."),
  RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", 404, "리소스 없음", "요청한 리소스를 찾을 수 없습니다."),
  METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", 405, "지원하지 않는 Method", "GET 요청만 지원합니다."),
  NOT_ACCEPTABLE("NOT_ACCEPTABLE", 406, "지원하지 않는 응답 형식", "요청한 형식으로 응답할 수 없습니다."),
  RESOURCE_CONFLICT("RESOURCE_CONFLICT", 409, "리소스 충돌", "현재 리소스의 상태와 요청이 충돌합니다."),
  UNSUPPORTED_MEDIA_TYPE(
    "UNSUPPORTED_MEDIA_TYPE",
    415,
    "지원하지 않는 미디어 타입",
    "요청의 미디어 타입을 지원하지 않습니다.",
  ),
  VALIDATION_FAILED(
    "VALIDATION_FAILED",
    422,
    "처리할 수 없는 요청 값",
    "요청 데이터의 형식은 유효하지만 의미상 처리할 수 없습니다.",
  ),
  TOO_MANY_REQUESTS("TOO_MANY_REQUESTS", 429, "요청 횟수 제한 초과", "허용된 요청 횟수를 초과했습니다. 잠시 후 다시 시도하세요."),
  INTERNAL_SERVER_ERROR("INTERNAL_SERVER_ERROR", 500, "서버 내부 오류", "요청을 처리하지 못했습니다."),
  BAD_GATEWAY("BAD_GATEWAY", 502, "게이트웨이 응답 오류", "연동 서비스에서 올바른 응답을 받지 못했습니다."),
  SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", 503, "서비스 이용 불가", "일시적으로 요청을 처리할 수 없습니다."),
  GATEWAY_TIMEOUT("GATEWAY_TIMEOUT", 504, "게이트웨이 응답 시간 초과", "연동 서비스의 응답 시간이 초과되었습니다.");

  companion object {
    private val byStatus = entries.associateBy { it.status }

    /** 상태 코드에 맞는 코드. 목록에 없는 상태는 4xx면 INVALID_PARAMETER, 그 밖은 INTERNAL_SERVER_ERROR. */
    fun of(status: HttpStatusCode): ErrorCode =
      byStatus[status.value()]
        ?: if (status.is4xxClientError) INVALID_PARAMETER else INTERNAL_SERVER_ERROR
  }
}
