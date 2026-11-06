# 그룹 계약 유지와 검증 보완안

| 항목 | 내용 |
| --- | --- |
| 분류 | 서비스 계약 검토 |
| 상태 | 기존 응답 유지·중계 필드 선별 적용. 2차(0.2.0): 중복 ID 제외+로그, 오류 코드표, GET 전용 적용 |
| 현재 계약 | [api.md](api.md), [소속 정책](policies.md) |
| 결정·구현 | [D1·D15](../../../docs/tasks/REVIEW-001/decisions.md), [REVIEW-002](../../../docs/tasks/REVIEW-002/plan.md) |

## 응답 필드 집합

그룹 상세는 기존 6필드를 그대로 유지한다. 배열로 바꾸거나 다른 감사 필드를 추가하지 않는다. 아래 모든 키는 필수·비null이다. 예제와 입력 규칙은 현재 API 정의서를 따른다.

| API·JSON 경로 | 타입 | 의미 |
| --- | --- | --- |
| summary: group | 배열 | 미소속만 요청하면 [] |
| group[].groupId | 문자열 | 소문자 영숫자 10자리 그룹 ID |
| group[].groupName | 문자열 | 그룹 이름 |
| group[].totalMemberCount | 정수(int64) | 요청 범위와 별개인 그룹 전체 인원 |
| group[].members | 배열 | 요청된 ID에 해당하는 소속만 포함 |
| group[].members[].memberId | 문자열 | 소문자 영숫자 10자리 멤버 ID |
| group[].members[].permission | 문자열 | 권한 code |
| detail: memberId | 문자열 | 요청한 멤버 ID |
| detail: groupId | 문자열 | 소속 그룹 ID |
| detail: groupName | 문자열 | 그룹 이름 |
| detail: registeredDate | 문자열 | 그룹 가입일, Asia/Seoul yyyy-MM-dd HH:mm |
| detail: pricing | 문자열 | Free/Advanced/Premium code |
| detail: permission | 문자열 | Viewer/Editor/Admin code |

목록은 그룹 ID, 그 안의 members는 멤버 ID 순으로 정렬한다. DB는 UTC TIMESTAMP(6)을 유지한다. 권한·과금 enum의 codeName/status/description은 관리용이며 반환하지 않는다. 소속 0건은 상세 404, 다건은 예외·500·ERROR이며 DB UNIQUE(member_id)도 유지한다.

## 입력과 실패의 변경 범위

D1은 REVIEW-002에서 사용자가 확정했다: 중복 ID는 응답에서 제외하고 중복된 ID 원문을 로그로 남긴다. 처리 순서는 모든 ID 형식 확인(400) → 원래 요청 수 100 초과(422) → 처음 나온 순서로 중복 제거 → WARN `event=input.duplicate_ids`다. 그룹 전체 인원·정렬·조회 최대 2회·REPEATABLE_READ 일관성은 유지한다.

오류는 type/title/status/detail/instance/code만 반환한다. 2차 개정에서 `code`를 상태별 코드표로 바꾸고 원인은 `type`으로 구분한다: 400 `INVALID_PARAMETER`, 403 `FORBIDDEN`(type `internal-access-denied`), 404 `RESOURCE_NOT_FOUND`(미소속은 type `group-membership-not-found`, 경로는 `about:blank`), 405 `METHOD_NOT_ALLOWED`, 422 `VALIDATION_FAILED`, 500 `INTERNAL_SERVER_ERROR`(다건은 type `group-membership-ambiguous`), 503 `SERVICE_UNAVAILABLE`. 현재 표는 [API 정의](api.md#오류와-기타-경로)가 소유한다. 오류 본문에 내부 키·원문·ID 목록을 포함하지 않는다.

## 멤버 경유 Swagger와 추가 필드 차단

그룹의 포트는 호스트에 공개하지 않는다. 멤버의 고정 두 GET 중계로 명세와 실행을 제공하고 내부 호출 키는 서버에서만 추가한다. REVIEW-002 결정으로 중계는 local 프로필에서만 등록하고 멤버 JWT scope `group.read`를 요구한다([ADR-006](../../../docs/common/decisions/ADR-006-member-jwt-authentication.md)). 그룹 서비스는 사용자 토큰을 받지 않는다.

제공자 DTO와 중계 성공 응답 모두 정확한 키 집합을 검사한다. 중계는 모든 깊이에서 정의된 필드만 새 응답으로 구성한다. 추가 필드는 제거하고 필수 누락·타입 위반은 502 `BAD_GATEWAY`(type `group-response-contract-violation`)로 처리한다. 다른 중계 오류는 [승인 계약](../../../docs/tasks/REVIEW-002/approved-contract.md)을 따른다. 승인된 오류 본문과 HTTP 상태 보존, 1MiB 상한·no-store·헤더 차단은 유지한다.

## 필수 검증 인계

기존 상세 500·503 검사를 재사용한다. 잘못된 상세 ID 400, 빈 summary 200, 입력의 각 오류, 같은 멤버의 동시 중복 입력, 다건 방어, 요청 멤버 범위와 전체 인원, 두 조회의 같은 DB 스냅샷을 검사한다. 원본 그룹 응답과 멤버 중계 양쪽에 추가 필드가 없음을 별도로 검증한다. 멤버 소비자 단독 테스트는 다른 서비스 소스 트리를 직접 읽지 않도록 한다.
