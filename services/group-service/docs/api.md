# 그룹 서비스 API 정의

| 항목 | 내용 |
| --- | --- |
| 분류 | 서비스 정의 |
| 상태 | 현재(0.2.0) |
| 소유 | group-service, GROUP-001 · REVIEW-002 개정 |
| 접근 | 내부 숫자 IP:포트. X-Internal-Service-Id + 해당 호출자의 X-Internal-Service-Key 필요. GET만 제공 |
| 명세 | openapi.json, 멤버의 /internal-docs/group을 경유한 Swagger 명세·실행(local 전용) |
| 시각 | Asia/Seoul의 yyyy-MM-dd HH:mm. 저장은 UTC TIMESTAMP(6) |

모든 경로는 GET만 제공한다. HEAD·OPTIONS·POST·PUT·PATCH·DELETE·TRACE는 내부 키 검사·핸들러보다 먼저 405 `METHOD_NOT_ALLOWED`와 `Allow: GET`으로 거부한다([POL-API-008](../../../docs/common/api-conventions.md#pol-api-008)).

## GET /api/groups/summary

operationId: getGroupSummaries. 성공 200 application/json.

~~~text
/api/groups/summary?memberIds=tu3fgxd45k&memberIds=dj8fy4bewk
~~~

- memberIds는 반복 파라미터이며 ID는 소문자 영숫자 10자리다.
- 형식 위반은 400 `INVALID_PARAMETER`다: 누락(0개)·빈 값·공백·대문자·형식 위반·콤마 결합(`a,b`). 잘못된 값은 중복 제거로 숨기지 않는다.
- 원래 입력 수(중복 포함)가 100개를 넘으면 422 `VALIDATION_FAILED`다.
- 중복 ID는 거부하지 않는다. 처음 나온 순서로 한 번만 조회하고 응답에도 한 번만 넣는다. 중복된 ID 원문·중복 수·요청 수·고유 수를 WARN 로그 `event=input.duplicate_ids`로 남긴다(사용자 결정, [POL-CODE-004 예외](../../../docs/common/code-conventions.md)).
- group 배열은 그룹 ID 순, members는 멤버 ID 순이다. members에는 요청한 ID만 들어간다.
- totalMemberCount는 요청 ID 수와 관계없는 그룹 전체 인원(int64)이다.
- 미소속 ID는 결과 행이 없으며 모두 미소속이면 group은 빈 배열이다.
- 멤버당 최대 한 소속이므로 결과 그룹도 최대 100개다. 결과를 임의로 절삭하지 않는다.

~~~json
{
  "group": [
    {
      "groupId": "arutjf9348",
      "groupName": "Test1",
      "totalMemberCount": 2,
      "members": [
        { "memberId": "dj8fy4bewk", "permission": "Viewer" },
        { "memberId": "tu3fgxd45k", "permission": "Editor" }
      ]
    }
  ]
}
~~~

## GET /api/group/{memberId}

operationId: getMemberGroupDetail. 아래 6개 필드만 있는 단일 객체다. 모든 필드는 필수·비null이다.

~~~json
{
  "memberId": "tu3fgxd45k",
  "groupId": "arutjf9348",
  "groupName": "Test1",
  "registeredDate": "2026-08-15 13:00",
  "pricing": "Premium",
  "permission": "Editor"
}
~~~

- memberId 형식(소문자 영숫자 10자리) 위반은 400 `INVALID_PARAMETER`다.
- registeredDate는 멤버가 이 그룹에 가입한 시각이다. 멤버 서비스의 가입일과 다르다.
- permission은 Viewer/Editor/Admin, pricing은 Free/Advanced/Premium의 code다. 표시명·설명·코드 상태를 응답에 넣지 않는다.
- 0건은 404 `RESOURCE_NOT_FOUND`(type `urn:problem-type:group-membership-not-found`), 1건은 200, 2건 이상이면 500 `INTERNAL_SERVER_ERROR`(type `urn:problem-type:group-membership-ambiguous`)와 ERROR 로그다.
- DB UNIQUE(member_id)로 복수 입력을 먼저 차단한다. 다건 조회는 별도 방어 규칙이며 첫 행을 선택하지 않는다.
- 상세는 Cache-Control: no-store다.

## 오류와 기타 경로

오류는 application/problem+json이며 type/title/status/detail/instance/code를 포함한다. `code`는 [공통 코드표](../../../docs/common/api-conventions.md#pol-api-002)의 값이고, 같은 상태 안에서 소비자가 구분해야 하는 원인만 `type`에 고정 URN을 쓴다.

| 상황 | 상태·code | type |
| --- | --- | --- |
| 입력 형식 위반 | 400 `INVALID_PARAMETER` | `about:blank` |
| 내부 호출 인증 실패(키·호출자·중복 헤더·IP 범위) | 403 `FORBIDDEN` | `urn:problem-type:internal-access-denied` |
| 상세 미소속 | 404 `RESOURCE_NOT_FOUND` | `urn:problem-type:group-membership-not-found` |
| 없는 경로 | 404 `RESOURCE_NOT_FOUND` | `about:blank` |
| GET 외 메서드 | 405 `METHOD_NOT_ALLOWED` | `about:blank` |
| 지원하지 않는 `Accept` | 406 `NOT_ACCEPTABLE` | `about:blank` |
| memberIds 100개 초과 | 422 `VALIDATION_FAILED` | `about:blank` |
| 다건 소속 조회 | 500 `INTERNAL_SERVER_ERROR` | `urn:problem-type:group-membership-ambiguous` |
| 저장 코드·기타 내부 오류 | 500 `INTERNAL_SERVER_ERROR` | `about:blank` |
| DB 연결/가용성 실패 | 503 `SERVICE_UNAVAILABLE` | `about:blank` |

멤버 서비스는 상세 404 중 `code`·`status`·`type`이 모두 미소속 값일 때만 group=null로 바꾼다. 경로 404(type `about:blank`)는 계약 오류다.

내부 인증 실패는 사용자 인증이 아니라 호출자 식별 실패이므로 401이 아닌 403이다. 사용자 토큰을 받지 않는 내부 API라 401·429는 선언하지 않는다(호출 수 제한은 사용자 진입점인 멤버 서비스가 담당한다).

GET health는 키 없이 접근한다. /error는 오류 처리 경로다. /v3/api-docs에는 내부 인증이 필요하며 그룹 Swagger UI는 제공하지 않는다.

X-Request-Id는 허용된 값이면 이어 사용하고 없거나 잘못되면 생성한다. 두 서비스의 요청·업무·의존 호출 로그를 연결한다. 키·본문은 로그에 남기지 않는다. 멤버 ID는 summary 중복 경고의 중복 ID 원문만 예외로 남긴다.

멤버 목록은 summary 한 번, 상세는 detail 한 번을 호출한다. 그룹 5xx는 멤버 업무 API의 503이며 미소속 전용 404만 멤버의 group=null로 바꾼다.

## 멤버 경유 Swagger 실행

local 프로필의 멤버 Swagger에서 그룹 서비스를 선택하고 Try it out / Execute를 사용한다. 브라우저는 /internal-api/group 아래의 같은 경로를 JWT(scope `group.read`)로 호출하고 멤버 서버가 내부 키를 추가한다. 그룹 원본 OpenAPI·응답·호스트 포트 정책은 유지한다. 중계의 상태 변환은 [멤버 API의 중계 표](../../member-service/docs/api.md#그룹-swagger-실행-중계)와 [중계 정책](../../../docs/common/api-conventions.md#그룹-api-실행-중계)을 따른다.
