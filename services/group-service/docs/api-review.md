# 그룹 계약 유지와 검증 보완안

| 항목 | 내용 |
| --- | --- |
| 분류 | 서비스 계약 검토 |
| 상태 | 기존 응답 유지 확정, 중복 입력·중계 오류 방식은 확인 대기. 검증 보완 미구현 |
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

D1 승인 전 summary 중복 ID는 기존 400이다. 제거 후 성공을 선택한다면 원래 요청 수 1~100 → 모든 ID 형식 확인 → 중복 제거 순서로 처리하고 반복 정보의 로그 표현도 확정한다. 그룹 전체 인원·정렬·조회 최대 2회·REPEATABLE_READ 일관성은 유지한다.

오류는 type/title/status/detail/instance/code만 반환한다. 400 VALIDATION_FAILED, 403 INTERNAL_ACCESS_DENIED, 404 GROUP_MEMBERSHIP_NOT_FOUND/NOT_FOUND, 500 GROUP_MEMBERSHIP_AMBIGUOUS/INTERNAL_ERROR, 503 SERVICE_UNAVAILABLE는 현행대로다. 오류 상세에 내부 키·원문·ID 목록을 포함하지 않는다.

## 멤버 경유 Swagger와 추가 필드 차단

그룹의 포트는 호스트에 공개하지 않는다. 멤버의 고정 두 GET 중계로 명세와 실행을 제공하고 내부 호출 키는 서버에서만 추가한다. 사용자 JWT나 local 한정은 별도 결정 전 도입하지 않는다.

제공자 DTO와 중계 성공 응답 모두 정확한 키 집합을 검사한다. 중계는 현재 성공 JSON이 객체인지만 검사하므로 중첩 필드를 포함한 계약 검사가 필요하다. 미정의 키·필수 누락·타입 위반을 발견하면 원문을 반환하지 않고 500 INTERNAL_ERROR로 처리하는 안을 제안한다. 승인된 오류 본문과 HTTP 상태 보존, 1MiB 상한·no-store·헤더 차단은 유지한다.

## 필수 검증 인계

기존 상세 500·503 검사를 재사용한다. 잘못된 상세 ID 400, 빈 summary 200, 입력의 각 오류, 같은 멤버의 동시 중복 입력, 다건 방어, 요청 멤버 범위와 전체 인원, 두 조회의 같은 DB 스냅샷을 검사한다. 원본 그룹 응답과 멤버 중계 양쪽에 추가 필드가 없음을 별도로 검증한다. 멤버 소비자 단독 테스트는 다른 서비스 소스 트리를 직접 읽지 않도록 한다.
