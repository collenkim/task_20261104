package task.member.presentation.error

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import tools.jackson.databind.json.JsonMapper

/** 오류 응답의 여섯 필드(type·title·status·detail·instance·code)를 한 곳에서 만든다(POL-API-002). */
object Problems {
  const val CODE = "code"

  fun of(code: ErrorCode, type: ProblemType? = null, instance: URI? = null): ProblemDetail {
    require(type == null || type.status == code.status) { "오류 코드와 세부 원인의 상태가 다릅니다." }
    val problem =
      ProblemDetail.forStatusAndDetail(
        HttpStatusCode.valueOf(code.status),
        type?.description ?: code.description,
      )
    problem.title = type?.codeName ?: code.codeName
    if (type != null) problem.type = type.uri
    if (instance != null) problem.instance = instance
    problem.setProperty(CODE, code.code)
    return MemberProblemDetail(problem)
  }

  fun of(type: ProblemType): ProblemDetail = of(type.errorCode, type)
}

/** MVC 바깥(필터·보안 처리기)에서도 같은 형식과 로그로 오류를 쓴다(POL-API-002, POL-CODE-004). */
class ProblemResponseWriter(private val mapper: JsonMapper) {
  fun write(
    request: HttpServletRequest,
    response: HttpServletResponse,
    code: ErrorCode,
    type: ProblemType? = null,
  ) {
    SafeErrorLog.record(code, request = request, type = type)
    val problem = Problems.of(code, type, URI.create(request.requestURI))
    // 필터 응답은 MVC 변환기를 거치지 않으므로 ProblemDetail 혼합 설정에 기대지 않고 여섯 필드를 직접 쓴다.
    val body =
      linkedMapOf(
        "type" to problem.type.toString(),
        "title" to problem.title,
        "status" to problem.status,
        "detail" to problem.detail,
        "instance" to problem.instance?.toString(),
        Problems.CODE to code.code,
      )
    response.status = code.status
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    response.characterEncoding = Charsets.UTF_8.name()
    response.writer.write(mapper.writeValueAsString(body))
  }
}
