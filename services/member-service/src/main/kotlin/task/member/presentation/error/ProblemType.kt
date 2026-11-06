package task.member.presentation.error

import java.net.URI

/**
 * 같은 `code` 안에서 소비자가 구분해야 하는 세부 원인(RFC 9457 `type`). 지정하지 않으면 `about:blank`다(POL-API-002).
 *
 * `code`는 고정 URN이며 [ErrorCode] 목록을 늘리지 않는다. `status`는 함께 쓰는 [ErrorCode]의 상태와 같다.
 */
enum class ProblemType(
  val code: String,
  val status: Int,
  val codeName: String,
  val description: String,
) {
  MEMBER_NOT_FOUND("urn:problem-type:member-not-found", 404, "멤버 없음", "요청한 멤버가 없습니다."),
  GROUP_SERVICE_UNAVAILABLE(
    "urn:problem-type:group-service-unavailable",
    503,
    "그룹 서비스 이용 불가",
    "그룹 정보를 일시적으로 조회할 수 없습니다.",
  ),
  GROUP_SERVICE_TIMEOUT(
    "urn:problem-type:group-service-timeout",
    504,
    "그룹 응답 시간 초과",
    "그룹 응답 대기 시간이 초과되었습니다.",
  ),
  GROUP_RESPONSE_INVALID_JSON(
    "urn:problem-type:group-response-invalid-json",
    502,
    "그룹 JSON 오류",
    "그룹 응답의 JSON 형식이 올바르지 않습니다.",
  ),
  GROUP_RESPONSE_CONTRACT_VIOLATION(
    "urn:problem-type:group-response-contract-violation",
    502,
    "그룹 응답 규격 오류",
    "그룹 응답이 정의된 규격을 충족하지 않습니다.",
  ),
  GROUP_RESPONSE_UNSUPPORTED_MEDIA_TYPE(
    "urn:problem-type:group-response-unsupported-media-type",
    502,
    "그룹 응답 형식 오류",
    "그룹 응답의 미디어 타입이 올바르지 않습니다.",
  ),
  GROUP_RESPONSE_TOO_LARGE(
    "urn:problem-type:group-response-too-large",
    502,
    "그룹 응답 크기 초과",
    "그룹 응답이 허용 크기를 초과했습니다.",
  ),
  GROUP_UPSTREAM_FAILURE(
    "urn:problem-type:group-upstream-failure",
    502,
    "그룹 서버 오류",
    "그룹 서버에서 정상 응답을 받지 못했습니다.",
  );

  val uri: URI
    get() = URI.create(code)

  val errorCode: ErrorCode
    get() = ErrorCode.entries.first { it.status == status }

  companion object {
    const val DEFAULT = "about:blank"
  }
}
