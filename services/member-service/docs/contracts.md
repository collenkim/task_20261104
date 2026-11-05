# 멤버 API 계약 공유

| 항목 | 내용 |
| --- | --- |
| 대상 | 프런트엔드·다른 자사 서비스·클라이언트 생성 도구 |
| 공유 파일 | [openapi.json](openapi.json), OpenAPI 3.1 |
| 사람용 계약 | [API 정의서](api.md) |
| 검증 정책 | [POL-TEST-007](../../../docs/common/testing.md#pol-test-007-openapi-스냅샷과-계약) |

## 소비 방법

`openapi.json`을 기준으로 요청·응답 타입을 확인하거나 소비자 도구로 클라이언트를 생성한다. `servers`는 상대 주소 `/`로 정규화되어 있으므로 호출 대상 IP:포트는 소비자의 실행 설정에서 지정한다. 다른 서비스가 멤버의 JPA 엔티티·Kotlin 구현·공유 런타임 모듈을 가져오지 않는다.

| 메서드·경로 | operationId | 성공 스키마 |
| --- | --- | --- |
| GET /api/members | getMemberSummaries | MemberSummaryPageResponse |
| GET /api/member/{id} | getMemberDetail | MemberDetailResponse |

- 멤버는 0개 이상 그룹에 소속된다. `groups`는 항상 배열이며 미소속은 빈 배열이다.
- 상세 소속의 `joinedAt`·`pricing`은 키가 항상 있고 값은 null일 수 있다. 선택 정보 부재와 그룹 미소속을 구분한다.
- `permission`·`pricing`은 열린 문자열이다. 소비자는 새 코드를 수용하고 표시할 때 `permissionName`을 사용한다.
- page/size/sort는 반복하지 않는다. 원시 문자열·정수 범위·offset 제한은 [API 정의서](api.md#get-apimembers)의 기준을 따른다.
- UTC 시각은 마이크로초를 보존한다. 화면에서 필요한 시간대·표시 정밀도는 소비자가 변환한다.
- 실패는 ProblemDetail + `code`다. 오류 본문으로 내부 주소·키·그룹 응답·개인정보를 전달하지 않는다. 상세는 `Cache-Control: no-store`다.
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

계약 검사는 실제 멤버 Controller·DB와 local fixture 응답, 오류 상황을 대상으로 한다. HTTP 그룹 소비자 검증과 실제 그룹 제공자 검증은 별개다. 아직 그룹 업무 API가 없으므로 실제 그룹 업무 통합·제공자 명세 호환성을 검증했다고 해석하지 않는다.
