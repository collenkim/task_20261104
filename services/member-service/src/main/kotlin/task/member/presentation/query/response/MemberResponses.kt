package task.member.presentation.query.response

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import task.member.presentation.query.mapper.ApiTime

/** 공개 목록 봉투. Spring Data Page의 직렬화 구조에 의존하지 않는다(POL-API-003·004). */
@Schema(description = "멤버 요약 목록과 멤버 수 기준의 페이지 정보", additionalProperties = FALSE)
data class MemberSummaryPageResponse(
  @field:Schema(requiredMode = REQUIRED) val members: List<MemberSummaryResponse>,
  @field:Schema(requiredMode = REQUIRED) val page: MemberPageResponse,
)

/** 적용된 크기와 요청 페이지, 멤버 기준 건수(POL-API-003). */
@Schema(additionalProperties = FALSE)
data class MemberPageResponse(
  @field:Schema(requiredMode = REQUIRED, minimum = "1", maximum = "100") val size: Int,
  @field:Schema(requiredMode = REQUIRED, minimum = "1", description = "1부터 시작하는 요청 페이지")
  val number: Int,
  @field:Schema(requiredMode = REQUIRED, minimum = "0") val totalElements: Long,
  @field:Schema(requiredMode = REQUIRED, minimum = "0") val totalPages: Long,
)

/** 목록에는 기본 식별 정보와 소속 핵심 정보만 노출한다(POL-CQRS-002). */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(additionalProperties = FALSE)
data class MemberSummaryResponse(
  @field:Schema(requiredMode = REQUIRED, pattern = "^[a-z0-9]{10}$") val id: String,
  @field:Schema(requiredMode = REQUIRED) val name: String,
  @field:Schema(
    requiredMode = REQUIRED,
    types = ["string", "null"],
    description = "소속 그룹 이름. 미소속은 null",
  )
  val group: String?,
  @field:Schema(
    requiredMode = REQUIRED,
    types = ["string", "null"],
    description = "권한 code. 미소속은 null",
  )
  val permission: String?,
)

/** 멤버 가입일과 그룹 가입일을 구분하고 공개 계약의 필드만 반환한다(POL-API-004). */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(additionalProperties = FALSE)
data class MemberDetailResponse(
  @field:Schema(requiredMode = REQUIRED, pattern = "^[a-z0-9]{10}$") val id: String,
  @field:Schema(requiredMode = REQUIRED) val name: String,
  @field:Schema(requiredMode = REQUIRED, format = "email") val email: String,
  @field:Schema(requiredMode = REQUIRED) val address: String,
  @field:Schema(
    requiredMode = REQUIRED,
    type = "string",
    pattern = ApiTime.PATTERN,
    minLength = 16,
    maxLength = 16,
    description = "멤버 가입 시각 한국 시간(Asia/Seoul), 분 단위",
  )
  val registeredDate: String,
  @field:Schema(requiredMode = REQUIRED, nullable = true, description = "단일 소속. 미소속은 null")
  val group: GroupMembershipDetailResponse?,
)

/** 선택 필드는 null이어도 키를 유지한다. 목록 DTO와 용도별로 분리한다(POL-API-004). */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(additionalProperties = FALSE)
data class GroupMembershipDetailResponse(
  @field:Schema(requiredMode = REQUIRED) val id: String,
  @field:Schema(requiredMode = REQUIRED) val name: String,
  @field:Schema(requiredMode = REQUIRED, description = "그룹 권한 코드. 새 코드 추가 가능")
  val permission: String,
  @field:Schema(
    requiredMode = REQUIRED,
    types = ["string", "null"],
    pattern = ApiTime.PATTERN,
    minLength = 16,
    maxLength = 16,
    description = "그룹 소속 시각 한국 시간(Asia/Seoul), 분 단위. 모르면 null",
  )
  val registeredDate: String?,
  @field:Schema(
    requiredMode = REQUIRED,
    types = ["string", "null"],
    description = "선택 과금 코드. 모르면 null, 새 코드 추가 가능",
  )
  val pricing: String?,
)

/** 실제 ProblemDetail + code 응답의 문서용 스키마(POL-API-002). */
@Schema(additionalProperties = FALSE)
data class MemberProblemResponse(
  @field:Schema(requiredMode = REQUIRED, example = "about:blank") val type: String,
  @field:Schema(requiredMode = REQUIRED) val title: String,
  @field:Schema(requiredMode = REQUIRED) val status: Int,
  @field:Schema(requiredMode = REQUIRED) val detail: String,
  @field:Schema(requiredMode = REQUIRED) val instance: String,
  @field:Schema(requiredMode = REQUIRED) val code: String,
)
