# API 규칙

| 항목 | 내용 |
| --- | --- |
| 목적 | 두 서비스의 HTTP API가 같은 형식으로 동작하게 한다 |
| 범위 | 모든 서비스의 HTTP API(`/actuator/health`, API 문서 경로 포함). 사용자에게 공개되는 API는 멤버 서비스뿐이고, 그룹 API는 멤버 서비스만 호출하는 내부 API다([ADR-005](decisions/ADR-005-member-edge-group-internal.md)) |
| 관련 코드 | `services/*/src/main/kotlin/task/*/presentation/error/`, `infrastructure/configuration/OpenApiConfiguration.kt`, `application.yaml`(`spring.data.web.pageable`) |

## POL-API-001 API 버전

- 경로·헤더·미디어 타입에 버전을 두지 않는다(예: `/v1/...`를 쓰지 않음).
- 호환되지 않는 변경은 소비 서비스와 함께 바꾼다. 계약 호환성 검증 방식은 기능 개발 단계에서 정한다.
- OpenAPI 문서의 필수 항목 `info.version`은 서비스가 관리한다(현재 멤버 0.2.0, 그룹 0.1.0). 명세 문서 버전이며 API 경로·헤더 버전과 다르다([OpenAPI 규격](https://spec.openapis.org/oas/v3.1.0#info-object)).

## POL-API-002 오류 응답

애플리케이션의 오류 응답은 RFC 9457 ProblemDetail(`application/problem+json`)에 `code`를 더한다.

```json
{
  "type": "about:blank",
  "title": "대상 없음",
  "status": 404,
  "detail": "요청한 경로나 자원을 찾을 수 없습니다.",
  "instance": "/members",
  "code": "NOT_FOUND"
}
```

- `code`는 enum의 명시적 `code` 속성이며 클라이언트가 분기에 쓰는 안정적인 값이다. `title`은 `codeName`, 기본 `detail`은 `description`이다. MVC 입력 검증 등 구체적인 설명이 이미 있으면 그 `detail`을 유지한다. 설명은 사람이 읽는 값이므로 클라이언트 분기 조건으로 사용하지 않는다.
- 처리되지 않은 예외(500)는 내부 메시지·스택을 넣지 않는다. 고정 문구와 `INTERNAL_ERROR`만 넣는다.
- 그룹 내부 키 거부는 403 `INTERNAL_ACCESS_DENIED`와 같은 ProblemDetail 형식이다. 사용자 Bearer 인증과 관련 헤더는 사용하지 않는다.

| code | status | codeName | description |
| --- | --- | --- | --- |
| `BAD_REQUEST` | 400 | 잘못된 요청 | 요청 형식이 올바르지 않습니다. |
| `VALIDATION_FAILED` | 400 | 입력값 검증 실패 | 요청 값이 허용된 조건을 충족하지 않습니다. |
| `UNAUTHORIZED` | 401 | 인증 필요 | 인증이 필요합니다. |
| `FORBIDDEN` | 403 | 접근 거부 | 접근 권한이 없습니다. |
| `NOT_FOUND` | 404 | 대상 없음 | 요청한 경로나 자원을 찾을 수 없습니다. |
| `METHOD_NOT_ALLOWED` | 405 | 허용되지 않은 요청 방법 | 지원하지 않는 HTTP 메서드입니다. |
| `NOT_ACCEPTABLE` | 406 | 응답 형식 불일치 | 요청한 형식으로 응답할 수 없습니다. |
| `CONFLICT` | 409 | 상태 충돌 | 현재 자원의 상태와 요청이 충돌합니다. |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 본문 형식 | 요청 본문의 형식을 지원하지 않습니다. |
| `INTERNAL_ERROR` | 500 | 내부 처리 오류 | 요청을 처리하지 못했습니다. |
| `SERVICE_UNAVAILABLE` | 503 | 서비스 이용 불가 | 일시적으로 요청을 처리할 수 없습니다. |

- 목록에 없는 상태 코드는 4xx면 `BAD_REQUEST`, 5xx면 `INTERNAL_ERROR`로 둔다.
- 멤버의 `GROUP_SERVICE_UNAVAILABLE`: 503 / 그룹 서비스 이용 불가 / 그룹 정보를 일시적으로 조회할 수 없습니다.
- 그룹의 `INTERNAL_ACCESS_DENIED`: 403 / 내부 호출 거부 / 내부 서비스 호출 키를 확인할 수 없습니다.
- 위 서비스 전용 코드는 해당 서비스 enum에만 둔다. 표의 일반 코드는 각 서비스가 유지한다.
- 내부 주소·그룹 응답 본문·토큰을 사용자 오류 응답에 넣지 않는다.
- 구현: `ProblemDetailsExceptionHandler`(MVC 예외), `ErrorCode`. 멤버 공개 조회 계약은 `type`을 필수로 정의하므로 `MemberProblemDetail`에서 기본 `about:blank`를 명시한다. 그룹 오류 직렬화는 이번 멤버 작업에서 변경하지 않는다.

## POL-API-003 페이지네이션

| 항목 | 값 |
| --- | --- |
| 요청 파라미터 | `page`(0부터), `size`, `sort` |
| 기본 크기 | 20 (`spring.data.web.pageable.default-page-size`) |
| 최대 크기 | 100 (`max-page-size`). 더 큰 값을 요청하면 100으로 맞춘다 |
| 응답 형식 | 페이지 메타데이터 `page{size, number, totalElements, totalPages}` 유지. 목록 키는 서비스 계약이 소유(MEMBER-001: `members`) |

- 기존 `spring.data.web.pageable` 블록(기본 20·최대 100·via-dto)과 기존 공통 정책 테스트는 유지한다. 현재 테스트 API는 Pageable을 사용한다.
- MEMBER-001 업무 API는 원시 파라미터 개수와 형식을 검증한 요청 DTO를 Query로 변환하고, 명시적 응답 DTO를 반환한다. 작업 폴더 구현·검증 상태는 [3단계 기록](member-query-stage-3-verification.md)에 있다. application에 Pageable/Page를 노출하지 않는다. 목록 키를 `members`로 정한 것은 서비스별 명시적 계약이며 기존 API의 `content`를 일괄 변경하지 않는다.
- page/size/sort는 각각 한 번만 받는다. 공백·빈 값·음수·정수 범위 초과·반복은 400이다. 생략에만 기본값을 적용하고 size의 범위 검증 후 최대 크기를 적용한다. offset은 Long으로 계산한 뒤 JPA Integer 범위 안인지 검증한다. 정확한 경계는 서비스 API 문서가 소유한다.
- 제한 없는 전체 목록 API는 만들지 않는다. Spring 내부 PageImpl 직렬화 결과를 외부 계약으로 사용하지 않는다.

## POL-API-004 API 문서

- springdoc-openapi가 각 서비스의 코드에서 OpenAPI 3 명세를 생성한다. 명세 버전은 서비스가 소유한다(현재 멤버 0.2.0, 그룹 0.1.0).
- 멤버 `/swagger-ui.html`에서 멤버·그룹 명세를 선택한다. 두 서비스 조회 모두 Try it out으로 실행 가능하며 그룹 요청은 멤버의 고정 중계 경로를 통과한다. 선택 시 UI 인스턴스를 새 설정으로 만든다.
- 멤버 `/v3/api-docs`는 사용자 인증 없이 제공하며 사용자 Bearer 보안 스킴이 없다.
- 그룹 `/v3/api-docs`는 내부 키가 필요하다. 원본에는 internalServiceId와 internalServiceKey의 AND 헤더 스킴을 정의한다.
- 멤버 GET `/internal-docs/group`은 고정된 그룹 명세만 조회하고 `Cache-Control: no-store`로 반환한다. 이 기술 컨트롤러는 `@Hidden`으로 멤버 업무 명세에서 제외한다.
- 브라우저 전달본은 내부 servers·인증 요구·securitySchemes를 제거하고 서버 주소를 /internal-api/group으로 지정한다. 실제 그룹 원본 계약은 그룹이 소유한다.
- 그룹 Swagger UI는 제공하지 않는다. 외부 온라인 validator와 브라우저의 그룹 직접 요청도 사용하지 않는다.
- 명세 전달에는 내부 키가 서버에서 추가되며 사용자에게 키를 입력받거나 전달하지 않는다. 사용자 지정 URL을 전달하는 범용 프록시는 제공하지 않는다.
- 그룹 명세 장애는 503, 내부 키 거부·명세 계약 오류는 500이다. 멤버 자기 명세와 readiness는 계속 제공한다.
- 위 명세 노출 정책은 local과 기본 프로필에 동일하게 적용한다. local 업무 조회는 임시 그룹 fixture를 기본으로 사용하지만 그룹 명세 전달은 실제 HTTP를 유지한다.
- 멤버 업무 명세는 목록·상세 경로와 스키마를 제공한다. 그룹은 summary·단일 상세 명세를 제공한다. 각 서비스 docs/api.md·구현·스냅샷을 함께 갱신한다.

## POL-API-005 시각·배열·코드 계약

- 업무 API 시각은 Asia/Seoul의 yyyy-MM-dd HH:mm 문자열이다. DB에는 UTC TIMESTAMP(6)으로 마이크로초를 보존하고 응답 mapper에서만 분 단위로 표시한다. OpenAPI는 string/pattern/길이/예제로 정의하며 format: date-time은 사용하지 않는다. HTTP 표준 헤더·실행 로그·AI 원본 시각에는 적용하지 않는다.
- 가입 시각·소속 시각·생성 시각은 의미를 구분한다. 필드 이름·필수/선택·null 의미는 서비스 계약에 고정한다.
- 다중 관계는 배열이다. 관계가 없으면 빈 배열이며 선택 부가 정보가 없으면 정의된 null 규칙을 따른다. 장애를 빈 배열로 바꾸지 않는다.
- 외부 소유 코드의 정의를 소비자 enum으로 복제하지 않는다. 문자열 코드와 표시명을 분리하고 알 수 없는 유효 코드를 수용한다. 코드명은 분기 조건으로 사용하지 않는다.

## POL-API-006 공유 계약과 스냅샷

- 코드·서비스 API 정의서를 검토한 뒤 springdoc으로 OpenAPI를 생성한다. 생성 명세를 저장소에 보관하고, 손으로 따로 수정하지 않는다.
- operationId·스키마명·입력 범위·필수/null·오류·성공 예제를 고정한다. 상세 시그니처 공유는 OpenAPI로 하며 공유 런타임 DTO 모듈을 만들지 않는다.
- 환경별 서버 주소와 테스트 전용 API가 스냅샷에 섞이지 않도록 [테스트 규칙](testing.md#pol-test-007-openapi-스냅샷과-계약)을 적용한다.
- 일반 빌드는 읽기 전용으로 비교한다. 갱신은 별도 명령이며 필드 삭제·필수화·타입 변경은 소비자 영향 검토를 거친다. 스냅샷 일치와 실제 제공자 적합성 검증을 구분한다.

GROUP-001 그룹 전용 코드: GROUP_MEMBERSHIP_NOT_FOUND(404), GROUP_MEMBERSHIP_AMBIGUOUS(500). 권한 응답은 code만 노출하고 표시명은 그룹 enum 내부에서 관리한다.

### 그룹 API 실행 중계

멤버 GET /internal-api/group/api/groups/summary와 /internal-api/group/api/group/{memberId}만 그룹으로 전달한다. 내부 키·ID는 서버가 주입하며 원격 주소나 임의 경로는 사용자에게 받지 않는다. 기술 중계는 @Hidden이며 멤버 업무 스냅샷에 포함하지 않는다. 두 조회는 Swagger 밖에서도 멤버 주소로 접근할 수 있다.

이 중계는 그룹 테스트를 위해 정상 JSON·표준 6필드 ProblemDetail의 상태/본문을 유지한다. 멤버 업무 조합 API의 오류 변환 정책과 다르다. 연결 실패/시간 초과는 503 GROUP_SERVICE_UNAVAILABLE, JSON·매체·계약 오류/리다이렉트는 500 INTERNAL_ERROR다. 응답 상한은 1MiB이며 내부 헤더는 전달하지 않고 no-store를 적용한다.
