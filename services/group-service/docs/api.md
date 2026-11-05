# 그룹 서비스 API 정의

| 항목 | 내용 |
| --- | --- |
| 소유 | group-service, GROUP-001 |
| 접근 | 내부 숫자 IP:포트. X-Internal-Service-Id + 해당 호출자의 X-Internal-Service-Key 필요 |
| 명세 | openapi.json, 멤버의 /internal-docs/group을 경유한 Swagger 명세·실행 |
| 시각 | Asia/Seoul의 yyyy-MM-dd HH:mm. 저장은 UTC TIMESTAMP(6) |

## GET /api/groups/summary

operationId: getGroupSummaries. 성공 200 application/json.

~~~text
/api/groups/summary?memberIds=tu3fgxd45k&memberIds=dj8fy4bewk
~~~

- memberIds는 반복 파라미터 1~100개, ID는 소문자 영숫자 10자리다.
- 누락·빈 값·공백·대문자·형식 위반·중복·101개 이상·콤마 결합은 400 VALIDATION_FAILED.
- group 배열은 그룹 ID 순, members는 멤버 ID 순이다. members에는 요청한 ID만 들어간다.
- totalMemberCount는 요청 ID 수와 관계없는 그룹 전체 인원(int64)이다.
- 미소속 ID는 결과 행이 없으며 모두 미소속이면 group은 빈 배열이다.
- 현재 멤버당 최대 한 소속이므로 결과 그룹도 최대 100개다. 결과를 임의로 절삭하지 않는다.

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

- registeredDate는 멤버가 이 그룹에 가입한 시각이다. 멤버 서비스의 가입일과 다르다.
- permission은 Viewer/Editor/Admin, pricing은 Free/Advanced/Premium의 code다. 표시명·설명·코드 상태를 응답에 넣지 않는다.
- 0건은 404 GROUP_MEMBERSHIP_NOT_FOUND, 1건은 200, 2건 이상이면 예외·500 GROUP_MEMBERSHIP_AMBIGUOUS와 ERROR 로그다.
- DB UNIQUE(member_id)로 복수 입력을 먼저 차단한다. 다건 조회는 별도 방어 규칙이며 첫 행을 선택하지 않는다.
- 상세는 Cache-Control: no-store다.

## 오류와 기타 경로

오류는 application/problem+json이며 type/title/status/detail/instance/code를 포함한다.

| 상황 | HTTP / code |
| --- | --- |
| 입력 오류 | 400 VALIDATION_FAILED |
| 내부 호출 인증 실패 | 403 INTERNAL_ACCESS_DENIED |
| 상세 미소속 | 404 GROUP_MEMBERSHIP_NOT_FOUND |
| 없는 경로 | 404 NOT_FOUND |
| 다건 소속 조회 | 500 GROUP_MEMBERSHIP_AMBIGUOUS |
| 저장 코드·기타 내부 오류 | 500 INTERNAL_ERROR |
| DB 연결/가용성 실패 | 503 SERVICE_UNAVAILABLE |

GET/HEAD health는 키 없이 접근한다. /error는 오류 처리 경로다. /v3/api-docs에는 내부 인증이 필요하며 그룹 Swagger UI는 제공하지 않는다. 405/406 등은 [공통 API 규칙](../../../docs/common/api-conventions.md)을 따른다.

X-Request-Id는 허용된 값이면 이어 사용하고 없거나 잘못되면 생성한다. 두 서비스의 요청·업무·의존 호출 로그를 연결한다. 키·본문·실제 멤버 ID는 로그에 남기지 않는다.

기존 /internal/group-memberships 계약은 GROUP-001에서 위 두 API로 대체한다. 멤버 목록은 summary 한 번, 상세는 detail 한 번을 호출한다. 그룹 5xx는 멤버 503이며 미소속 전용 404만 빈 groups로 바꾼다.

## 멤버 경유 Swagger 실행

멤버 Swagger에서 그룹 서비스를 선택하고 Try it out / Execute를 사용한다. 브라우저는 /internal-api/group 아래의 같은 경로를 호출하고 멤버 서버가 내부 키를 추가한다. 그룹 원본 OpenAPI·응답·호스트 포트 정책은 유지한다. 이 기술 중계는 그룹 400/404/500/503과 표준 오류 본문을 유지하므로 실제 그룹 결과를 확인할 수 있다.
