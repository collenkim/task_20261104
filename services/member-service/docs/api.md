# 멤버 서비스 API 정의

| 항목 | 내용 |
| --- | --- |
| 분류 | 서비스 정의 |
| 상태 | 현재(0.4.0) |
| 목적 | 현재 HTTP 경로와 적용 중인 업무 계약 소유 |
| 범위 | 사용자 진입점 http://127.0.0.1:8081 |
| 관련 코드 | presentation/query/, presentation/error/, infrastructure/security/, infrastructure/web/, infrastructure/configuration/, application.yaml |

## 현재 제공 경로

모든 경로는 GET만 제공한다. HEAD·OPTIONS·POST·PUT·PATCH·DELETE·TRACE는 인증·핸들러보다 먼저 405 `METHOD_NOT_ALLOWED`와 `Allow: GET`으로 거부한다([POL-API-008](../../../docs/common/api-conventions.md#pol-api-008)).

| 경로 | 호출 조건 | 응답 |
| --- | --- | --- |
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | 인증 없음 | 상태 JSON(준비 상태는 자기 DB 포함) |
| `/v3/api-docs` | 인증 없음 | 멤버 OpenAPI JSON(데이터 없음) |
| `/swagger-ui.html`, `/swagger-ui/index.html`, `/swagger-ui/swagger-initializer.js` | 인증 없음 | Swagger 화면. local 프로필은 초기화 스크립트에 짧은 만료 토큰을 넣는다(no-store) |
| `/.well-known/oauth-protected-resource` | 인증 없음 | RFC 9728 보호 자원 메타데이터(자원 이름·scope·Bearer 헤더 방식) |
| `/api/members` | JWT, scope `member.read` | 멤버 요약 페이지 + 단일 소속 |
| `/api/member/{memberId}` | JWT, scope `member.read` | 기본 정보 + 소속. no-store |
| `/internal-docs/group` | 인증 없음, `task.group-proxy.enabled=true`일 때만 등록 | 서버 키로 받은 그룹 명세. 실행 주소는 멤버 중계, no-store |
| `/internal-api/group/api/groups/summary`, `/internal-api/group/api/group/{memberId}` | JWT, scope `group.read`, `task.group-proxy.enabled=true`일 때만 등록 | 그룹 조회 실행 중계. no-store |

- 인증은 RS256 JWT Bearer다. 발급자·대상·만료·사용 시작·subject를 검증하고 경로별 scope를 요구한다([POL-SEC-001](../../../docs/common/security.md#pol-sec-001)). 토큰이 없거나 검증에 실패하면 401 `UNAUTHORIZED`, scope가 모자라면 403 `FORBIDDEN`이다. 두 응답 모두 RFC 6750 `WWW-Authenticate: Bearer ...` 헤더를 보낸다.
- 그룹 중계와 그룹 명세 전달은 local 프로필만 켠다(기본 `false`). 다른 프로필에서는 경로가 없으므로 404다([POL-SEC-006](../../../docs/common/security.md#pol-sec-006)).
- 업무·중계 경로는 인증된 subject별 토큰 버킷(기본 용량 100, 초당 50 보충)으로 제한한다. 초과하면 429 `TOO_MANY_REQUESTS`와 `Retry-After`(초)를 보낸다. 서버 한 대 메모리 기준이며 분산 제한은 앞단 게이트웨이 책임이다([POL-SEC-007](../../../docs/common/security.md#pol-sec-007)).

<a id="member-001-공개-계약"></a>

## 공개 계약 (REVIEW-002 개정, 0.4.0)

0.4.0은 REVIEW-002에서 JWT 인증·GET 전용·오류 코드표·400/422 구분을 적용한 계약이다. 0.3.0(단일 `group`, 감사 필드 제외) 응답 본문은 바뀌지 않았다. 공통 규칙은 [API 정책](../../../docs/common/api-conventions.md), 승인 표와 변경 이유는 [개정 기록](api-review.md), 실행 결과는 [REVIEW-002 검증](../../../docs/verification/REVIEW-002/verification.md)에 있다. 아래 예제는 현재 코드의 응답이며 계약 테스트가 OpenAPI 스키마로 검사한다.

### GET /api/members

operationId는 `getMemberSummaries`, 응답 스키마는 `MemberSummaryPageResponse`다. 성공은 200 `application/json`.

| 파라미터 | 값이 없을 때 | 허용 범위·표현 |
| --- | --- | --- |
| `page` | 0 | ASCII 숫자만, 0~2147483647 |
| `size` | 20 | ASCII 숫자만, 1~2147483647. 범위 검증 후 `min(size, 100)` 적용 |
| `sort` | `name,asc` | 정확히 `name,asc` 또는 `name,desc` |

- 형식 위반은 400 `INVALID_PARAMETER`다: 반복 전달(같은 값 포함), 빈 값, 공백(앞뒤 공백 포함), 부호, 음수, 소수, 숫자 외 문자, int32 범위 초과, 빈 `sort`. 생략과 빈 값은 다르다. 선행 0은 숫자로 파싱한다. 첫 값 선택·중복 제거로 보정하지 않는다.
- 형식은 맞지만 처리할 수 없는 값은 422 `VALIDATION_FAILED`다: `size=0`, `page × 적용 size > 2147483647`(예: `page=21474837&size=100`), 허용하지 않는 정렬(`name`, `id,asc`, `name,ASC`, `name,asc ` 등).
- 두 검사 모두 DB·그룹 호출 전에 끝난다. 허용 범위 안에서 마지막 페이지를 넘으면 200과 빈 `members`다.
- 원시 파라미터의 개수를 먼저 확인하고 DTO로 변환·검증한다. 자동 `Pageable` 바인딩의 값 보정에 의존하지 않는다.
- 이름은 DB `utf8mb4_unicode_ci` 기준으로 정렬하며 내부 보조 정렬은 항상 `id ASC`다. `desc`에서도 ID는 ASC다. 서로 다른 요청 사이 데이터 변경까지 막는 스냅샷 보장은 하지 않는다.
- `page`는 클라이언트가 다음 요청을 만들 수 있도록 응답에 포함한다(사용자 결정). `page.size`는 적용된 크기, `page.number`는 요청 페이지, `totalElements`는 멤버 수다. 전체 0건이면 `totalPages=0`이다.

요청 예: `/api/members?page=0&size=20&sort=name,asc` (헤더 `Authorization: Bearer <토큰>`).

```json
{
  "members": [
    {
      "id": "tu3fgxd45k",
      "name": "user1",
      "group": "개발팀",
      "permission": "Editor"
    },
    {
      "id": "k2m9x0p1q3",
      "name": "user3",
      "group": null,
      "permission": null
    }
  ],
  "page": {
    "size": 20,
    "number": 0,
    "totalElements": 2,
    "totalPages": 1
  }
}
```

위 예시는 2명만 있는 데이터셋이며 local 시드 목록의 복사본이 아니다. `members`와 `page`는 필수·비null이다. 항목의 필수 키는 id/name/group/permission이며 group은 그룹명이다. 미소속이면 group과 permission은 함께 null이다. `permission`은 안정적인 코드 문자열이며 표시명은 응답에 포함하지 않는다. 목록에는 과금 정보·소속 시각·감사 정보를 노출하지 않는다.

### GET /api/member/{memberId}

operationId는 `getMemberDetail`, 스키마는 `MemberDetailResponse`다. 요청한 단수 경로를 유지한다. `memberId`는 `^[a-z0-9]{10}$`이며 형식 오류는 400 `INVALID_PARAMETER`, 멤버 없음은 404 `RESOURCE_NOT_FOUND`(type `urn:problem-type:member-not-found`)다. 두 경우 그룹 공급자를 호출하지 않는다. 성공은 200이며 `Cache-Control: no-store`를 사용한다.

```json
{
  "id": "tu3fgxd45k",
  "name": "user1",
  "email": "user1@example.invalid",
  "address": "가상 주소 1",
  "registeredDate": "2026-08-15 13:00",
  "group": {
    "id": "arutjf9348",
    "name": "개발팀",
    "permission": "Editor",
    "pricing": "Premium",
    "registeredDate": "2026-08-15 13:00"
  }
}
```

- 최상위 registeredDate는 멤버 가입일, group.registeredDate는 그룹 가입일이다. 두 값은 각자의 원본 시각에서 따로 변환한다(local 시드 `dj8fy4bewk`: 멤버 `2026-08-16 13:00`, 그룹 `2026-08-16 00:30`). DB 감사 정보 createdAt·createdBy는 공개하지 않는다.
- 상세는 id/name/email/address/registeredDate/group 여섯 키만 반환한다. 미소속은 group:null이다. 소속 객체는 id/name/registeredDate/pricing/permission이며 선택 정보 registeredDate·pricing의 null 허용은 유지한다.
- 업무 시각은 Asia/Seoul 기준 yyyy-MM-dd HH:mm 문자열이다. DB의 TIMESTAMP(6) 정밀도는 유지하고 응답에서만 초 이하를 생략한다. OpenAPI에 date-time 형식을 붙이지 않는다.
- `pricing`은 선택 정보다. 없어도 목록·상세 조회는 성공한다. 빈 문자열은 어댑터에서 null로 정규화한다. 알려지지 않은 비어 있지 않은 코드는 그대로 수용한다.
- 권한·과금의 코드값은 OpenAPI에서 문자열로 표현하며 닫힌 enum으로 고정하지 않는다. 코드명·설명은 분기 기준이 아니다. 권한 자체의 정의는 그룹 정책이 소유한다.

### 오류 응답

오류는 RFC 9457 ProblemDetail 여섯 필드(`type`, `title`, `status`, `detail`, `instance`, `code`)다. `code`는 [공통 코드표](../../../docs/common/api-conventions.md#pol-api-002)의 값 하나이며, 같은 상태 안에서 구분이 필요한 원인만 `type`에 고정 URN을 쓴다. 구분이 없으면 `type`은 `about:blank`다. OpenAPI에는 각 상태의 ProblemDetail 예제와 `code` 허용 값이 있다.

| 상황 | 상태·code | type |
| --- | --- | --- |
| 입력 형식·반복·ID 형식 위반 | 400 `INVALID_PARAMETER` | `about:blank` |
| 토큰 없음·서명·발급자·대상·만료·subject 위반 | 401 `UNAUTHORIZED` | `about:blank` |
| scope 부족 | 403 `FORBIDDEN` | `about:blank` |
| 상세 멤버 없음 | 404 `RESOURCE_NOT_FOUND` | `urn:problem-type:member-not-found` |
| 없는 경로 | 404 `RESOURCE_NOT_FOUND` | `about:blank` |
| GET 외 메서드 | 405 `METHOD_NOT_ALLOWED` | `about:blank` |
| 지원하지 않는 `Accept` | 406 `NOT_ACCEPTABLE` | `about:blank` |
| size 0·offset 초과·허용하지 않는 정렬 | 422 `VALIDATION_FAILED` | `about:blank` |
| 호출 수 제한 초과 | 429 `TOO_MANY_REQUESTS` + `Retry-After` | `about:blank` |
| 상세 미소속 전용 404를 제외한 그룹 4xx·리다이렉트·잘못된 JSON·필수 계약 위반, fixture 누락·손상 | 500 `INTERNAL_SERVER_ERROR` | `about:blank` |
| 그룹 연결 실패·시간 초과·그룹 5xx | 503 `SERVICE_UNAVAILABLE` | `urn:problem-type:group-service-unavailable` |

406 `NOT_ACCEPTABLE`은 사용자가 준 코드표에 없던 상태를 같은 규칙으로 추가한 값이다. 409·415는 GET 전용 조회 API에서 발생하지 않으므로 코드표에만 있고 이 API의 OpenAPI 응답에는 선언하지 않는다.

```json
{
  "type": "about:blank",
  "title": "처리할 수 없는 요청 값",
  "status": 422,
  "detail": "요청 데이터의 형식은 유효하지만 의미상 처리할 수 없습니다.",
  "instance": "/api/members",
  "code": "VALIDATION_FAILED"
}
```

그룹 주소·본문·키·토큰·이메일·주소를 오류에 포함하지 않는다. 그룹 장애를 미소속 null로 바꾸지 않는다. 공유 파일과 갱신 방법은 [계약 공유](contracts.md)를 따른다.

local fixture 모드의 성공은 실제 그룹 연동 성공을 뜻하지 않는다. HTTP 모드는 [통신 정책](../../../docs/common/service-communication.md)의 지수 백오프·Full Jitter를 따르며 전체 API의 최대 지연을 고정 수치로 약속하지 않는다.

## 그룹 Swagger 실행 중계

`/internal-api/group/api/groups/summary`와 `/internal-api/group/api/group/{memberId}`는 local에서 Swagger로 그룹 조회를 실행하는 기술 중계다. 입력·응답 계약은 [그룹 API](../../group-service/docs/api.md)가 소유한다. 멤버 업무 명세에서는 숨기며 `/internal-docs/group`의 servers로 제공한다. 전달 명세에는 내부 키 대신 `bearerAuth`(scope `group.read`)를 표시한다.

| 그룹 응답·상황 | 중계 응답 |
| --- | --- |
| 200 | 정의된 필드만 선별해 200 |
| 4xx(400·403·404·422 등) | 정의된 ProblemDetail 필드만 보존해 같은 상태 |
| 500 등 그 밖의 상태, 리다이렉트 | 502 `BAD_GATEWAY`, type `urn:problem-type:group-upstream-failure` |
| 잘못된 JSON | 502 `BAD_GATEWAY`, type `urn:problem-type:group-response-invalid-json` |
| 규격 위반 | 502 `BAD_GATEWAY`, type `urn:problem-type:group-response-contract-violation` |
| 매체 유형 오류 | 502 `BAD_GATEWAY`, type `urn:problem-type:group-response-unsupported-media-type` |
| 크기 초과(중계 1MiB, 명세 2MiB) | 502 `BAD_GATEWAY`, type `urn:problem-type:group-response-too-large` |
| 연결 불가·그룹 503 | 503 `SERVICE_UNAVAILABLE`, type `urn:problem-type:group-service-unavailable` |
| 응답 시간 초과·그룹 504 | 504 `GATEWAY_TIMEOUT`, type `urn:problem-type:group-service-timeout` |

업무 API는 그룹 시간 초과를 503으로 알리고(사용자에게 "그룹 정보를 지금 조회할 수 없음"), 중계는 게이트웨이 의미를 그대로 드러내기 위해 504로 구분한다. 500 `INTERNAL_SERVER_ERROR`는 멤버 자체 처리 오류에만 사용한다. 모든 중계 응답은 no-store다.
