package task.group.presentation.error

import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.*
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import task.group.application.exception.*

/** 의미 있는 예외를 고정 오류 계약으로 매핑한다(POL-API-002, POL-GROUP-001). */
@RestControllerAdvice
class ProblemDetailsExceptionHandler : ResponseEntityExceptionHandler() {
  @ExceptionHandler(InvalidGroupQueryException::class)
  fun invalid(ex: InvalidGroupQueryException) = problem(ErrorCode.VALIDATION_FAILED, ex)

  @ExceptionHandler(GroupMembershipNotFoundException::class)
  fun missing(ex: GroupMembershipNotFoundException) =
    problem(ErrorCode.GROUP_MEMBERSHIP_NOT_FOUND, ex)

  @ExceptionHandler(GroupMembershipAmbiguousException::class)
  fun ambiguous(ex: GroupMembershipAmbiguousException) =
    problem(ErrorCode.GROUP_MEMBERSHIP_AMBIGUOUS, ex)

  @ExceptionHandler(
    DataAccessResourceFailureException::class,
    CannotCreateTransactionException::class,
  )
  fun unavailable(ex: Exception) = problem(ErrorCode.SERVICE_UNAVAILABLE, ex)

  @ExceptionHandler(Exception::class)
  fun unexpected(ex: Exception) = problem(ErrorCode.INTERNAL_ERROR, ex)

  override fun handleExceptionInternal(
    ex: Exception,
    body: Any?,
    headers: HttpHeaders,
    statusCode: HttpStatusCode,
    request: WebRequest,
  ): ResponseEntity<Any>? {
    val code =
      when (ex) {
        is MethodArgumentNotValidException,
        is HandlerMethodValidationException -> ErrorCode.VALIDATION_FAILED
        else -> ErrorCode.of(statusCode)
      }
    return super.handleExceptionInternal(ex, problem(code, ex), headers, statusCode, request)
  }

  private fun problem(code: ErrorCode, ex: Exception): ProblemDetail {
    SafeErrorLog.record(code, ex)
    return GroupProblemDetail(
      ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status), code.description)
        .apply {
          title = code.codeName
          setProperty(CODE, code.code)
        }
    )
  }

  companion object {
    const val CODE = "code"
  }
}
