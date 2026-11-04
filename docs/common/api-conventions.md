# API 규칙

| 항목 | 내용 |
| --- | --- |
| 목적 | 두 서비스의 HTTP API가 같은 형식으로 동작하게 한다 |
| 범위 | 모든 서비스의 공개 API(`/actuator/health`, API 문서 경로 포함) |
| 관련 코드 | `services/*/src/main/kotlin/task/*/presentation/error/`, `infrastructure/configuration/OpenApiConfiguration.kt`, `application.yaml`(`spring.data.web.pageable`) |

## POL-API-001 API 버전

- 경로·헤더·미디어 타입에 버전을 두지 않는다(예: `/v1/...`를 쓰지 않음).
- 호환되지 않는 변경은 소비 서비스와 함께 바꾼다. 계약 호환성 검증 방식은 기능 개발 단계에서 정한다.
- OpenAPI 문서의 `info.version`은 명세 필수 항목이라 springdoc 기본값을 둔다. API 버전을 뜻하지 않는다.

## POL-API-002 오류 응답

모든 오류 응답은 RFC 9457 ProblemDetail(`application/problem+json`)에 `code`를 더한다.

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "No static resource members.",
  "instance": "/members",
  "code": "NOT_FOUND"
}
```

- `code`는 클라이언트가 분기에 쓰는 안정적인 값이다. `title`·`detail`은 사람이 읽는 설명이며 바뀔 수 있다.
- 처리되지 않은 예외(500)는 내부 메시지·스택을 넣지 않는다. 고정 문구와 `INTERNAL_ERROR`만 넣는다.
- 401·403도 같은 형식이다. 401에는 RFC 6750 `WWW-Authenticate: Bearer` 헤더가 붙는다.

| code | status | 의미 |
| --- | --- | --- |
| `BAD_REQUEST` | 400 | 형식이 잘못된 요청(타입 불일치, 읽을 수 없는 본문 등) |
| `VALIDATION_FAILED` | 400 | 요청 값 검증 실패(Bean Validation) |
| `UNAUTHORIZED` | 401 | 토큰 없음·만료·서명 불일치·대상(aud) 불일치 |
| `FORBIDDEN` | 403 | 인증됐지만 권한 없음 |
| `NOT_FOUND` | 404 | 경로 또는 자원이 없음 |
| `METHOD_NOT_ALLOWED` | 405 | 지원하지 않는 HTTP 메서드 |
| `NOT_ACCEPTABLE` | 406 | 응답 형식 협상 실패 |
| `CONFLICT` | 409 | 상태 충돌(변경 기능 도입 시) |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 지원하지 않는 요청 본문 형식 |
| `INTERNAL_ERROR` | 500 | 처리되지 않은 서버 오류 |
| `SERVICE_UNAVAILABLE` | 503 | 일시적으로 처리할 수 없음 |

- 목록에 없는 상태 코드는 4xx면 `BAD_REQUEST`, 5xx면 `INTERNAL_ERROR`로 둔다.
- 다른 서비스 장애를 나타내는 코드는 서비스 간 조회를 만들 때 이 표에 추가한다([service-communication](service-communication.md)).
- 구현: `ProblemDetailsExceptionHandler`(MVC 예외), `ProblemDetailSecurityHandler`(401·403), `ErrorCode`.

## POL-API-003 페이지네이션

| 항목 | 값 |
| --- | --- |
| 요청 파라미터 | `page`(0부터), `size`, `sort` |
| 기본 크기 | 20 (`spring.data.web.pageable.default-page-size`) |
| 최대 크기 | 100 (`max-page-size`). 더 큰 값을 요청하면 100으로 맞춘다 |
| 응답 형식 | Spring Data `PagedModel` 형식(`serialization-mode: via-dto`): `content`와 `page{size, number, totalElements, totalPages}` |

- 목록 API는 `Pageable`을 받고 `Page`를 돌려준다. 제한 없는 전체 목록 API는 만들지 않는다.

## POL-API-004 API 문서

- springdoc-openapi로 코드에서 명세를 만든다.
  - 명세: `/v3/api-docs`
  - 화면: `/swagger-ui.html`
  - 두 경로는 인증 없이 연다.
- 문서에는 Bearer 보안 스킴(`bearerAuth`)을 선언하고 모든 API에 적용한다.
- 서비스별 API 정의서(`services/<서비스>/docs/api.md`)는 조회 API를 만들 때 쓴다. 정의서·Swagger·실제 응답이 일치해야 한다.
