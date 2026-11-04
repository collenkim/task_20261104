package task.member.infrastructure.configuration

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import task.member.presentation.error.ErrorCode
import tools.jackson.databind.json.JsonMapper

/**
 * 401·403 응답을 ProblemDetail + `code`로 쓴다(POL-API-002, POL-SEC-003).
 *
 * 상태 코드와 `WWW-Authenticate` 헤더(RFC 6750)는 Spring Security 기본 처리기에 맡기고 본문만 채운다. 토큰 값이나 검증 실패의 내부 사유는
 * 본문에 넣지 않는다.
 */
class ProblemDetailSecurityHandler(private val jsonMapper: JsonMapper) :
  AuthenticationEntryPoint, AccessDeniedHandler {
  private val bearerEntryPoint = BearerTokenAuthenticationEntryPoint()
  private val bearerAccessDenied = BearerTokenAccessDeniedHandler()

  override fun commence(
    request: HttpServletRequest,
    response: HttpServletResponse,
    authException: AuthenticationException,
  ) {
    bearerEntryPoint.commence(request, response, authException)
    write(request, response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "인증이 필요합니다.")
  }

  override fun handle(
    request: HttpServletRequest,
    response: HttpServletResponse,
    accessDeniedException: AccessDeniedException,
  ) {
    bearerAccessDenied.handle(request, response, accessDeniedException)
    write(request, response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "접근 권한이 없습니다.")
  }

  private fun write(
    request: HttpServletRequest,
    response: HttpServletResponse,
    status: HttpStatus,
    code: ErrorCode,
    detail: String,
  ) {
    response.status = status.value()
    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    response.characterEncoding = Charsets.UTF_8.name()
    val body =
      linkedMapOf(
        "type" to "about:blank",
        "title" to status.reasonPhrase,
        "status" to status.value(),
        "detail" to detail,
        "instance" to request.requestURI,
        "code" to code.name,
      )
    jsonMapper.writeValue(response.outputStream, body)
  }
}
