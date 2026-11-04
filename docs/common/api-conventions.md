# API 규칙

| 항목 | 내용 |
| --- | --- |
| 목적 | 두 서비스의 HTTP API가 같은 형식으로 동작하게 한다 |
| 범위 | 모든 서비스의 HTTP API(`/actuator/health`, API 문서 경로 포함). 사용자에게 공개되는 API는 멤버 서비스뿐이고, 그룹 API는 멤버 서비스만 호출하는 내부 API다([ADR-005](decisions/ADR-005-member-edge-group-internal.md)) |
| 관련 코드 | `services/*/src/main/kotlin/task/*/presentation/error/`, `infrastructure/configuration/OpenApiConfiguration.kt`, `application.yaml`(`spring.data.web.pageable`) |

## POL-API-001 API 버전

- 경로·헤더·미디어 타입에 버전을 두지 않는다(예: `/v1/...`를 쓰지 않음).
- 호환되지 않는 변경은 소비 서비스와 함께 바꾼다. 계약 호환성 검증 방식은 기능 개발 단계에서 정한다.
- OpenAPI 문서의 필수 항목 `info.version`은 `0.1.0`으로 명시한다. 명세 문서 버전이며 API 경로·헤더 버전과 다르다([OpenAPI 규격](https://spec.openapis.org/oas/v3.1.0#info-object)).

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
- 구현: `ProblemDetailsExceptionHandler`(MVC 예외), `ErrorCode`.

## POL-API-003 페이지네이션

| 항목 | 값 |
| --- | --- |
| 요청 파라미터 | `page`(0부터), `size`, `sort` |
| 기본 크기 | 20 (`spring.data.web.pageable.default-page-size`) |
| 최대 크기 | 100 (`max-page-size`). 더 큰 값을 요청하면 100으로 맞춘다 |
| 응답 형식 | Spring Data `PagedModel` 형식(`serialization-mode: via-dto`): `content`와 `page{size, number, totalElements, totalPages}` |

- 목록 API는 `Pageable`을 받고 `Page`를 돌려준다. 제한 없는 전체 목록 API는 만들지 않는다.

## POL-API-004 API 문서

- springdoc-openapi가 각 서비스의 코드에서 OpenAPI 3 명세를 생성한다. 버전은 `0.1.0`이다.
- 멤버 `/swagger-ui.html`에서 멤버·그룹 명세를 선택한다. 멤버는 실행 가능, 그룹은 읽기 전용(`supportedSubmitMethods=[]`)이다. 선택 시 UI 인스턴스를 새 설정으로 만든다.
- 멤버 `/v3/api-docs`는 사용자 인증 없이 제공하며 사용자 Bearer 보안 스킴이 없다.
- 그룹 `/v3/api-docs`는 내부 키가 필요하다. 원본에는 `internalServiceKey` apiKey 헤더 스킴을 정의한다.
- 멤버 GET `/internal-docs/group`은 고정된 그룹 명세만 조회하고 `Cache-Control: no-store`로 반환한다. 이 기술 컨트롤러는 `@Hidden`으로 멤버 업무 명세에서 제외한다.
- 브라우저 전달본은 내부 `servers`와 인증 요구·securitySchemes를 제거하고 참고용 설명을 붙인다. 실제 그룹 원본 계약은 그룹이 소유한다.
- 그룹 Swagger UI는 제공하지 않는다. 외부 온라인 validator와 브라우저의 그룹 직접 요청도 사용하지 않는다.
- 명세 전달에는 내부 키가 서버에서 추가되며 사용자에게 키를 입력받거나 전달하지 않는다. 사용자 지정 URL을 전달하는 범용 프록시는 제공하지 않는다.
- 그룹 명세 장애는 503, 내부 키 거부·명세 계약 오류는 500이다. 멤버 자기 명세와 readiness는 계속 제공한다.
- 위 동작은 local과 기본 프로필에 동일하게 적용한다. local은 개발 주소 기본값만 추가한다.
- 업무 API가 아직 없으므로 운영 명세 paths는 비어 있을 수 있다. 기능을 만들 때 서비스 `docs/api.md`와 구현·명세를 함께 갱신한다.
