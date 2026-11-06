package task.member.presentation.query

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.Parameters
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import task.member.presentation.error.ErrorCode
import task.member.presentation.error.ProblemResponses
import task.member.presentation.error.ProblemType
import task.member.presentation.query.response.MemberDetailResponse
import task.member.presentation.query.response.MemberSummaryPageResponse

/** HTTP 계약을 컨트롤러 처리와 분리한다. 소비자는 생성 OpenAPI를 공유한다(POL-API-004). */
@Tag(name = "멤버 조회", description = "멤버 기본 정보와 그룹 소속 조회. 현재 멤버당 0~1개")
interface MemberQueryApi {
  @Operation(
    operationId = "getMemberSummaries",
    summary = "멤버 요약 목록 조회",
    description =
      "이름 정렬과 멤버 기준 페이지. 형식 위반(반복·공백·부호·소수·int32 초과)은 400 INVALID_PARAMETER, 형식은 맞지만 처리할 수 없는 값(size 0, page × 적용 크기 2147483647 초과, 허용하지 않는 sort)은 422 VALIDATION_FAILED. size는 범위 확인 후 최대 100 적용.",
  )
  @Parameters(
    Parameter(
      name = "page",
      `in` = ParameterIn.QUERY,
      description = "0부터 시작. ASCII 숫자만 허용(부호·공백 400)",
      schema =
        Schema(
          type = "integer",
          format = "int32",
          defaultValue = "0",
          minimum = "0",
          maximum = "2147483647",
        ),
    ),
    Parameter(
      name = "size",
      `in` = ParameterIn.QUERY,
      description = "ASCII 숫자만 허용. 0은 422, 100 초과는 100으로 적용(page.size에 표시)",
      schema =
        Schema(
          type = "integer",
          format = "int32",
          defaultValue = "20",
          minimum = "1",
          maximum = "2147483647",
        ),
    ),
    Parameter(
      name = "sort",
      `in` = ParameterIn.QUERY,
      description = "허용 값 외에는 422. 동명이인은 항상 ID 오름차순",
      schema =
        Schema(
          type = "string",
          defaultValue = "name,asc",
          allowableValues = ["name,asc", "name,desc"],
        ),
    ),
  )
  @ApiResponse(
    responseCode = "200",
    description = "멤버 요약 페이지",
    content =
      [
        Content(
          mediaType = "application/json",
          schema = Schema(implementation = MemberSummaryPageResponse::class),
        )
      ],
  )
  @ProblemResponses(
    [
      ErrorCode.INVALID_PARAMETER,
      ErrorCode.UNAUTHORIZED,
      ErrorCode.FORBIDDEN,
      ErrorCode.METHOD_NOT_ALLOWED,
      ErrorCode.NOT_ACCEPTABLE,
      ErrorCode.VALIDATION_FAILED,
      ErrorCode.TOO_MANY_REQUESTS,
      ErrorCode.INTERNAL_SERVER_ERROR,
      ErrorCode.SERVICE_UNAVAILABLE,
    ],
    types = [ProblemType.GROUP_SERVICE_UNAVAILABLE],
    instance = "/api/members",
  )
  @GetMapping("/api/members", produces = ["application/json"])
  fun summaries(
    @Parameter(hidden = true) @RequestParam parameters: MultiValueMap<String, String>
  ): MemberSummaryPageResponse

  @Operation(
    operationId = "getMemberDetail",
    summary = "멤버 상세 조회",
    description =
      "기본 정보와 단일 그룹 소속(미소속 null). 감사 정보는 응답에서 제외합니다. 응답은 no-store, 시각은 한국 시간(Asia/Seoul) yyyy-MM-dd HH:mm입니다.",
  )
  @ApiResponse(
    responseCode = "200",
    description = "멤버 상세",
    content =
      [
        Content(
          mediaType = "application/json",
          schema = Schema(implementation = MemberDetailResponse::class),
        )
      ],
  )
  @ProblemResponses(
    [
      ErrorCode.INVALID_PARAMETER,
      ErrorCode.UNAUTHORIZED,
      ErrorCode.FORBIDDEN,
      ErrorCode.RESOURCE_NOT_FOUND,
      ErrorCode.METHOD_NOT_ALLOWED,
      ErrorCode.NOT_ACCEPTABLE,
      ErrorCode.TOO_MANY_REQUESTS,
      ErrorCode.INTERNAL_SERVER_ERROR,
      ErrorCode.SERVICE_UNAVAILABLE,
    ],
    types = [ProblemType.MEMBER_NOT_FOUND, ProblemType.GROUP_SERVICE_UNAVAILABLE],
    instance = "/api/member/missing001",
  )
  @GetMapping("/api/member/{memberId}", produces = ["application/json"])
  fun detail(
    @Parameter(
      required = true,
      description = "소문자 영숫자 10자리 멤버 ID. 형식 위반은 400",
      schema = Schema(pattern = "^[a-z0-9]{10}$"),
    )
    @PathVariable("memberId")
    memberId: String
  ): ResponseEntity<MemberDetailResponse>
}
