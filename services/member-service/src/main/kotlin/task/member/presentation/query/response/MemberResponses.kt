package task.member.presentation.query.response

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED
import java.time.Instant

/** 공개 목록 봉투. Spring Data Page의 직렬화 구조에 의존하지 않는다(POL-API-003·004). */
@Schema(description = "멤버 요약 목록과 멤버 수 기준의 페이지 정보")
data class MemberSummaryPageResponse(
  @field:Schema(requiredMode = REQUIRED) val members: List<MemberSummaryResponse>,
  @field:Schema(requiredMode = REQUIRED) val page: MemberPageResponse,
)

/** 적용된 크기와 요청 페이지, 멤버 기준 건수(POL-API-003). */
data class MemberPageResponse(
  @field:Schema(requiredMode = REQUIRED, minimum = "1", maximum = "100") val size: Int,
  @field:Schema(requiredMode = REQUIRED, minimum = "0") val number: Int,
  @field:Schema(requiredMode = REQUIRED, minimum = "0") val totalElements: Long,
  @field:Schema(requiredMode = REQUIRED, minimum = "0") val totalPages: Long,
)

/** 목록에는 기본 식별 정보와 소속 핵심 정보만 노출한다(POL-CQRS-002). */
data class MemberSummaryResponse(
  @field:Schema(requiredMode = REQUIRED, pattern = "^[a-z0-9]{10}$") val id: String,
  @field:Schema(requiredMode = REQUIRED) val name: String,
  @field:Schema(requiredMode = REQUIRED, description = "미소속은 빈 배열, group ID 오름차순")
  val groups: List<GroupMembershipSummaryResponse>,
)

/** 권한 코드는 그룹이 소유하는 열린 문자열이며 코드명은 표시용이다(POL-API-004). */
data class GroupMembershipSummaryResponse(
  @field:Schema(requiredMode = REQUIRED) val id: String,
  @field:Schema(requiredMode = REQUIRED) val name: String,
  @field:Schema(requiredMode = REQUIRED, description = "그룹 권한 코드. 새 코드 추가 가능")
  val permission: String,
  @field:Schema(requiredMode = REQUIRED, description = "표시용 권한 코드명") val permissionName: String,
)

/** 감사 정보와 소속을 포함한 상세 계약. 시각은 UTC·마이크로초를 보존한다(POL-API-004). */
data class MemberDetailResponse(
  @field:Schema(requiredMode = REQUIRED, pattern = "^[a-z0-9]{10}$") val id: String,
  @field:Schema(requiredMode = REQUIRED) val name: String,
  @field:Schema(requiredMode = REQUIRED, format = "email") val email: String,
  @field:Schema(requiredMode = REQUIRED) val address: String,
  @field:Schema(
    requiredMode = REQUIRED,
    type = "string",
    format = "date-time",
    description = "멤버 가입 시각 UTC",
  )
  val registeredDate: Instant,
  @field:Schema(
    requiredMode = REQUIRED,
    type = "string",
    format = "date-time",
    description = "행 생성 시각 UTC",
  )
  val createdAt: Instant,
  @field:Schema(requiredMode = REQUIRED, description = "행 생성 주체") val createdBy: String,
  @field:Schema(requiredMode = REQUIRED, description = "미소속은 빈 배열, group ID 오름차순")
  val groups: List<GroupMembershipDetailResponse>,
)

/** 선택 필드는 null이어도 키를 유지한다. 목록 DTO와 용도별로 분리한다(POL-API-004). */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class GroupMembershipDetailResponse(
  @field:Schema(requiredMode = REQUIRED) val id: String,
  @field:Schema(requiredMode = REQUIRED) val name: String,
  @field:Schema(requiredMode = REQUIRED, description = "그룹 권한 코드. 새 코드 추가 가능")
  val permission: String,
  @field:Schema(requiredMode = REQUIRED, description = "표시용 권한 코드명") val permissionName: String,
  @field:Schema(
    requiredMode = REQUIRED,
    types = ["string", "null"],
    format = "date-time",
    description = "그룹 소속 시각 UTC. 모르면 null",
  )
  val joinedAt: Instant?,
  @field:Schema(
    requiredMode = REQUIRED,
    types = ["string", "null"],
    description = "선택 과금 코드. 모르면 null, 새 코드 추가 가능",
  )
  val pricing: String?,
)

/** 실제 ProblemDetail + code 응답의 문서용 스키마(POL-API-002). */
data class MemberProblemResponse(
  @field:Schema(requiredMode = REQUIRED, example = "about:blank") val type: String,
  @field:Schema(requiredMode = REQUIRED) val title: String,
  @field:Schema(requiredMode = REQUIRED) val status: Int,
  @field:Schema(requiredMode = REQUIRED) val detail: String,
  @field:Schema(requiredMode = REQUIRED) val instance: String,
  @field:Schema(requiredMode = REQUIRED) val code: String,
)
