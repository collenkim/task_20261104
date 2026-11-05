package task.group.presentation.error

import com.fasterxml.jackson.annotation.JsonInclude
import java.net.URI
import org.springframework.http.ProblemDetail

/** about:blank type도 필수 필드로 직렬화한다(POL-API-002). */
class GroupProblemDetail(source: ProblemDetail) : ProblemDetail(source) {
  @JsonInclude(JsonInclude.Include.ALWAYS)
  override fun getType(): URI = super.getType() ?: URI.create("about:blank")
}
