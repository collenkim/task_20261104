package task.group.presentation.query

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.RestController
import task.group.application.port.input.GetGroupSummariesUseCase
import task.group.application.port.input.GetMemberGroupDetailUseCase
import task.group.application.query.detail.MemberGroupDetailQuery
import task.group.application.query.summary.GroupSummaryQuery
import task.group.presentation.query.mapper.GroupResponseMapper

/** 입력 포트와 응답 변환만 사용한다(POL-CQRS-004). */
@RestController
class GroupQueryController(
  private val summaries: GetGroupSummariesUseCase,
  private val detail: GetMemberGroupDetailUseCase,
) : GroupQueryApi {
  override fun summaries(parameters: MultiValueMap<String, String>) =
    GroupResponseMapper.summaries(
      summaries.execute(GroupSummaryQuery(parameters["memberIds"] ?: emptyList()))
    )

  override fun detail(memberId: String) =
    ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(GroupResponseMapper.detail(detail.execute(MemberGroupDetailQuery(memberId))))
}
