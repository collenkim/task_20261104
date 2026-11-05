package task.member.presentation.error

import com.fasterxml.jackson.annotation.JsonInclude
import java.net.URI
import org.springframework.http.ProblemDetail

/** about:blank도 필수 type으로 직렬화한다. Spring의 확장 속성 처리는 유지한다(POL-API-002). */
class MemberProblemDetail(source: ProblemDetail) : ProblemDetail(source) {
  @JsonInclude(JsonInclude.Include.ALWAYS)
  override fun getType(): URI = super.getType() ?: URI.create("about:blank")
}
