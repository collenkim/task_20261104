package task.group.presentation.error

import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import task.group.application.exception.GroupMembershipAmbiguousException
import task.group.application.exception.GroupMembershipNotFoundException
import task.group.application.exception.InvalidGroupQueryException
import task.group.application.exception.UnprocessableGroupQueryException

/** 의미 있는 예외를 고정 오류 계약으로 매핑한다(POL-API-002, POL-GROUP-001). */
@RestControllerAdvice
class ProblemDetailsExceptionHandler : ResponseEntityExceptionHandler() {
  @ExceptionHandler(InvalidGroupQueryException::class)
  fun invalid(ex: InvalidGroupQueryException) = problem(ErrorCode.INVALID_PARAMETER, ex)

  @ExceptionHandler(UnprocessableGroupQueryException::class)
  fun unprocessable(ex: UnprocessableGroupQueryException) = problem(ErrorCode.VALIDATION_FAILED, ex)

  @ExceptionHandler(GroupMembershipNotFoundException::class)
  fun missing(ex: GroupMembershipNotFoundException) =
    problem(ProblemType.GROUP_MEMBERSHIP_NOT_FOUND, ex)

  @ExceptionHandler(GroupMembershipAmbiguousException::class)
  fun ambiguous(ex: GroupMembershipAmbiguousException) =
    problem(ProblemType.GROUP_MEMBERSHIP_AMBIGUOUS, ex)

  @ExceptionHandler(
    DataAccessResourceFailureException::class,
    CannotCreateTransactionException::class,
  )
  fun unavailable(ex: Exception) = problem(ErrorCode.SERVICE_UNAVAILABLE, ex)

  @ExceptionHandler(Exception::class)
  fun unexpected(ex: Exception) = problem(ErrorCode.INTERNAL_SERVER_ERROR, ex)

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
    return super.handleExceptionInternal(
      ex,
      problem(code, ex),
      headers,
      HttpStatusCode.valueOf(code.status),
      request,
    )
  }

  private fun problem(code: ErrorCode, ex: Exception): ProblemDetail {
    SafeErrorLog.record(code, ex)
    return Problems.of(code)
  }

  private fun problem(type: ProblemType, ex: Exception): ProblemDetail {
    SafeErrorLog.record(type.errorCode, ex, type = type)
    return Problems.of(type)
  }

  companion object {
    const val CODE = Problems.CODE
  }
}
