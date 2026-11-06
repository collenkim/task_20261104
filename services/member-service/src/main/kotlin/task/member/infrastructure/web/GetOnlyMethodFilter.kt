package task.member.infrastructure.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.filter.OncePerRequestFilter
import task.member.presentation.error.ErrorCode
import task.member.presentation.error.ProblemResponseWriter

/**
 * 제공하는 API는 모두 GET이다. HEAD·OPTIONS·POST·PUT·PATCH·DELETE·TRACE 등은 인증·핸들러 실행 전에 405로
 * 막는다(POL-API-008).
 *
 * Spring MVC의 암묵 HEAD·OPTIONS 처리가 핸들러와 원격 호출을 실행하지 않도록 가장 앞쪽(요청 추적 다음)에서 판단한다.
 */
class GetOnlyMethodFilter(private val writer: ProblemResponseWriter) : OncePerRequestFilter() {
  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    chain: FilterChain,
  ) {
    if (request.method == ALLOWED) {
      chain.doFilter(request, response)
      return
    }
    response.setHeader(HttpHeaders.ALLOW, ALLOWED)
    writer.write(request, response, ErrorCode.METHOD_NOT_ALLOWED)
  }

  /** 오류 디스패치도 같은 규칙을 적용한다. */
  override fun shouldNotFilterErrorDispatch() = false

  companion object {
    const val ALLOWED = "GET"
  }
}
