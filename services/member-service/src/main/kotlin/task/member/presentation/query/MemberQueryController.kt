package task.member.presentation.query

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.RestController
import task.member.application.port.input.GetMemberDetailUseCase
import task.member.application.port.input.GetMemberSummariesUseCase
import task.member.application.query.detail.MemberDetailQuery
import task.member.presentation.query.mapper.MemberResponseMapper
import task.member.presentation.query.request.MemberSummaryRequest

/** 입력 포트만 호출하고 HTTP 변환만 담당한다(POL-CQRS-004). */
@RestController
class MemberQueryController(
  private val summariesUseCase: GetMemberSummariesUseCase,
  private val detailUseCase: GetMemberDetailUseCase,
) : MemberQueryApi {
  override fun summaries(parameters: MultiValueMap<String, String>) =
    MemberResponseMapper.summaries(
      summariesUseCase.execute(MemberSummaryRequest.from(parameters).toQuery())
    )

  override fun detail(id: String) =
    ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .body(MemberResponseMapper.detail(detailUseCase.execute(MemberDetailQuery(id))))
}
