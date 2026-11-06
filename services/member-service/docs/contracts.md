# 멤버 API 계약 공유

| 항목 | 내용 |
| --- | --- |
| 분류 | 서비스 정의 |
| 상태 | 현재 · 적용 예정 사항은 해당 절에서 구분 |
| 대상 | 프런트엔드·다른 자사 서비스·클라이언트 생성 도구 |
| 공유 파일 | [openapi.json](openapi.json), OpenAPI 3.1 |
| 사람용 계약 | [API 정의서](api.md) |
| 검증 정책 | [POL-TEST-007](../../../docs/common/testing.md#pol-test-007-openapi-스냅샷과-계약) |

## 소비 방법

`openapi.json`을 기준으로 요청·응답 타입을 확인하거나 소비자 도구로 클라이언트를 생성한다. `servers`는 상대 주소 `/`로 정규화되어 있으므로 호출 대상 IP:포트는 소비자의 실행 설정에서 지정한다. 다른 서비스가 멤버의 JPA 엔티티·Kotlin 구현·공유 런타임 모듈을 가져오지 않는다.

| 메서드·경로 | operationId | 성공 스키마 |
| --- | --- | --- |
| GET /api/members | getMemberSummaries | MemberSummaryPageResponse |
| GET /api/member/{memberId} | getMemberDetail | MemberDetailResponse |

- 모든 작업은 `bearerAuth`(HTTP bearer, JWT, scope `member.read`)가 필요하다. 소비자는 발급 서버의 토큰을 `Authorization` 헤더로 보낸다. 제공 메서드는 GET뿐이다.
- 현재 멤버당 소속은 0~1개다. 상세 group은 단일 객체이며 미소속은 null이다. 목록은 group 이름·permission code를 반환한다.
- 상세 소속의 `registeredDate`·`pricing`은 키가 항상 있고 값은 null일 수 있다. 선택 정보 부재와 그룹 미소속을 구분한다.
- `permission`·`pricing`은 열린 문자열이다. 소비자는 새 코드를 수용한다. 표시명은 API로 반환하지 않는다.
- page/size/sort는 반복하지 않는다. 형식 위반은 400, size 0·offset 초과·허용하지 않는 정렬은 422다. 경계는 [API 정의서](api.md#get-apimembers)의 기준을 따른다.
- 업무 응답은 Asia/Seoul yyyy-MM-dd HH:mm 문자열이다. DB는 UTC TIMESTAMP(6)을 보존한다. OpenAPI는 date-time 대신 pattern 있는 string이다.
- 실패는 ProblemDetail + `code`다. 소비자는 `status`와 `code`로 분기하고, 같은 상태 안의 세부 원인은 `type` URN으로 구분한다(예: 404 `RESOURCE_NOT_FOUND` + `urn:problem-type:member-not-found`). 429는 `Retry-After`를 따른다. 오류 본문으로 내부 주소·키·그룹 응답·개인정보를 전달하지 않는다. 상세는 `Cache-Control: no-store`다.
- 클라이언트 SDK는 이 작업에 포함하지 않는다. 소비자 언어·도구를 정할 때 같은 스냅샷으로 생성하고 호환성을 검증한다.

## 생성과 검토

명세의 원천은 운영 Controller·DTO의 애너테이션이다. JSON을 손으로 고치지 않는다. 계약을 바꿀 때 API 정의서와 코드를 함께 수정하고 아래 명령으로 명세를 갱신한다. 저장소 루트에서 실행하며 검증용 MariaDB를 위해 Docker가 필요하다.

```powershell
.\gradlew.bat :member-service:updateOpenApiSnapshot --no-daemon --console=plain
.\gradlew.bat :member-service:checkOpenApiSnapshot --no-daemon --console=plain
```

`generateOpenApiSnapshot`은 운영 패키지만 읽는 별도 Spring 문맥에서 실제 응답·예제·공식 OpenAPI 메타스키마를 검사하고 `build/generated/openapi/openapi.json`을 만든다. `updateOpenApiSnapshot`만 검증된 파일을 서비스 docs로 복사한다. 일반 `check`·`build`는 저장소 파일을 읽기 전용으로 비교하고 불일치 시 실패한다. 테스트 전용 API와 그룹 명세 전달 경로는 공개 계약에 들어가지 않는다.

객체 키를 재귀 정렬하고 서버 URL만 정규화한다. 배열 순서·설명·필수/null·코드 의미를 제거하지 않으며 UTF-8/LF로 공유한다. 필드 삭제, 경로·타입·필수 여부 변경은 소비자 영향 검토가 필요하다. 새 필드·새 권한 코드도 소비자의 엄격한 역직렬화 설정과 함께 확인한다.

## 검증 범위

계약 검사는 실제 멤버 Controller·DB와 local fixture 응답, 오류 상황을 대상으로 한다. HTTP 그룹 소비자 검증과 실제 그룹 제공자 검증은 별개다. 현재 계약의 실제 제공자 연결·Swagger 증거는 [REVIEW-002 검증](../../../docs/verification/REVIEW-002/verification.md)에 있다.

## 계약 변경 이력

permissionName 제거와 시각 문자열 변경은 소비자 수정이 필요한 계약 변경이다. 경로와 페이지는 유지한다. REVIEW-002에서 groups 배열을 group으로 바꾸고 createdAt·createdBy를 공개 응답에서 제외했다. 이전 RFC 3339 파서와 permissionName 사용을 수정해야 한다.

0.4.0(REVIEW-002 2차): JWT Bearer 필수, 상세 경로 변수 이름 `{memberId}`, 오류 `code`를 상태별 코드표로 교체(`VALIDATION_FAILED`는 422 의미로 변경, 형식 위반은 400 `INVALID_PARAMETER`), 세부 원인 `type` URN 추가, 401·403·405·406·422·429 응답 선언. 성공 응답 본문은 0.3.0과 같다. 오류 `code`로 분기하던 소비자는 새 코드표에 맞춰 수정해야 한다.
