package task.group.presentation.error

import java.net.URI

/**
 * 같은 `code` 안에서 소비자가 구분해야 하는 세부 원인(RFC 9457 `type`). 지정하지 않으면 `about:blank`다(POL-API-002).
 *
 * 멤버 서비스는 [GROUP_MEMBERSHIP_NOT_FOUND]로 정상 미소속 404와 경로 오류 404를 구분한다.
 */
enum class ProblemType(
  val code: String,
  val status: Int,
  val codeName: String,
  val description: String,
) {
  GROUP_MEMBERSHIP_NOT_FOUND(
    "urn:problem-type:group-membership-not-found",
    404,
    "그룹 소속 없음",
    "그룹 소속을 찾을 수 없습니다.",
  ),
  GROUP_MEMBERSHIP_AMBIGUOUS(
    "urn:problem-type:group-membership-ambiguous",
    500,
    "소속 정합성 오류",
    "단일 그룹 소속 조건에 맞지 않습니다.",
  ),
  INTERNAL_ACCESS_DENIED(
    "urn:problem-type:internal-access-denied",
    403,
    "내부 호출 거부",
    "내부 서비스 호출 키를 확인할 수 없습니다.",
  );

  val uri: URI
    get() = URI.create(code)

  val errorCode: ErrorCode
    get() = ErrorCode.entries.first { it.status == status }

  companion object {
    const val DEFAULT = "about:blank"
  }
}
