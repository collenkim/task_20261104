package task.group.presentation.error

import org.springframework.http.HttpStatusCode

/** 오류 응답(ProblemDetail)의 `code` 값. 목록과 의미는 docs/common/api-conventions.md (POL-API-002). */
enum class ErrorCode(val status: Int) {
  BAD_REQUEST(400),
  VALIDATION_FAILED(400),
  UNAUTHORIZED(401),
  FORBIDDEN(403),
  NOT_FOUND(404),
  METHOD_NOT_ALLOWED(405),
  NOT_ACCEPTABLE(406),
  CONFLICT(409),
  UNSUPPORTED_MEDIA_TYPE(415),
  INTERNAL_ERROR(500),
  SERVICE_UNAVAILABLE(503);

  companion object {
    /** 상태 코드에 맞는 일반 코드. 목록에 없는 상태는 4xx면 BAD_REQUEST, 5xx면 INTERNAL_ERROR. */
    fun of(status: HttpStatusCode): ErrorCode =
      entries.firstOrNull { it.status == status.value() && it != VALIDATION_FAILED }
        ?: if (status.is5xxServerError) INTERNAL_ERROR else BAD_REQUEST
  }
}
