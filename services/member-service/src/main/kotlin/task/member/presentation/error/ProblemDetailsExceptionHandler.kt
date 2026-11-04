package task.member.presentation.error

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import task.member.application.exception.GroupServiceConfigurationException
import task.member.application.exception.GroupServiceUnavailableException
import task.member.application.exception.InvalidMemberQueryException
import task.member.application.exception.MemberNotFoundException

/**
 * 모든 오류 응답을 RFC 9457 ProblemDetail + `code`로 만든다(POL-API-002).
 *
 * Spring MVC 표준 예외는 상위 클래스가 ProblemDetail을 만들고, 여기서 `code`를 붙인다. 처리되지 않은 예외는 내부 정보를 응답에 넣지 않고
 * INTERNAL_ERROR로 바꾼다.
 */
@RestControllerAdvice
class ProblemDetailsExceptionHandler : ResponseEntityExceptionHandler() {
  private val log = LoggerFactory.getLogger(javaClass)

  @ExceptionHandler(InvalidMemberQueryException::class)
  fun handleInvalidMemberQuery(): ProblemDetail = memberProblem(ErrorCode.VALIDATION_FAILED)

  @ExceptionHandler(MemberNotFoundException::class)
  fun handleMemberNotFound(): ProblemDetail = memberProblem(ErrorCode.NOT_FOUND)

  private fun memberProblem(code: ErrorCode): ProblemDetail =
    ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status), code.description).apply {
      title = code.codeName
      setProperty(CODE, code.code)
    }

  override fun handleExceptionInternal(
    ex: Exception,
    body: Any?,
    headers: HttpHeaders,
    statusCode: HttpStatusCode,
    request: WebRequest,
  ): ResponseEntity<Any>? {
    val response = super.handleExceptionInternal(ex, body, headers, statusCode, request)
    val problem = response?.body as? ProblemDetail
    if (problem != null && problem.properties?.containsKey(CODE) != true) {
      val code =
        when (ex) {
          is MethodArgumentNotValidException,
          is HandlerMethodValidationException -> ErrorCode.VALIDATION_FAILED
          else -> ErrorCode.of(statusCode)
        }
      problem.setProperty(CODE, code.code)
      problem.title = code.codeName
      if (problem.detail == null) problem.detail = code.description
    }
    return response
  }

  @ExceptionHandler(GroupServiceUnavailableException::class)
  fun handleGroupUnavailable(): ProblemDetail = groupProblem(ErrorCode.GROUP_SERVICE_UNAVAILABLE)

  @ExceptionHandler(GroupServiceConfigurationException::class)
  fun handleGroupConfiguration(): ProblemDetail = groupProblem(ErrorCode.INTERNAL_ERROR)

  private fun groupProblem(code: ErrorCode): ProblemDetail {
    log.warn("그룹 호출 실패: {}", code.code)
    return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(code.status), code.description)
      .apply {
        title = code.codeName
        setProperty(CODE, code.code)
      }
  }

  @ExceptionHandler(Exception::class)
  fun handleUnexpected(ex: Exception): ProblemDetail {
    log.error("처리되지 않은 오류", ex)
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ErrorCode.INTERNAL_ERROR.description,
      )
      .apply {
        title = ErrorCode.INTERNAL_ERROR.codeName
        setProperty(CODE, ErrorCode.INTERNAL_ERROR.code)
      }
  }

  companion object {
    const val CODE = "code"
  }
}
