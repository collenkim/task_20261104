# 그룹 서비스 API 정의

| 항목 | 내용 |
| --- | --- |
| 분류 | 서비스 정의 |
| 상태 | 현재(0.4.0) |
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
- totalMemberCount는 요청 ID 중 실제 반환된 members의 개수(int64)다. 요청하지 않은 소속, 매핑 없는 ID, 중복 ID를 집계하지 않는다. 같은 그룹의 실제 소속이 2명이어도 요청 결과가 1명이면 1이다.
- 매핑 없는 ID는 결과와 count에서 제외하며, 모두 매핑이 없으면 group은 빈 배열이다. 미소속을 나타내는 가상 그룹이나 permission:null 항목을 만들지 않는다.
- 멤버 원장을 역조회하지 않고 group_membership을 기준으로 한다. 그룹을 생성한 뒤 멤버가 소속될 때만 매핑을 만든다. group_id NOT NULL + FK는 매핑 대상 그룹의 존재를 보장한다. 상세 응답은 변경하지 않는다.
- 멤버당 최대 한 소속이므로 결과 그룹도 최대 100개다. 결과를 임의로 절삭하지 않는다.

| 요청 사례 | 결과 |
| --- | --- |
| 같은 그룹의 매핑 2개 | group 항목 1개, members 2개, totalMemberCount 2 |
| 매핑 있는 ID 1개 + 매핑 없는 ID 1개 | group 항목 1개, members 1개, totalMemberCount 1 |
| 매핑 없는 ID만 요청 | group 빈 배열. null 멤버·가상 그룹 없음 |
| 같은 ID를 반복 요청 | members에 한 번만 반환·집계, 중복 WARN 로그 |

group 배열 길이는 그룹 수이며 멤버 수가 아니다. 각 totalMemberCount는 해당 항목의 members 배열 길이와 일치한다.

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

오류는 application/json이며 type/title/status/detail/instance/code를 포함한다. `code`는 [공통 코드표](../../../docs/common/api-conventions.md#pol-api-002)의 값이고, 같은 상태 안에서 소비자가 구분해야 하는 원인만 `type`에 고정 URN을 쓴다.

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

## 공통 오류 계약과 상태 코드

성공·오류 API 응답은 모두 `application/json`이다. 오류는 type/title/status/detail/instance/code 여섯 필드만 반환한다. 같은 HTTP 상태에는 두 서비스가 아래와 같은 code·의미를 사용하며, 업무 원인은 type URN으로 구분한다. 상태 코드는 HTTP 응답과 본문의 status가 일치해야 한다. 이 표는 공통 정의이며 각 API에서 실제 발생하는 상태는 다음 적용 표를 따른다.

| HTTP | 공통 code | 의미 |
| --- | --- | --- |
| 400 | `INVALID_PARAMETER` | 잘못된 요청 |
| 401 | `UNAUTHORIZED` | 인증 실패 |
| 403 | `FORBIDDEN` | 접근 권한 없음 |
| 404 | `RESOURCE_NOT_FOUND` | 리소스 없음 |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 Method |
| 406 | `NOT_ACCEPTABLE` | 지원하지 않는 응답 형식 |
| 409 | `RESOURCE_CONFLICT` | 리소스 충돌 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 미디어 타입 |
| 422 | `VALIDATION_FAILED` | 처리할 수 없는 요청 값 |
| 429 | `TOO_MANY_REQUESTS` | 요청 횟수 제한 초과 |
| 500 | `INTERNAL_SERVER_ERROR` | 서버 내부 오류 |
| 502 | `BAD_GATEWAY` | 게이트웨이 응답 오류 |
| 503 | `SERVICE_UNAVAILABLE` | 서비스 이용 불가 |
| 504 | `GATEWAY_TIMEOUT` | 게이트웨이 응답 시간 초과 |

| API | 성공 | 발생 가능한 오류 |
| --- | --- | --- |
| GET /api/groups/summary | 200 | 400, 403, 405, 406, 422, 500, 503 |
| GET /api/group/{memberId} | 200 | 400, 403, 404, 405, 406, 500, 503 |

그룹은 사용자 JWT를 검증하지 않으며 등록된 내부 호출자 키를 검사한다. 거부는 403이며 401·429·502·504는 이 서비스의 구현된 발생 경로가 아니다. 409·415 역시 GET 전용 조회에서 발생하지 않는다. 멤버를 통한 중계의 502/503/504는 멤버 경계가 생성하는 상태다.
