package task.group.presentation.error

import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/** MVC 오류 본문 구조는 보존하고 레거시 소비자를 위한 JSON 미디어 타입을 고정한다(POL-API-002). */
@RestControllerAdvice
class JsonProblemResponseAdvice : ResponseBodyAdvice<Any> {
  override fun supports(
    returnType: MethodParameter,
    converterType: Class<out HttpMessageConverter<*>>,
  ) = true

  override fun beforeBodyWrite(
    body: Any?,
    returnType: MethodParameter,
    selectedContentType: MediaType,
    selectedConverterType: Class<out HttpMessageConverter<*>>,
    request: ServerHttpRequest,
    response: ServerHttpResponse,
  ): Any? {
    if (body is ProblemDetail) response.headers.contentType = MediaType.APPLICATION_JSON
    return body
  }
}
