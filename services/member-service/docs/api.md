# 멤버 서비스 API 정의

| 항목 | 내용 |
| --- | --- |
| 목적 | 현재 HTTP 경로와 후속 업무 계약 소유 |
| 범위 | 사용자 진입점 http://127.0.0.1:8081 |
| 관련 코드 | infrastructure/configuration/, presentation/error/, application.yaml |

## 현재 제공 경로

| 방법·경로 | 호출 조건 | 응답 |
| --- | --- | --- |
| GET `/actuator/health` | 키 없음 | 상태 JSON |
| GET `/actuator/health/liveness` | 키 없음 | 생존 상태 |
| GET `/actuator/health/readiness` | 키 없음 | 자기 DB 포함 준비 상태 |
| GET `/v3/api-docs` | 사용자 인증 없음 | 멤버 OpenAPI JSON |
| GET `/swagger-ui.html`, `/swagger-ui/index.html` | 사용자 인증 없음 | 두 명세 선택 화면 |
| GET `/internal-docs/group` | 사용자 인증 없음 | 서버 키로 받은 그룹 참고 명세. no-store |
| GET `/api/members` | 사용자 인증 없음 | 멤버 요약 페이지 + 다중 소속 |
| GET `/api/member/{id}` | 사용자 인증 없음 | 기본 정보·감사 정보 + 소속. no-store |

그룹 명세 전달은 모든 프로필에 제공한다. 내부 서버 주소·브라우저 인증 요구를 제거하고 공개 업무 명세에서는 전달 컨트롤러를 제외한다. 그룹 연결 실패·시간 초과·5xx는 503 GROUP_SERVICE_UNAVAILABLE, 내부 키 거부·명세 계약 오류는 500 INTERNAL_ERROR다.

## MEMBER-001 공개 계약

2026-11-05 공개 API와 local 자동 시드를 구현하고 실제 MariaDB + fixture/테스트 HTTP 서버로 검증한다. 2단계 커밋 `e5a8c6b`를 확인했으며 3단계 커밋 완료 여부는 예약 실행 뒤 확인한다. [3단계 검증](../../../docs/common/member-query-stage-3-verification.md)을 기준으로 완료 범위를 구분한다. 전체 범위는 [통합 계획](../../../docs/common/member-query-plan.md), 공통 규칙은 [API 정책](../../../docs/common/api-conventions.md)을 따른다.

### GET /api/members

operationId는 `getMemberSummaries`, 응답 스키마는 `MemberSummaryPageResponse`다. 성공은 200 `application/json`.

| 파라미터 | 값이 없을 때 | 허용 범위·표현 |
| --- | --- | --- |
| `page` | 0 | ASCII 숫자만, 0~2147483647 |
| `size` | 20 | ASCII 숫자만, 1~2147483647. 범위 검증 후 `min(size, 100)` 적용 |
| `sort` | `name,asc` | 정확히 `name,asc` 또는 `name,desc` |

- `page`·`size`·`sort`는 각각 최대 한 번이다. 같은 값이어도 반복 전달하면 400 `VALIDATION_FAILED`로 거부한다. 임의로 첫 값 선택·중복 제거하지 않는다.
- 빈 값, 공백(앞뒤 공백 포함), 부호, 음수, 소수, 숫자 외 문자, 정수 범위 초과는 400이다. 생략과 빈 값은 다르다. 선행 0은 숫자로 파싱한다.
- `page.toLong() * effectiveSize.toLong() <= 2147483647`을 DB 호출 전에 검증한다. 예: `page=21474837&size=100`은 400이다. 허용 범위 안에서 마지막 페이지를 넘으면 200과 빈 `members`다.
- 원시 파라미터의 개수를 먼저 확인하고 DTO로 변환·검증한다. 자동 `Pageable` 바인딩의 값 보정에 의존하지 않는다. 기존 `spring.data.web.pageable` 설정 블록은 유지한다.
- 이름은 DB `utf8mb4_unicode_ci` 기준으로 정렬하며 내부 보조 정렬은 항상 `id ASC`다. `desc`에서도 ID는 ASC다. 고정 데이터에서 순서가 결정적이며, 서로 다른 요청 사이 데이터 변경까지 막는 스냅샷 보장은 하지 않는다.
- `totalElements`는 멤버 수다. 다중 소속으로 멤버 행·건수를 늘리지 않는다. `page.size`는 적용된 크기, `page.number`는 요청 페이지다. 전체 0건이면 `totalPages=0`이다.

요청 예: `/api/members?page=0&size=20&sort=name,asc`.

```json
{
  "members": [
    {
      "id": "tu3fgxd45k",
      "name": "user1",
      "groups": [
        { "id": "arutjf9348", "name": "개발팀", "permission": "Editor", "permissionName": "편집자" },
        { "id": "demo000002", "name": "프로젝트팀", "permission": "Viewer", "permissionName": "조회자" }
      ]
    },
    { "id": "k2m9x0p1q3", "name": "user3", "groups": [] }
  ],
  "page": { "size": 20, "number": 0, "totalElements": 2, "totalPages": 1 }
}
```

위 예시는 2명만 있는 데이터셋이며 전체 local 시드 목록의 복사본이 아니다. `members`, `page`, 항목의 `id`, `name`, `groups`, 각 그룹의 네 필드는 필수·비null이다. `permission`은 안정적인 코드 문자열이고 `permissionName`은 표시용 코드명이다. 목록에는 과금 정보·소속 시각·감사 정보를 요구하거나 노출하지 않는다.

### GET /api/member/{id}

operationId는 `getMemberDetail`, 스키마는 `MemberDetailResponse`다. 요청한 단수 경로를 유지한다. `id`는 `^[a-z0-9]{10}$`이며 형식 오류는 400, 멤버 없음은 404다. 두 경우 그룹 공급자를 호출하지 않는다. 성공은 200이며 `Cache-Control: no-store`를 사용한다.

```json
{
  "id": "tu3fgxd45k",
  "name": "user1",
  "email": "user1@example.invalid",
  "address": "가상 주소 1",
  "registeredDate": "2026-08-15T04:00:59.123456Z",
  "createdAt": "2026-08-15T04:00:59.123456Z",
  "createdBy": "seed",
  "groups": [
    {
      "id": "arutjf9348",
      "name": "개발팀",
      "permission": "Editor",
      "permissionName": "편집자",
      "joinedAt": "2026-08-15T04:00:00Z",
      "pricing": "Premium"
    },
    {
      "id": "demo000002",
      "name": "프로젝트팀",
      "permission": "Viewer",
      "permissionName": "조회자",
      "joinedAt": null,
      "pricing": null
    }
  ]
}
```

- `registeredDate`는 멤버 가입 시각, `createdAt`은 행 생성 시각, `createdBy`는 생성 주체다. 원래 기본 정보 요구를 충족하도록 상세에 포함한다. 가입 시각을 그룹 소속 시각으로 재사용하지 않는다.
- 멤버 기본 필드와 `groups`는 필수·비null이다. 그룹 핵심 네 필드는 목록과 동일하다. 상세의 `joinedAt`·`pricing` 키는 항상 있고 값은 null 가능하다. null은 선택 정보를 알 수 없음을 뜻하며 미소속은 `groups: []`다.
- UTC RFC 3339 `date-time`을 사용하고 DB의 마이크로초 정밀도를 보존한다. `yyyy-MM-dd HH:mm` 변환·KST 고정·초 이하 절삭은 하지 않는다. KST 표시가 필요하면 소비자가 변환한다.
- `pricing`은 요청서의 상세 과금 표시 요구를 유지하는 선택 정보다. 없어도 목록·상세 조회는 성공한다. 빈 문자열은 알려진 값이 아니므로 어댑터에서 null로 정규화한다. 알려지지 않은 비어 있지 않은 코드는 그대로 수용한다.
- 목록 `GroupMembershipSummaryResponse`와 상세 `GroupMembershipDetailResponse`는 공통 핵심 필드의 이름·타입·의미를 맞춘다. DTO 상속이나 공유 서비스 모듈을 요구하지 않는다. 내부 `MembershipView`를 각 용도에 투영한다.
- 권한·과금의 코드값은 OpenAPI에서 문자열로 표현하며 닫힌 enum으로 고정하지 않는다. 코드명·설명은 분기 기준이 아니다. 권한 자체의 정의·상태·설명은 그룹 정책이 소유한다.

### 오류와 공급자별 의미

| 상황 | 응답 |
| --- | --- |
| 입력 형식·반복·정수 범위·offset·정렬 위반 | 400 `VALIDATION_FAILED` |
| 상세 멤버 없음 | 404 `NOT_FOUND` |
| HTTP 연결 실패·시간 초과·그룹 5xx | 503 `GROUP_SERVICE_UNAVAILABLE` |
| 그룹 4xx·리다이렉트·잘못된 JSON·필수 계약 위반 | 500 `INTERNAL_ERROR` |
| fixture에 요청 ID가 누락되거나 fixture가 손상됨 | 500 `INTERNAL_ERROR` (시작 시 가능한 오류는 기동 실패) |

문법이 올바른 공개 파라미터라도 DB 조회 범위를 벗어나면 400이다. 다른 기존 API의 일반 `BAD_REQUEST` 매핑까지 일괄 변경하지 않는다. 멤버용 요청 검증 예외/바인딩 처리로 `VALIDATION_FAILED`를 보장하고 회귀 테스트를 유지한다.

```json
{
  "type": "about:blank",
  "title": "입력값 검증 실패",
  "status": 400,
  "detail": "요청 값이 허용된 조건을 충족하지 않습니다.",
  "instance": "/api/members",
  "code": "VALIDATION_FAILED"
}
```

404·500·503도 ProblemDetail + `code`이며 각 코드의 고정 문구를 따른다. 네 오류의 OpenAPI 예시는 코드에 반영했다. 실제 응답의 상태·코드 검사는 수행했으며, 공유 스냅샷 기준 전체 스키마 검증은 M-07에서 추가한다. 그룹 주소·본문·키·이메일·주소를 오류에 포함하지 않는다. 장애를 빈 그룹 배열로 바꾸지 않는다.

local fixture 모드의 성공은 실제 그룹 연동 성공을 뜻하지 않는다. 외부 공개 DTO에 개발용 모드 플래그를 추가하지 않고 실행 모드·검증 기록으로 구분한다. HTTP 모드는 [통신 정책](../../../docs/common/service-communication.md)의 지수 백오프·Full Jitter를 따르며 전체 API의 최대 지연을 고정 수치로 약속하지 않는다.
