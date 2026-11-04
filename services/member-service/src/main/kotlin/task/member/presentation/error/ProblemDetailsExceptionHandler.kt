package task.member.presentation.error

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * 모든 오류 응답을 RFC 9457 ProblemDetail + `code`로 만든다(POL-API-002).
 *
 * Spring MVC 표준 예외는 상위 클래스가 ProblemDetail을 만들고, 여기서 `code`를 붙인다. 처리되지 않은 예외는 내부 정보를 응답에 넣지 않고
 * INTERNAL_ERROR로 바꾼다.
 */
@RestControllerAdvice
class ProblemDetailsExceptionHandler : ResponseEntityExceptionHandler() {
  private val log = LoggerFactory.getLogger(javaClass)

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
      problem.setProperty(CODE, code.name)
    }
    return response
  }

  /** 보안 예외는 Spring Security(인증 진입점·접근 거부 처리기)가 401·403 ProblemDetail로 만들도록 그대로 넘긴다. */
  @ExceptionHandler(AccessDeniedException::class, AuthenticationException::class)
  fun rethrowSecurity(ex: RuntimeException): Nothing = throw ex

  @ExceptionHandler(Exception::class)
  fun handleUnexpected(ex: Exception): ProblemDetail {
    log.error("처리되지 않은 오류", ex)
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "요청을 처리하지 못했습니다.",
      )
      .apply { setProperty(CODE, ErrorCode.INTERNAL_ERROR.name) }
  }

  companion object {
    const val CODE = "code"
  }
}
