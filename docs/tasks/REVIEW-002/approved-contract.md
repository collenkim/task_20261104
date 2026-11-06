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

## 3차 승인: 공통 미디어 타입과 페이지 번호

이번 사용자 요청으로 성공·오류 API 응답의 Content-Type을 `application/json`으로 통일한다. 오류의 type/title/status/detail/instance/code 여섯 필드와 상태별 공통 code는 유지한다. MVC 예외뿐 아니라 인증·인가·메서드·요청 제한 필터, 그룹 직접 응답과 멤버 중계, OpenAPI 예제까지 같은 기준을 적용한다. Swagger HTML·JavaScript 같은 정적 자원은 JSON API 응답 대상이 아니다.

멤버의 외부 `page` 입력 기본값·최솟값과 응답 `page.number`는 1이다. `page=0`은 형식상 정수지만 허용 범위 밖이므로 422 VALIDATION_FAILED다. 음수·공백·정수 범위 초과는 기존 400 INVALID_PARAMETER 규칙을 유지한다. 내부 저장소는 0 기반으로 변환하며 offset은 `(page - 1) × 적용 size`로 계산해 2147483647 이하인지 확인한다. 빈 결과의 totalPages는 0이지만 요청 페이지 번호는 1 이상이다.

사용자의 마지막 정정으로 **page 객체는 유지**한다. members와 page{size, number, totalElements, totalPages}를 응답한다. page 제거 요청은 취소됐다.

서비스별 api.md에는 동일한 공통 오류 필드·상태/code 정의를 싣고, 실제 발생하는 상태를 API별로 표시한다. JWT를 사용하지 않는 그룹에 사용자 JWT 401을 구현한 것처럼 표시하거나 GET 전용 API에 발생하지 않는 409·415를 억지로 추가하지 않는다. 같은 HTTP 상태의 code와 의미는 두 서비스가 동일하게 유지한다.

새 계약 버전은 멤버 0.5.0, 그룹 0.3.0이다. 사용자 요청에 따라 기존 문서 분류·보안·중복 제거·GET 전용·계층·SQL 입력·성능·AI 기록을 함께 재검증한다. 기준 코드는 f1c17f2이며, 기존 2차 완료 기록은 해당 시점의 증거로 보존한다.

## 4차 승인: 그룹 summary 매핑 기준 집계

사용자 최종 결정은 ‘매핑 없는 ID는 제외, null로 반환하지 않음’이다. 그룹을 먼저 생성하고 멤버가 소속될 때 group_membership이 생성된다. 그룹 API는 이 매핑 테이블을 기준으로 조회하며 멤버 원장이나 API를 역조회하지 않는다.

- totalMemberCount는 요청 ID 중 각 그룹의 실제 반환 members 개수다. 요청 밖 소속·매핑 없는 ID·중복을 포함하지 않는다.
- 매핑 없는 ID를 가상 그룹이나 permission:null로 반환하지 않는다. 전부 매핑이 없으면 기존 `{"group":[]}`로 응답한다.
- 같은 조회 결과에서 count와 목록을 만들며 전체 그룹 count 쿼리를 제거한다. 읽기 트랜잭션과 정렬·단일 소속 제약을 유지한다.
- 상세 응답 필드와 오류 정책은 유지한다. 멤버 API에서 실제 멤버의 미소속을 group/permission=null로 표현하는 기존 계약도 유지한다.
- 그룹 OpenAPI 버전은 0.4.0이다. 멤버 소비자·중계·합성 HTTP 테스트·실제 Compose 검증도 새 집계 의미를 따른다.
