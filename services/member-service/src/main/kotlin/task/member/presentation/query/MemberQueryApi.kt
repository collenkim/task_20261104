package task.member.presentation.query

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.Parameters
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import task.member.presentation.query.response.MemberDetailResponse
import task.member.presentation.query.response.MemberProblemResponse
import task.member.presentation.query.response.MemberSummaryPageResponse

/** HTTP 계약을 컨트롤러 처리와 분리한다. 소비자는 생성 OpenAPI를 공유한다(POL-API-004). */
@Tag(name = "멤버 조회", description = "멤버 기본 정보와 다중 그룹 소속 조회")
interface MemberQueryApi {
  @Operation(
    operationId = "getMemberSummaries",
    summary = "멤버 요약 목록 조회",
    description =
      "이름 정렬과 멤버 기준 페이지. page/size/sort는 같은 값이어도 반복 불가. 공백·부호·소수 불가. size 범위 확인 후 최대 100 적용, page × 적용 크기는 2147483647 이하.",
  )
  @Parameters(
    Parameter(
      name = "page",
      `in` = ParameterIn.QUERY,
      description = "0부터 시작. ASCII 숫자만 허용",
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
      description = "정수 검증 후 최대 100 적용. ASCII 숫자만 허용",
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
      description = "동명이인은 항상 ID 오름차순",
      schema =
        Schema(
          type = "string",
          defaultValue = "name,asc",
          allowableValues = ["name,asc", "name,desc"],
        ),
    ),
  )
  @ApiResponses(
    ApiResponse(
      responseCode = "200",
      description = "멤버 요약 페이지",
      content =
        [
          Content(
            mediaType = "application/json",
            schema = Schema(implementation = MemberSummaryPageResponse::class),
          )
        ],
    ),
    ApiResponse(
      responseCode = "400",
      description = "VALIDATION_FAILED: 조회 입력 위반",
      content =
        [
          Content(
            mediaType = "application/problem+json",
            schema = Schema(implementation = MemberProblemResponse::class),
            examples = [ExampleObject(value = MemberQueryExamples.INVALID_SUMMARY)],
          )
        ],
    ),
    ApiResponse(
      responseCode = "500",
      description = "INTERNAL_ERROR: 내부 설정·계약 오류",
      content =
        [
          Content(
            mediaType = "application/problem+json",
            schema = Schema(implementation = MemberProblemResponse::class),
            examples = [ExampleObject(value = MemberQueryExamples.INTERNAL_ERROR)],
          )
        ],
    ),
    ApiResponse(
      responseCode = "503",
      description = "GROUP_SERVICE_UNAVAILABLE: 그룹 일시 장애",
      content =
        [
          Content(
            mediaType = "application/problem+json",
            schema = Schema(implementation = MemberProblemResponse::class),
            examples = [ExampleObject(value = MemberQueryExamples.GROUP_UNAVAILABLE)],
          )
        ],
    ),
  )
  @GetMapping("/api/members", produces = ["application/json"])
  fun summaries(
    @Parameter(hidden = true) @RequestParam parameters: MultiValueMap<String, String>
  ): MemberSummaryPageResponse

  @Operation(
    operationId = "getMemberDetail",
    summary = "멤버 상세 조회",
    description = "기본 정보·생성 감사 정보·다중 그룹 소속. 응답은 no-store, 시각은 UTC이며 마이크로초를 보존합니다.",
  )
  @ApiResponses(
    ApiResponse(
      responseCode = "200",
      description = "멤버 상세",
      content =
        [
          Content(
            mediaType = "application/json",
            schema = Schema(implementation = MemberDetailResponse::class),
          )
        ],
    ),
    ApiResponse(
      responseCode = "400",
      description = "VALIDATION_FAILED: 식별자 형식 오류",
      content =
        [
          Content(
            mediaType = "application/problem+json",
            schema = Schema(implementation = MemberProblemResponse::class),
            examples = [ExampleObject(value = MemberQueryExamples.INVALID_DETAIL)],
          )
        ],
    ),
    ApiResponse(
      responseCode = "404",
      description = "NOT_FOUND: 멤버 없음",
      content =
        [
          Content(
            mediaType = "application/problem+json",
            schema = Schema(implementation = MemberProblemResponse::class),
            examples = [ExampleObject(value = MemberQueryExamples.NOT_FOUND)],
          )
        ],
    ),
    ApiResponse(
      responseCode = "500",
      description = "INTERNAL_ERROR: 내부 설정·계약 오류",
      content =
        [
          Content(
            mediaType = "application/problem+json",
            schema = Schema(implementation = MemberProblemResponse::class),
            examples = [ExampleObject(value = MemberQueryExamples.INTERNAL_ERROR)],
          )
        ],
    ),
    ApiResponse(
      responseCode = "503",
      description = "GROUP_SERVICE_UNAVAILABLE: 그룹 일시 장애",
      content =
        [
          Content(
            mediaType = "application/problem+json",
            schema = Schema(implementation = MemberProblemResponse::class),
            examples = [ExampleObject(value = MemberQueryExamples.GROUP_UNAVAILABLE)],
          )
        ],
    ),
  )
  @GetMapping("/api/member/{id}", produces = ["application/json"])
  fun detail(
    @Parameter(
      required = true,
      description = "소문자 영숫자 10자리 멤버 ID",
      schema = Schema(pattern = "^[a-z0-9]{10}$"),
    )
    @PathVariable("id")
    id: String
  ): ResponseEntity<MemberDetailResponse>
}
