# API 규칙

| 항목 | 내용 |
| --- | --- |
| 분류 | 공통 기준 |
| 상태 | 현재(REVIEW-002 개정) |
| 목적 | 두 서비스의 HTTP API가 같은 형식으로 동작하게 한다 |
| 범위 | 모든 서비스의 HTTP API(`/actuator/health`, API 문서 경로 포함). 사용자에게 공개되는 API는 멤버 서비스뿐이고, 그룹 API는 멤버 서비스만 호출하는 내부 API다([ADR-005](decisions/ADR-005-member-edge-group-internal.md)) |
| 관련 코드 | `services/*/src/main/kotlin/task/*/presentation/error/`, `infrastructure/web/GetOnlyMethodFilter.kt`, `infrastructure/configuration/OpenApiConfiguration.kt`, `application.yaml`(`spring.data.web.pageable`) |

## POL-API-001 API 버전

- 경로·헤더·미디어 타입에 버전을 두지 않는다(예: `/v1/...`를 쓰지 않음).
- 호환되지 않는 변경은 소비 서비스와 함께 바꾼다. 계약 호환성은 OpenAPI 스냅샷 비교로 검증한다([POL-API-006](#pol-api-006-공유-계약과-스냅샷)).
- OpenAPI 문서의 필수 항목 `info.version`은 서비스가 관리한다(현재 멤버 0.4.0, 그룹 0.2.0). 명세 문서 버전이며 API 경로·헤더 버전과 다르다([OpenAPI 규격](https://spec.openapis.org/oas/v3.1.0#info-object)).

<a id="pol-api-002"></a>

## POL-API-002 오류 응답

애플리케이션의 오류 응답은 RFC 9457 ProblemDetail(`application/problem+json`) 다섯 필드에 `code`를 더한 여섯 필드다.

```json
{
  "type": "urn:problem-type:member-not-found",
  "title": "멤버 없음",
  "status": 404,
  "detail": "요청한 멤버가 없습니다.",
  "instance": "/api/member/missing001",
  "code": "RESOURCE_NOT_FOUND"
}
```

- `code`는 아래 코드표의 값 하나이며 상태 코드마다 하나다. 두 서비스가 같은 목록(`ErrorCode`)을 쓴다. 클라이언트는 `status`와 `code`로 분기한다.
- 같은 상태 안에서 소비자가 구분해야 하는 원인은 `code`를 늘리지 않고 `type`에 고정 URN(`urn:problem-type:<원인>`)을 쓴다(`ProblemType`). 구분이 필요 없으면 `about:blank`다. 원인 URN은 서비스가 소유하며 `title`·`detail`은 그 원인의 문구다.
- `title`은 `codeName`, 기본 `detail`은 `description`이다. 설명은 사람이 읽는 값이므로 클라이언트 분기 조건으로 사용하지 않는다.
- 처리되지 않은 예외(500)는 내부 메시지·스택을 넣지 않는다. 고정 문구와 `INTERNAL_SERVER_ERROR`만 넣는다.
- 내부 주소·그룹 응답 본문·키·토큰·이메일·주소를 오류 응답에 넣지 않는다.
- 필터(인증·메서드·요청 제한)가 쓰는 오류도 같은 여섯 필드다(`ProblemResponseWriter`). MVC 예외는 `ProblemDetailsExceptionHandler`가 매핑한다.
- OpenAPI에는 각 작업이 `@ProblemResponses`로 선언한 상태마다 `application/problem+json` 스키마와 예제 하나를 생성한다. `code` 허용 값은 코드표에서 채운다.

| status | code | codeName | 쓰임 |
| --- | --- | --- | --- |
| 400 | `INVALID_PARAMETER` | 잘못된 요청 | 형식 위반: 숫자 아님·빈 값·공백·반복·필수 누락·ID 패턴 위반·콤마 결합 |
| 401 | `UNAUTHORIZED` | 인증 실패 | 멤버: 토큰 없음·검증 실패 |
| 403 | `FORBIDDEN` | 접근 권한 없음 | 멤버: scope 부족. 그룹: 내부 호출 거부(type `internal-access-denied`) |
| 404 | `RESOURCE_NOT_FOUND` | 리소스 없음 | 없는 경로(`about:blank`), 업무 대상 없음(type으로 구분) |
| 405 | `METHOD_NOT_ALLOWED` | 지원하지 않는 Method | GET 외 메서드([POL-API-008](#pol-api-008)) |
| 406 | `NOT_ACCEPTABLE` | 지원하지 않는 응답 형식 | 지원하지 않는 `Accept`. 사용자 표에 없던 상태를 같은 규칙으로 추가 |
| 409 | `RESOURCE_CONFLICT` | 리소스 충돌 | 코드표 예약. 현재 GET 조회 API에서는 발생하지 않음 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | 지원하지 않는 미디어 타입 | 코드표 예약. 현재 요청 본문이 없어 발생하지 않음 |
| 422 | `VALIDATION_FAILED` | 처리할 수 없는 요청 값 | 형식은 맞지만 의미상 처리 불가: size 0·offset 초과·허용하지 않는 정렬·memberIds 100개 초과 |
| 429 | `TOO_MANY_REQUESTS` | 요청 횟수 제한 초과 | 멤버: subject별 제한 초과 + `Retry-After` |
| 500 | `INTERNAL_SERVER_ERROR` | 서버 내부 오류 | 자체 처리 오류·원격 계약 위반(업무 API) |
| 502 | `BAD_GATEWAY` | 게이트웨이 응답 오류 | 멤버 중계·명세 전달의 원격 응답 오류(type으로 원인 구분) |
| 503 | `SERVICE_UNAVAILABLE` | 서비스 이용 불가 | DB·그룹 서비스 이용 불가 |
| 504 | `GATEWAY_TIMEOUT` | 게이트웨이 응답 시간 초과 | 멤버 중계·명세 전달의 원격 시간 초과 |

- 목록에 없는 상태 코드는 4xx면 `INVALID_PARAMETER`, 그 밖은 `INTERNAL_SERVER_ERROR`로 둔다.
- 409·415는 코드표에 두되 이를 만들 경로가 없으므로 OpenAPI 응답에 선언하지 않는다. 미구현 상태를 선언하지 않는 것이 [응답 계약 규칙](#pol-api-007-정의된-응답만-노출)과 맞다.

| 서비스 | type URN | status | 상황 |
| --- | --- | --- | --- |
| 멤버 | `urn:problem-type:member-not-found` | 404 | 상세 멤버 없음 |
| 멤버 | `urn:problem-type:group-service-unavailable` | 503 | 그룹 연결 실패·시간 초과·5xx(업무 API), 그룹 503(중계) |
| 멤버 | `urn:problem-type:group-service-timeout` | 504 | 중계·명세 전달 시간 초과 |
| 멤버 | `urn:problem-type:group-response-invalid-json` | 502 | 중계 응답 JSON 손상·중복 키·뒤따르는 토큰 |
| 멤버 | `urn:problem-type:group-response-contract-violation` | 502 | 중계 응답 필수·타입·값 규격 위반 |
| 멤버 | `urn:problem-type:group-response-unsupported-media-type` | 502 | 중계 응답 Content-Type 오류 |
| 멤버 | `urn:problem-type:group-response-too-large` | 502 | 중계 응답 크기 초과 |
| 멤버 | `urn:problem-type:group-upstream-failure` | 502 | 그룹 서버 실패·리다이렉트·명세 접근 실패 |
| 그룹 | `urn:problem-type:group-membership-not-found` | 404 | 상세 미소속 |
| 그룹 | `urn:problem-type:group-membership-ambiguous` | 500 | 다건 소속 조회 |
| 그룹 | `urn:problem-type:internal-access-denied` | 403 | 내부 호출 키·호출자·헤더·IP 범위 거부 |

<a id="pol-api-003"></a>

## POL-API-003 입력 검증과 페이지네이션

| 항목 | 값 |
| --- | --- |
| 요청 파라미터 | `page`(0부터), `size`, `sort` |
| 기본 크기 | 20 (`spring.data.web.pageable.default-page-size`) |
| 최대 크기 | 100 (`max-page-size`). 더 큰 값을 요청하면 100으로 맞춘다 |
| 응답 형식 | 목록 키(서비스 계약 소유, 멤버: `members`) + 페이지 메타데이터 `page{size, number, totalElements, totalPages}` |

- 형식 위반은 400 `INVALID_PARAMETER`, 형식은 맞지만 의미상 처리할 수 없는 값은 422 `VALIDATION_FAILED`다(사용자 결정). 검증은 DB·원격 호출 전에 끝낸다.
  - 400: 숫자 아님, 빈 값, 공백, 부호, 정수 범위 초과, 반복 파라미터, 필수 누락, ID 패턴 위반, 콤마 결합.
  - 422: `size=0`, offset(`page × size`) 상한 초과, 허용하지 않는 정렬, `memberIds` 100개 초과.
- page/size/sort는 각각 한 번만 받는다. 생략에만 기본값을 적용하고 size의 범위 검증 후 최대 크기를 적용한다. offset은 Long으로 계산한 뒤 JPA Integer 범위 안인지 검증한다. 정확한 경계는 서비스 API 문서가 소유한다.
- `page` 메타데이터는 클라이언트가 다음 요청을 만들 수 있도록 응답에 둔다(사용자 결정). Spring 내부 PageImpl 직렬화 결과는 외부 계약으로 사용하지 않고 명시적 응답 DTO로 반환한다. application 계층에 Pageable/Page를 노출하지 않는다.
- 제한 없는 전체 목록 API는 만들지 않는다.

## POL-API-004 API 문서

- springdoc-openapi가 각 서비스의 코드에서 OpenAPI 3.1 명세를 생성한다.
- 멤버 `/v3/api-docs`는 인증 없이 제공한다(데이터 없음). 명세에는 `bearerAuth`(HTTP bearer, JWT) 스킴과 전역 보안 요구를 둔다.
- 멤버 `/swagger-ui.html`에서 멤버 명세를 연다. local 프로필은 초기화 스크립트에 짧은 만료 토큰을 넣어 자동 인증한다([POL-SEC-005](security.md#pol-sec-005)). 그룹 중계가 켜진 local에서만 그룹 명세를 선택할 수 있다.
- 그룹 `/v3/api-docs`는 내부 키가 필요하다. 원본에는 internalServiceId와 internalServiceKey의 AND 헤더 스킴을 정의한다. 그룹 Swagger UI는 제공하지 않는다.
- 멤버 GET `/internal-docs/group`(local 전용)은 고정된 그룹 명세만 조회하고 `Cache-Control: no-store`로 반환한다. 이 기술 컨트롤러는 `@Hidden`으로 멤버 업무 명세에서 제외한다. 전달본은 내부 servers·내부 키 스킴을 제거하고 서버 주소를 `/internal-api/group`, 보안을 `bearerAuth`(scope `group.read`)로 바꾼다. 그룹 원본 계약은 그룹이 소유한다.
- 사용자에게 내부 키를 입력받거나 전달하지 않는다. 사용자 지정 URL을 전달하는 범용 프록시는 제공하지 않는다.
- 명세 전달의 원격 실패는 [중계 표](#그룹-api-실행-중계)와 같다. 멤버 자기 명세와 readiness는 계속 제공한다.

## POL-API-005 시각·코드 계약

- 업무 API 시각은 Asia/Seoul의 yyyy-MM-dd HH:mm 문자열이다. DB에는 UTC TIMESTAMP(6)으로 마이크로초를 보존하고 응답 mapper에서만 분 단위로 표시한다. OpenAPI는 string/pattern/길이/예제로 정의하며 format: date-time은 사용하지 않는다. HTTP 표준 헤더·실행 로그·AI 원본 시각에는 적용하지 않는다.
- 가입 시각·소속 시각·생성 시각은 의미를 구분한다. 필드 이름·필수/선택·null 의미는 서비스 계약에 고정한다.
- 멤버는 그룹 하나에만 소속한다. 단일 관계는 객체(상세 `group`) 또는 값(목록 `group` 이름)으로 반환하고, 미소속은 null이다. 배열(`groups[]`)로 반환하지 않는다. 장애를 null로 바꾸지 않는다.
- 외부 소유 코드의 정의를 소비자 enum으로 복제하지 않는다. 문자열 코드와 표시명을 분리하고 알 수 없는 유효 코드를 수용한다. 코드명은 분기 조건으로 사용하지 않는다.

## POL-API-006 공유 계약과 스냅샷

- 코드·서비스 API 정의서를 검토한 뒤 springdoc으로 OpenAPI를 생성한다. 생성 명세를 저장소에 보관하고, 손으로 따로 수정하지 않는다.
- operationId·스키마명·입력 범위·필수/null·오류·성공 예제를 고정한다. 상세 시그니처 공유는 OpenAPI로 하며 공유 런타임 DTO 모듈을 만들지 않는다.
- 환경별 서버 주소와 테스트 전용 API가 스냅샷에 섞이지 않도록 [테스트 규칙](testing.md#pol-test-007-openapi-스냅샷과-계약)을 적용한다.
- 일반 빌드는 읽기 전용으로 비교한다. 갱신은 별도 명령이며 필드 삭제·필수화·타입 변경은 소비자 영향 검토를 거친다. 스냅샷 일치와 실제 제공자 적합성 검증을 구분한다.

### 그룹 API 실행 중계

멤버 GET `/internal-api/group/api/groups/summary`와 `/internal-api/group/api/group/{memberId}`만 그룹으로 전달한다. local 프로필(`task.group-proxy.enabled=true`)에서만 등록하며 JWT scope `group.read`가 필요하다([POL-SEC-006](security.md#pol-sec-006)). 내부 키·ID는 서버가 주입하며 원격 주소나 임의 경로는 사용자에게 받지 않는다. 기술 중계는 `@Hidden`이며 멤버 업무 스냅샷에 포함하지 않는다.

중계는 성공 JSON과 그룹 4xx의 표준 6필드 ProblemDetail을 모든 깊이에서 선별한다. 추가 필드는 제거하고 필수 누락·타입·값 위반은 거부한다. 응답 상한은 1MiB(명세 2MiB)이며 내부 헤더는 전달하지 않고 no-store를 적용한다.

| 원격 상황 | 중계 응답 |
| --- | --- |
| 손상 JSON·중복 키·뒤따르는 토큰 | 502 `BAD_GATEWAY`, type `group-response-invalid-json` |
| 필수·타입·값 규격 위반 | 502 `BAD_GATEWAY`, type `group-response-contract-violation` |
| 잘못된 Content-Type | 502 `BAD_GATEWAY`, type `group-response-unsupported-media-type` |
| 본문 상한 초과 | 502 `BAD_GATEWAY`, type `group-response-too-large` |
| 그룹 서버 실패(500 등)·리다이렉트·명세 접근 실패 | 502 `BAD_GATEWAY`, type `group-upstream-failure` |
| 연결 불가·그룹 503 | 503 `SERVICE_UNAVAILABLE`, type `group-service-unavailable` |
| 연결/헤더/본문 시간 초과·그룹 504 | 504 `GATEWAY_TIMEOUT`, type `group-service-timeout` |
| 멤버 자체 내부 오류 | 500 `INTERNAL_SERVER_ERROR` |

멤버 업무 조합 API는 그룹 시간 초과도 503으로 알린다. 중계는 게이트웨이 의미를 드러내기 위해 504로 구분한다.

## POL-API-007 정의된 응답만 노출

상태: 사용자 확정 요구. 응답 DTO 명시·중계 필드 선별·계약 테스트의 정의 외 필드 거부로 적용했다. 모든 출력 경계의 강제 수단을 전수 검사한 기록은 아직 없으므로 전체 완료로 표시하지 않는다.

- 목록·상세·중첩 항목·page·그룹 중계에는 서비스별로 확정한 응답 필드만 노출한다. Entity·내부 모델·원격 JSON의 확장 필드를 자동으로 전달하지 않는다.
- 응답 DTO와 OpenAPI의 객체별 필드 목록·타입·필수·null 규칙을 맞춘다. 실제 응답 키 집합과 계약을 대조하고 정의 외 필드를 넣은 실패 사례를 검사한다.
- 내부 소비자는 호환성 정책에 따라 알 수 없는 원격 속성을 무시할 수 있다. 이 동작이 공개 응답 필드의 추가 승인을 뜻하지 않는다.
- enum의 코드명·상태·설명은 내부 모델에 보존한다. 업무 API는 소유 계약에서 정한 code 필드만 반환한다.

<a id="pol-api-008"></a>

## POL-API-008 GET 전용·URI·조회 입력 안전성

- 제공하는 API는 모두 GET이다. HEAD·OPTIONS·POST·PUT·PATCH·DELETE·TRACE 등은 가장 앞쪽 필터(요청 추적 다음)에서 405 `METHOD_NOT_ALLOWED`와 `Allow: GET`으로 거부한다(사용자 결정). 인증·내부 키 검사·핸들러·원격 호출보다 먼저 판단하므로 Spring MVC의 암묵 HEAD·OPTIONS 처리가 조회를 실행하지 않는다. 오류 디스패치에도 같은 규칙을 적용한다.
- 경로·쿼리는 RFC 3986을 따른다. 경로 변수는 `{memberId}` 하나이며 ID 패턴(`[a-z0-9]{10}`)으로 검증한다. 반복 쿼리는 같은 이름을 여러 번 쓰고(`memberIds=a&memberIds=b`) 콤마 결합을 받지 않는다. 그룹 중계는 사용자 입력을 URI 템플릿 변수로 넣어 값 단위로 인코딩하므로 `/?&+{}` 같은 문자를 경로·쿼리 구조로 해석하지 않는다(`GroupApiDocsTests`). 업무 API의 그룹 호출은 DB에서 읽은 패턴 일치 ID만 쓴다.
- SQL 인젝션 방어: 조회는 JPA 바인딩 파라미터(`:id`, `:ids`)와 고정 JPQL만 쓰며 입력을 문자열로 이어 붙이지 않는다. 정렬은 허용 목록(`name,asc`·`name,desc`)을 고정 `Sort`로 바꾸고 입력 필드명을 쿼리에 쓰지 않는다. ID는 패턴 검증 후에만 DB에 전달한다.
- 구현: 각 서비스 `infrastructure/web/GetOnlyMethodFilter.kt`, `WebFilterConfiguration.kt`. 검증: 두 서비스 `CommonPolicyTests`(경로 × 메서드 전수), 멤버 `MemberQueryApiTests`(HEAD·OPTIONS에서 조회·그룹 호출 없음).
