package task.group.presentation.query.response

import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED

/** 그룹별 요약 봉투(POL-GROUP-002). */
@Schema(additionalProperties = FALSE)
data class GroupSummaryResponse(
  @field:Schema(requiredMode = REQUIRED) val group: List<GroupSummaryItem>
)

/** 전체 인원과 요청 멤버를 구분한다(POL-GROUP-002). */
@Schema(additionalProperties = FALSE)
data class GroupSummaryItem(
  @field:Schema(requiredMode = REQUIRED, pattern = "^[a-z0-9]{10}$") val groupId: String,
  @field:Schema(requiredMode = REQUIRED, minLength = 1) val groupName: String,
  @field:Schema(requiredMode = REQUIRED, minimum = "0", description = "그룹 전체 소속 인원")
  val totalMemberCount: Long,
  @field:Schema(requiredMode = REQUIRED) val members: List<GroupMemberSummary>,
)

/** 권한 표시명은 응답에 포함하지 않는다(POL-CODE-005). */
@Schema(additionalProperties = FALSE)
data class GroupMemberSummary(
  @field:Schema(requiredMode = REQUIRED, pattern = "^[a-z0-9]{10}$") val memberId: String,
  @field:Schema(requiredMode = REQUIRED, description = "권한 code: Viewer, Editor, Admin")
  val permission: String,
)

/** 기존 6개 필드의 단일 상세 객체(POL-GROUP-001). */
@Schema(additionalProperties = FALSE)
data class MemberGroupDetailResponse(
  @field:Schema(requiredMode = REQUIRED, pattern = "^[a-z0-9]{10}$") val memberId: String,
  @field:Schema(requiredMode = REQUIRED, pattern = "^[a-z0-9]{10}$") val groupId: String,
  @field:Schema(requiredMode = REQUIRED, minLength = 1) val groupName: String,
  @field:Schema(
    requiredMode = REQUIRED,
    pattern = "^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}$",
    minLength = 16,
    maxLength = 16,
    example = "2026-08-15 13:00",
    description = "그룹 가입 시각. 한국 시간(Asia/Seoul), 분 단위",
  )
  val registeredDate: String,
  @field:Schema(requiredMode = REQUIRED, description = "과금 code: Free, Advanced, Premium")
  val pricing: String,
  @field:Schema(requiredMode = REQUIRED, description = "권한 code: Viewer, Editor, Admin")
  val permission: String,
)

/** 실제 오류 응답 계약(POL-API-002). */
@Schema(additionalProperties = FALSE)
data class GroupProblemResponse(
  @field:Schema(requiredMode = REQUIRED, example = "about:blank") val type: String,
  @field:Schema(requiredMode = REQUIRED) val title: String,
  @field:Schema(requiredMode = REQUIRED) val status: Int,
  @field:Schema(requiredMode = REQUIRED) val detail: String,
  @field:Schema(requiredMode = REQUIRED) val instance: String,
  @field:Schema(requiredMode = REQUIRED) val code: String,
)
