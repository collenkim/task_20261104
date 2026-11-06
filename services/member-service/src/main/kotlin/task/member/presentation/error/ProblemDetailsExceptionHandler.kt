package task.member.presentation.error

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import task.member.application.exception.GroupProxyException
import task.member.application.exception.GroupResponseContractException
import task.member.application.exception.GroupResponseJsonException
import task.member.application.exception.GroupResponseMediaException
import task.member.application.exception.GroupResponseSizeException
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.exception.GroupServiceTimeoutException
import task.member.application.exception.GroupServiceUnavailableException
import task.member.application.exception.GroupUpstreamFailureException
import task.member.application.exception.InvalidMemberQueryException
import task.member.application.exception.MemberNotFoundException
import task.member.application.exception.UnprocessableMemberQueryException

/**
 * 모든 오류 응답을 RFC 9457 ProblemDetail + `code`로 만든다(POL-API-002).
 *
 * Spring MVC 표준 예외는 상태 코드에 맞는 `code`를 붙인다. 처리되지 않은 예외는 내부 정보를 응답에 넣지 않고 INTERNAL_SERVER_ERROR로 바꾼다.
 */
@RestControllerAdvice
class ProblemDetailsExceptionHandler : ResponseEntityExceptionHandler() {
  @ExceptionHandler(InvalidMemberQueryException::class)
  fun handleInvalidMemberQuery(): ProblemDetail = problem(ErrorCode.INVALID_PARAMETER)

  @ExceptionHandler(UnprocessableMemberQueryException::class)
  fun handleUnprocessableMemberQuery(): ProblemDetail = problem(ErrorCode.VALIDATION_FAILED)

  @ExceptionHandler(MemberNotFoundException::class)
  fun handleMemberNotFound(): ProblemDetail = problem(ProblemType.MEMBER_NOT_FOUND)

  @ExceptionHandler(GroupServiceUnavailableException::class)
  fun handleGroupUnavailable(): ProblemDetail = problem(ProblemType.GROUP_SERVICE_UNAVAILABLE)

  @ExceptionHandler(GroupProxyException::class)
  fun handleGroupProxy(ex: GroupProxyException): ProblemDetail =
    problem(
      when (ex) {
        is GroupResponseJsonException -> ProblemType.GROUP_RESPONSE_INVALID_JSON
        is GroupResponseContractException -> ProblemType.GROUP_RESPONSE_CONTRACT_VIOLATION
        is GroupResponseMediaException -> ProblemType.GROUP_RESPONSE_UNSUPPORTED_MEDIA_TYPE
        is GroupResponseSizeException -> ProblemType.GROUP_RESPONSE_TOO_LARGE
        is GroupUpstreamFailureException -> ProblemType.GROUP_UPSTREAM_FAILURE
        is GroupServiceTimeoutException -> ProblemType.GROUP_SERVICE_TIMEOUT
      }
    )

  @ExceptionHandler(GroupServiceConfigurationException::class)
  fun handleGroupConfiguration(): ProblemDetail = problem(ErrorCode.INTERNAL_SERVER_ERROR)

  @ExceptionHandler(Exception::class)
  fun handleUnexpected(ex: Exception): ProblemDetail {
    SafeErrorLog.record(ErrorCode.INTERNAL_SERVER_ERROR, ex)
    return Problems.of(ErrorCode.INTERNAL_SERVER_ERROR)
  }

  override fun handleExceptionInternal(
    ex: Exception,
    body: Any?,
    headers: HttpHeaders,
    statusCode: HttpStatusCode,
    request: WebRequest,
  ): ResponseEntity<Any>? {
    // 본문 값 검증 실패는 형식이 아니라 의미 위반이므로 422로 응답한다.
    val code =
      when (ex) {
        is MethodArgumentNotValidException,
        is HandlerMethodValidationException -> ErrorCode.VALIDATION_FAILED
        else -> ErrorCode.of(statusCode)
      }
    SafeErrorLog.record(code, ex)
    return super.handleExceptionInternal(
      ex,
      Problems.of(code),
      headers,
      HttpStatusCode.valueOf(code.status),
      request,
    )
  }

  private fun problem(code: ErrorCode): ProblemDetail {
    SafeErrorLog.record(code)
    return Problems.of(code)
  }

  private fun problem(type: ProblemType): ProblemDetail {
    SafeErrorLog.record(type.errorCode, type = type)
    return Problems.of(type)
  }

  companion object {
    const val CODE = Problems.CODE
  }
}
