# REVIEW-002 승인 계약

| 항목 | 내용 |
| --- | --- |
| 분류 | 구현 결정 |
| 상태 | 사용자 승인. 1차(응답 필드·중계 오류)와 2차(인증·오류 코드표·GET 전용·중복 ID) 구현. 2차 실행 검증 결과는 검증 기록 참조 |
| 근거 | 단일 group·감사 필드 제외·두 가입일 분리·정의 필드 선별 승인 및 중계 오류의 상태 구분 요청 |

멤버 목록 항목은 id/name/group(이름)/permission이다. 상세는 id/name/email/address/registeredDate/group이며 group은 id/name/registeredDate/pricing/permission이다. 미소속 group은 null, 목록 permission도 null이다. 멤버 가입일과 그룹 가입일은 별개다. 감사 정보는 DB에 보존하고 공개 DTO에서 제외한다. 다건 소속은 예외로 처리한다. 페이지 설정·메타데이터·정렬·내부 트랜잭션 경계는 유지한다. 멤버 공개 계약은 호환되지 않는 변경이므로 0.3.0으로 갱신한다.

중계는 최상위와 중첩 객체 모두 정의 필드로 새 응답을 만든다. 추가 필드는 제거하고 정상 응답한다. 타입 강제 변환·필수값 기본값 보정은 하지 않는다. 그룹 상세의 기존 여섯 필드는 유지한다. 멤버가 제공하는 그룹 명세도 동일한 공개 필드만 정의한다.

아래 1차 표의 코드는 2차 승인에서 코드표 `code` + 원인 `type`으로 바뀌었다(예: `GROUP_RESPONSE_INVALID_JSON` → 502 `BAD_GATEWAY` + `urn:problem-type:group-response-invalid-json`). 당시 결정 기록으로 보존한다.

| 중계 실패 원인 | 상태 | 코드(1차, 대체됨) |
| --- | --- | --- |
| 그룹 JSON 문법 손상·중복 키·뒤따르는 JSON | 502 | GROUP_RESPONSE_INVALID_JSON |
| 필수 누락·타입/null·식별자/시각·소속 정합성 위반 | 502 | GROUP_RESPONSE_CONTRACT_VIOLATION |
| 잘못된 Content-Type | 502 | GROUP_RESPONSE_UNSUPPORTED_MEDIA_TYPE |
| 응답 크기 상한 초과 | 502 | GROUP_RESPONSE_TOO_LARGE |
| 예상하지 않은 상태·리다이렉트·그룹 500 등 | 502 | GROUP_UPSTREAM_FAILURE |
| 연결 실패·전송 중 연결 종료·그룹 503 | 503 | GROUP_SERVICE_UNAVAILABLE |
| 응답/본문 수신 시간 초과·그룹 504 | 504 | GROUP_SERVICE_TIMEOUT |
| 멤버 내부의 처리되지 않은 오류 | 500 | INTERNAL_ERROR |

그룹이 정의한 정상적인 400/403/404 등의 업무 오류는 상태와 정의된 여섯 오류 필드를 보존한다. 내부 키 거부는 브라우저 사용자 인증 문제와 구분해 로그에 남긴다. JSON 형식과 응답 계약이 깨졌다면 위 502로 변환한다. 오류 값·본문·내부 키는 로그에 담지 않고 실패 코드·요청 추적 정보만 기록한다. 수신한 잘못된 응답을 gateway 오류로 분류하는 근거는 [RFC 9110 502·504](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.6.3)이며 연결 불가의 503은 프로젝트 정책이다.

이번 세분화는 그룹 API·명세 중계에 적용한다. 멤버 업무 조회의 기존 503 등은 별도 변경 없이 유지한다. D2·D3·D15의 이전 확인 대기·일괄 500 제안은 이 결정으로 대체한다. JWT·중복 ID 제거는 아래 2차 승인으로 확정했고, DNS·회로 차단은 미승인 상태로 현행 동작을 유지한다.

## 2차 승인 (2026-11-06)

| 항목 | 사용자 결정 | 적용 |
| --- | --- | --- |
| page 응답 | 제거하지 않는다. 클라이언트가 알아야 한다 | `page{size, number, totalElements, totalPages}` 유지 |
| 오류 코드 | 4xx 세분화. 표: 400 INVALID_PARAMETER, 401 UNAUTHORIZED, 403 FORBIDDEN, 404 RESOURCE_NOT_FOUND, 405 METHOD_NOT_ALLOWED, 409 RESOURCE_CONFLICT, 415 UNSUPPORTED_MEDIA_TYPE, 422 VALIDATION_FAILED, 429 TOO_MANY_REQUESTS, 500 INTERNAL_SERVER_ERROR, 502 BAD_GATEWAY, 503 SERVICE_UNAVAILABLE, 504 GATEWAY_TIMEOUT. Swagger에 노출 | 두 서비스 `ErrorCode`가 이 표를 쓰고 OpenAPI에 상태별 ProblemDetail 예제를 생성. 406 `NOT_ACCEPTABLE`은 같은 규칙으로 추가 |
| 세부 원인 | `code`는 표 그대로, 원인은 `type`(선택: 권장안) | `urn:problem-type:*` 고정 URN([POL-API-002](../../common/api-conventions.md#pol-api-002)) |
| 400/422 | 형식 400 / 의미 422(선택: 권장안) | 400: 숫자 아님·빈 값·반복·필수 누락·ID 패턴·콤마 결합. 422: size 0·offset 초과·허용 안 된 정렬·memberIds 100 초과 |
| 중복 ID | 로그를 남긴다. 중복된 ID도 원문으로 | summary 200, 중복 제외, WARN `event=input.duplicate_ids duplicateIds=[...]` |
| JWT | 권장안. Swagger 자동 토큰은 커밋되지 않는 secrets 대신 init 때 키를 생성해 처리 | [ADR-006](../../common/decisions/ADR-006-member-jwt-authentication.md). `generateLocalSecrets`가 RSA 키 쌍 생성, local이 요청마다 짧은 만료 토큰 서명 |
| 메서드 | HEAD·OPTIONS와 POST·PUT·PATCH도 막는다. 제공 API는 GET뿐 | 두 서비스 405 + `Allow: GET`([POL-API-008](../../common/api-conventions.md#pol-api-008)) |
| 문서 정책 | MEMBER-001·GROUP-001 문서에서도 `groups[]`를 걷어낸다. 문서 정책 통일 | 이력 문서의 다중 소속 응답 예시 제거, 현재 정책 링크 |

멤버 계약은 0.4.0, 그룹 계약은 0.2.0이다. 성공 응답 본문은 바뀌지 않았지만 인증·오류 `code`가 바뀌므로 호환되지 않는 변경으로 공유한다.
