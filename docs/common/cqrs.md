# CQRS와 서비스 내부 구조

| 항목 | 내용 |
| --- | --- |
| 목적 | 서비스 내부 계층과 조회·변경 경로를 나누는 규칙을 정한다 |
| 범위 | 각 서비스의 `src/main/kotlin/task/<서비스>/` |
| 관련 코드 | `services/*/src/main/kotlin/task/*/` (현재는 `presentation/error`, `infrastructure/configuration`, 멤버의 `infrastructure/client`) |

CQRS는 서비스 내부에서만 적용한다. 서비스마다 DB는 하나이고, 조회 모델과 변경 모델은 코드 경로로 나눈다(별도 조회 DB·이벤트 저장소 없음).

## POL-CQRS-001 계층

```text
task.<서비스>
├─ presentation/
│  ├─ query/            조회 API(@RestController). 요청 검증과 응답 변환만
│  └─ error/            오류 응답(ProblemDetail + code)
├─ application/
│  ├─ query/            조회 조건(…Query)·처리기(…QueryHandler)·결과(…View)
│  ├─ command/          변경 기능. 실제 요구가 생길 때 추가
│  └─ port/             저장소·외부 서비스 계약(인터페이스)
├─ domain/              업무 규칙(프레임워크 의존 최소화)
└─ infrastructure/
   ├─ persistence/      JPA 엔티티·저장소 구현(port 구현)
   ├─ client/           다른 서비스 HTTP 호출 구현(port 구현)
   └─ configuration/    기술 설정(보안·OpenAPI 등)
```

- 의존 방향: `presentation → application → domain`.
  - `infrastructure`는 `application/port`를 구현한다.
  - `application`은 `infrastructure`를 직접 참조하지 않는다.
- OpenAPI 전달·Swagger 초기화는 업무 조회가 아닌 기술 기능으로 `infrastructure/configuration`에서 처리한다.
- 패키지는 필요한 시점에 만든다. 빈 패키지를 미리 두지 않는다.

## POL-CQRS-002 조회 경로

- 조회 API는 `…Query`를 만들어 `…QueryHandler`에 넘긴다.
- 처리기는 조회 전용 결과(`…View`)를 돌려준다. 엔티티를 API 응답으로 내보내지 않는다.
- 조회 처리기는 `@Transactional(readOnly = true)`로 실행한다.
- 다른 서비스 데이터가 필요하면 `application/port`의 계약으로 받는다.
  - 구현은 `infrastructure/client`에 둔다.
  - DB 조회와 외부 HTTP 호출을 한 처리 안에 섞지 않는다. 경계를 port로 나눈다.
- 목록 조회는 페이지네이션 규칙([POL-API-003](api-conventions.md#pol-api-003-페이지네이션))을 따른다.

## POL-CQRS-003 변경 경로

- 변경 기능은 `application/command`에 `…Command`·`…CommandHandler`로 둔다.
- 조회 처리기에서 상태를 바꾸지 않는다.
- 이번 범위(SETUP-001)에는 변경 기능이 없다.

## 이름 규칙

| 종류 | 이름 | 예 |
| --- | --- | --- |
| 조회 조건 | `<대상><동작>Query` | `MemberDetailQuery` |
| 조회 처리기 | `<대상><동작>QueryHandler` | `MemberDetailQueryHandler` |
| 조회 결과 | `<대상>View`, `<대상>SummaryView` | `MemberView` |
| 외부 서비스 계약 | `<서비스>Port` 또는 `<대상>Reader` | `GroupMembershipReader`(멤버 서비스가 그룹 데이터를 읽는 계약) |
| 엔티티 | `<대상>Entity` | `GroupEntity` |
