package task.group.presentation.query

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.Explode
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.enums.ParameterStyle
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import task.group.presentation.error.ErrorCode
import task.group.presentation.error.ProblemResponses
import task.group.presentation.error.ProblemType
import task.group.presentation.query.response.*

/** 서비스 소유 OpenAPI와 조회 진입점 계약(POL-CQRS-004). 오류 응답은 [ProblemResponses]에서 만든다(POL-API-002). */
interface GroupQueryApi {
  @Operation(
    operationId = "getGroupSummaries",
    summary = "멤버 ID 목록의 그룹 요약",
    description =
      "members는 요청 멤버만, totalMemberCount는 그룹 전체 인원입니다. 중복 ID는 거부하지 않고 한 번만 조회하며 중복된 ID는 서버 로그(WARN)에 남깁니다. 누락·형식 위반은 400, 원래 입력 100개 초과는 422입니다.",
  )
  @Parameter(
    name = "memberIds",
    `in` = ParameterIn.QUERY,
    required = true,
    style = ParameterStyle.FORM,
    explode = Explode.TRUE,
    array =
      ArraySchema(
        minItems = 1,
        maxItems = 100,
        schema = Schema(type = "string", pattern = "^[a-z0-9]{10}$"),
      ),
  )
  @ApiResponse(
    responseCode = "200",
    description = "그룹별 요약",
    content =
      [
        Content(
          mediaType = "application/json",
          schema = Schema(implementation = GroupSummaryResponse::class),
        )
      ],
  )
  @ProblemResponses(
    [
      ErrorCode.INVALID_PARAMETER,
      ErrorCode.FORBIDDEN,
      ErrorCode.METHOD_NOT_ALLOWED,
      ErrorCode.NOT_ACCEPTABLE,
      ErrorCode.VALIDATION_FAILED,
      ErrorCode.INTERNAL_SERVER_ERROR,
      ErrorCode.SERVICE_UNAVAILABLE,
    ],
    types = [ProblemType.INTERNAL_ACCESS_DENIED, ProblemType.GROUP_MEMBERSHIP_AMBIGUOUS],
    instance = "/api/groups/summary",
  )
  @GetMapping("/api/groups/summary", produces = ["application/json"])
  fun summaries(
    @Parameter(hidden = true) @RequestParam parameters: MultiValueMap<String, String>
  ): GroupSummaryResponse

  @Operation(
    operationId = "getMemberGroupDetail",
    summary = "멤버의 단일 그룹 상세",
    description =
      "형식 위반 400, 미소속 404(type urn:problem-type:group-membership-not-found), 다건 500. 시각은 한국 시간 yyyy-MM-dd HH:mm입니다.",
  )
  @ApiResponse(
    responseCode = "200",
    description = "단일 소속 상세",
    content =
      [
        Content(
          mediaType = "application/json",
          schema = Schema(implementation = MemberGroupDetailResponse::class),
        )
      ],
  )
  @ProblemResponses(
    [
      ErrorCode.INVALID_PARAMETER,
      ErrorCode.FORBIDDEN,
      ErrorCode.RESOURCE_NOT_FOUND,
      ErrorCode.METHOD_NOT_ALLOWED,
      ErrorCode.NOT_ACCEPTABLE,
      ErrorCode.INTERNAL_SERVER_ERROR,
      ErrorCode.SERVICE_UNAVAILABLE,
    ],
    types =
      [
        ProblemType.INTERNAL_ACCESS_DENIED,
        ProblemType.GROUP_MEMBERSHIP_NOT_FOUND,
        ProblemType.GROUP_MEMBERSHIP_AMBIGUOUS,
      ],
    instance = "/api/group/k2m9x0p1q3",
  )
  @GetMapping("/api/group/{memberId}", produces = ["application/json"])
  fun detail(
    @Parameter(
      required = true,
      description = "소문자 영숫자 10자리 멤버 ID. 형식 위반은 400",
      schema = Schema(pattern = "^[a-z0-9]{10}$"),
    )
    @PathVariable
    memberId: String
  ): ResponseEntity<MemberGroupDetailResponse>
}
