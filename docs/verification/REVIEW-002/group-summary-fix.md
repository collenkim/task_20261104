# 그룹 summary 매핑 기준 집계 보완

| 항목 | 내용 |
| --- | --- |
| 분류 | 검증 기록 |
| 상태 | 구현·빌드·실제 Compose API·Swagger 검증 완료 |
| 확인일 | 2026-11-06, Asia/Seoul |
| 대상 | feature/REVIEW-002 작업 폴더, f1c17f2 이후 변경 |
| 승인 | [4차 승인](../../tasks/REVIEW-002/approved-contract.md#4차-승인-그룹-summary-매핑-기준-집계) |
| 현행 계약 | [그룹 API 0.4.0](../../../services/group-service/docs/api.md#get-apigroupssummary) |

## 원인과 재현

기존 코드는 요청 ID에 해당하는 매핑과 그룹 전체 인원을 별도로 조회했다. 따라서 members에는 1명만 반환하면서 totalMemberCount에는 요청하지 않은 다른 소속까지 포함한 2를 반환했다. 이전 문서·테스트도 이 전체 인원 계약을 따르고 있었으며, 이번 사용자 결정으로 요청 범위의 실제 반환 인원으로 변경한다.

실제 로컬 Compose에서 멤버 API로 합성 시드의 존재·소속을 확인한 뒤 그룹 중계를 조회했다. 토큰과 응답 원문을 출력하지 않고 아래 수치만 기록했다.

| 요청 | 수정 전 group 항목 수 | 수정 전 count / members 수 | 수정 후 기준 |
| --- | --- | --- | --- |
| 같은 그룹의 두 멤버 | 1 | 2 / 2 | 2 / 2 유지 |
| 소속 멤버 + 없는 ID | 1 | 2 / 1 | 1 / 1 |
| 소속 멤버 + 매핑 없는 멤버 | 1 | 2 / 1 | 1 / 1 |
| 매핑 없는 멤버만 | 0 | 없음 | 빈 group 배열 유지 |
| 없는 ID만 | 0 | 없음 | 빈 group 배열 유지 |

같은 그룹이면 바깥 group 배열이 1개인 것은 정상이다. 문제는 각 항목의 totalMemberCount와 members의 크기가 달랐던 점이다.

## 구현

- group_membership의 요청 ID 매핑만 조회한다. 전체 그룹 count 쿼리와 별도 counts 모델을 제거하고 QueryHandler가 실제 members 크기를 Long으로 계산한다.
- 조회는 최대 2회에서 1회로 줄었다. 읽기 트랜잭션의 REPEATABLE_READ, 정렬, UNIQUE(member_id), 그룹 FK는 유지한다.
- 매핑 없는 ID는 반환과 집계에서 제외한다. permission:null·가상 그룹·멤버 원장 역호출·DB 스키마 변경을 추가하지 않는다.
- 중복은 기존대로 한 번 반환하고 WARN으로 기록한다. 그룹 상세의 필드와 404 정책은 유지한다.
- 멤버의 그룹 HTTP 소비자와 Swagger 중계는 count 불일치를 계약 오류로 검출한다. 중계는 502 BAD_GATEWAY / group-response-contract-violation이다. 합성 HTTP 응답도 새 의미에 맞춘다.
- 멤버 API의 실제 멤버 미소속 표시(group/permission=null)는 유지한다. 이번 null 제외 결정은 그룹 summary의 매핑 없는 ID 처리에 대한 것이다.

## 검증

- 실제 MariaDB: 같은 그룹의 요청 일부, 없는 매핑 혼합, 여러 그룹, 모두 미매핑, 중복, 쿼리 1회, 요청 밖 매핑의 동시 추가, 트랜잭션 종료 검증.
- 합성 HTTP: count가 반환 수보다 크거나 작은 경우의 멤버 소비자 거부·중계 502 검증.
- 공개 계약: OpenAPI 0.4.0을 운영 코드에서 생성하고 실제 JSON·문서 예제·공통 오류를 검사.
- 실제 Compose: runtime-smoke.py의 없는 ID/미소속 혼합·전체 미매핑 검사로 동일 문제 재현·해결을 확인.

수정 전과 동일한 다섯 요청을 다시 실행해 표의 수정 후 기준을 모두 확인했다. 모든 응답에서 permission:null 항목은 0개였다.

| 검사 | 결과 |
| --- | --- |
| 빌드·단위·DB·HTTP·계약 | spotlessApply :group-service:updateOpenApiSnapshot build 성공(1분 52초). 멤버 216개·그룹 70개, 실패/오류/건너뜀 0 |
| 실제 Compose API | startLocal 성공(54초), runtime-smoke.py 통과. 없는 ID/미매핑 혼합·전체 미매핑·기존 멤버 API·명세·인증·로그 추적 확인 |
| Swagger | 실제 Edge에서 네 API Try it out 통과. 그룹 summary에 한 ID 입력 시 count=1, members 크기=1 |
| 문서·공통 계약·공백 | docs-check.py·api-contract-check.py 오류 0, git diff --check 통과 |

Swagger 검사 첫 실행에서는 기존 전체 인원 기대값 2가 남아 실패했다. 요청 ID가 하나인 실제 응답 1을 확인하고 새 계약대로 기대값과 배열 길이 검사를 수정한 뒤 재실행했다. 실제 기능 코드의 결과를 다시 2로 바꾸지 않았다.

DB 스키마·시드·볼륨·키는 변경하지 않았다. AI 턴 중 커밋·병합·push도 수행하지 않았다. 이전 요구 재검증의 3차 집계 설명과 성능 후보는 이 후속 기록으로 대체한다.
