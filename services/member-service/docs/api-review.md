# 멤버 응답 계약 개정안

| 항목 | 내용 |
| --- | --- |
| 분류 | 서비스 계약 개정안 |
| 상태 | 사용자 승인 · REVIEW-002 적용. 1차(0.3.0 응답 필드)·2차(0.4.0 인증·오류 코드)·3차(0.5.0 페이지·JSON) |
| 현재 계약 | [api.md](api.md), OpenAPI 0.5.0 |
| 적용 버전 | 0.3.0 응답 필드 변경, 0.4.0 JWT·오류 코드표·400/422 구분, 0.5.0 페이지 1 시작·application/json. 기존 소비자의 요청·오류 처리 변경 필요 |
| 결정·작업 | [결정표](../../../docs/tasks/REVIEW-001/decisions.md), [REVIEW-002](../../../docs/tasks/REVIEW-002/plan.md) |

정의된 응답 외 필드를 내보내지 않는 요구는 확정됐다. 아래 필드 집합은 **사용자 승인 계약**이며 [REVIEW-002 검증](../../../docs/verification/REVIEW-002/verification.md)을 마쳤다. 기존 코드에 배열·감사 필드가 실제 존재했으며 단일 group·감사 필드 제외·가입일 분리를 승인받아 변경했다.

## 공통 입력과 표현

기존 두 경로·operationId, page/size/sort 규칙, 이름 정렬과 ID 보조 정렬, 페이지 설정 블록, 오류 매핑을 유지한다. 세부 입력 범위는 [현재 입력 정의](api.md#get-apimembers)가 소유한다. DB는 UTC TIMESTAMP(6), 응답 시각 문자열은 Asia/Seoul의 yyyy-MM-dd HH:mm이다. 멤버 가입일과 그룹 가입일은 독립된 값이다.

permission은 그룹 권한 code다. 현재 값은 Viewer/Editor/Admin이며 소비자는 새로운 비어 있지 않은 code를 수용한다. pricing도 Free/Advanced/Premium 등 code다. status·codeName·description은 내부 enum 관리값이며 응답에 추가하지 않는다.

## GET /api/members

200 application/json. 다음 표의 모든 키는 필수다. null은 표에서 허용한 경우에만 가능하다.

| JSON 경로 | 타입 | null | 의미·출처 |
| --- | --- | --- | --- |
| members | 배열 | 불가 | 조회한 멤버 페이지. 빈 페이지는 [] |
| members[].id | 문자열 | 불가 | 멤버 DB 식별자, 소문자 영숫자 10자리 |
| members[].name | 문자열 | 불가 | 멤버 DB 이름 |
| members[].group | 문자열 | 가능 | 그룹 summary의 그룹명. 미소속만 null |
| members[].permission | 문자열 | 가능 | 소속 권한 code. 미소속만 null |
| page | 객체 | 불가 | 아래 네 필드만 포함 |
| page.size | 정수 | 불가 | 적용한 페이지 크기 1~100 |
| page.number | 정수 | 불가 | 요청한 1 기반 페이지 번호 |
| page.totalElements | 정수(int64) | 불가 | 멤버 전체 수, 0 이상 |
| page.totalPages | 정수(int64) | 불가 | 전체 페이지 수, 0건이면 0 |

group과 permission은 함께 null이거나 함께 값이 있다. 그룹 응답 장애를 null로 바꾸지 않는다. 그룹 정보는 페이지당 summary 한 번으로 조합하며 빈 페이지는 호출하지 않는다. 이 호출 수는 재시도 전 논리 호출 기준이다.

```json
{
  "members": [
    {"id": "tu3fgxd45k", "name": "user1", "group": "Test1", "permission": "Editor"},
    {"id": "k2m9x0p1q3", "name": "user3", "group": null, "permission": null}
  ],
  "page": {"size": 20, "number": 1, "totalElements": 2, "totalPages": 1}
}
```

예제는 2명인 별도 데이터셋이다. 전체 local 시드의 응답을 복사한 예제가 아니다.

## GET /api/member/{memberId}

200 application/json, Cache-Control: no-store. 다음 키만 포함한다. 모든 키는 필수이며 선택 값도 키를 생략하지 않는다.

| JSON 경로 | 타입 | null | 의미·출처 |
| --- | --- | --- | --- |
| id | 문자열 | 불가 | 멤버 DB ID |
| name | 문자열 | 불가 | 멤버 DB 이름 |
| email | 문자열 | 불가 | 멤버 DB 이메일 |
| address | 문자열 | 불가 | 멤버 DB 주소 |
| registeredDate | 문자열 | 불가 | 멤버 가입일, 한국 시간 분 단위 |
| group | 객체 | 가능 | 미소속은 null. 소속이 있으면 아래 다섯 키 |
| group.id | 문자열 | 불가 | 그룹 원본 groupId |
| group.name | 문자열 | 불가 | 그룹 원본 groupName |
| group.registeredDate | 문자열 | 가능 | 그룹 가입일. 기존 선택 정보 허용 범위 유지 |
| group.pricing | 문자열 | 가능 | 과금 code. 선택 정보 미제공이면 null |
| group.permission | 문자열 | 불가 | 소속 권한 code |

```json
{
  "id": "tu3fgxd45k",
  "name": "user1",
  "email": "user1@example.invalid",
  "address": "가상 주소 1",
  "registeredDate": "2026-08-15 13:00",
  "group": {
    "id": "arutjf9348",
    "name": "Test1",
    "registeredDate": "2026-08-16 09:00",
    "pricing": "Premium",
    "permission": "Editor"
  }
}
```

멤버가 존재하고 미소속이면 같은 여섯 기본 키 중 group 값만 null이다. 실제 그룹 상세의 가입일·과금은 필수·비null이며, 소비자 선택 정보 허용은 그룹 제공자 계약을 느슨하게 만드는 변경이 아니다. 데이터가 여러 소속으로 해석되면 대표 행을 선택하거나 정상 응답하지 않는다.

## 오류와 중계

1차 개정은 기존 오류 코드를 유지했다. 2차 개정(0.4.0, 사용자 결정)에서 오류 `code`를 상태별 코드표로 바꾸고 세부 원인을 `type` URN으로 옮겼다: 형식 위반 400 `INVALID_PARAMETER`, 의미상 처리 불가 422 `VALIDATION_FAILED`, 멤버 없음 404 `RESOURCE_NOT_FOUND`(type `member-not-found`), 그룹 통신·5xx 503 `SERVICE_UNAVAILABLE`(type `group-service-unavailable`), 계약 위반 500 `INTERNAL_SERVER_ERROR`, 인증 401 `UNAUTHORIZED`·403 `FORBIDDEN`, GET 외 405, 요청 제한 429. 오류는 type/title/status/detail/instance/code의 여섯 키다. 그룹 상세 404 중 type `group-membership-not-found`만 정상 미소속으로 바꾼다. 현재 표는 [API 정의](api.md#오류-응답)가 소유한다.

Swagger 그룹 중계는 멤버 업무 DTO로 바꾸지 않고 [그룹 개정안](../../group-service/docs/api-review.md)의 정확한 필드를 보장한다. 추가 필드는 선별 과정에서 제거하며 필수 누락·타입 위반은 502로 처리한다. 세부 상태·코드는 [승인 계약](../../../docs/tasks/REVIEW-002/approved-contract.md)을 따른다. 키·원문 본문·개인정보를 오류나 로그에 싣지 않는다.

## REVIEW-002 적용과 검사

DTO·매퍼·입력/출력 포트의 영향 범위를 확인하고, 내부 DB 모델을 공개 DTO와 분리한다. createdAt·createdBy는 응답에서만 제외하며 저장·감사 처리는 유지한다. groups→group, joinedAt→registeredDate 및 감사 필드 제거는 소비자 호환성 변경으로 공유한다.

최상위·항목·page·중첩 group·오류 키 집합을 실제 응답으로 검사하고, 미정의 키 삽입을 검사기가 거부하는지 확인한다. 내부 소비자가 무시한 원격 추가 필드가 공개 응답에 전달되지 않아야 한다. 승인된 api.md 예제·운영 코드·생성 OpenAPI를 함께 갱신한다.
