# CQRS와 서비스 내부 구조

| 항목 | 내용 |
| --- | --- |
| 분류 | 공통 기준 |
| 상태 | 현재 · 적용 예정 사항은 해당 절에서 구분 |
| 목적 | 서비스 내부 계층과 조회·변경 경로를 나누는 규칙을 정한다 |
| 범위 | 각 서비스의 `src/main/kotlin/task/<서비스>/` |
| 관련 코드 | `services/*/src/main/kotlin/task/*/`의 presentation·application·domain·infrastructure와 각 서비스 계층 검사 |

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
│  └─ port/             input/(조회 유스케이스), output/(저장소·외부 서비스)
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
- DB 조회·엔티티의 내부 조회 모델 변환은 저장소 어댑터의 `@Transactional(readOnly = true)` 메서드에서 끝낸다. Spring 프록시를 거쳐 호출하며 자기 호출로 트랜잭션을 우회하지 않는다. 멤버 JpaMemberReader에 적용했다(M-02).
- 여러 데이터 소스를 조합하는 QueryHandler 전체에 DB 트랜잭션을 적용하지 않는다. 그룹 HTTP/fixture 포트 진입 전에 DB 트랜잭션이 종료되어야 한다.
- 다른 서비스 데이터가 필요하면 `application/port`의 계약으로 받는다.
  - 구현은 `infrastructure/client`에 둔다.
  - QueryHandler가 순서를 조정하되 DB 트랜잭션과 외부 호출의 실행 경계를 나눈다. JPA 엔티티·지연 로딩 참조를 포트 밖으로 반환하지 않는다.
- 목록 조회는 페이지네이션 규칙([POL-API-003](api-conventions.md#pol-api-003-페이지네이션))을 따른다.

## POL-CQRS-004 교체 가능한 경계

M-02 저장소 출력 포트, M-04 소속 공급자 선택, M-05 입력 포트·QueryHandler와 M-09 계층/실제 호출 트랜잭션 검사를 구현했다. [3단계 기록](../verification/MEMBER-001/stage-3.md)에서 fixture와 테스트 HTTP 서버의 증거를 구분한다.

- 입력 포트는 Query/View를 주고받는 기술 독립적인 조회 계약이다. HTTP Controller는 이를 호출한다. 새로운 내부 진입점도 같은 유스케이스를 사용할 수 있다.
- 출력 포트는 application이 소유하고 infrastructure가 구현한다. application은 JPA/Spring Data/RestClient/HTTP/Servlet/Jackson/OpenAPI/presentation DTO에 의존하지 않는다.
- API Response, 내부 View, 외부 HTTP DTO, JPA Entity를 각각 경계에서 매핑한다. application 내부 페이지 모델을 사용하고 저장소 구현에서만 Pageable/Page로 변환한다.
- local fixture와 실제 HTTP는 같은 출력 포트를 구현한다. 공급자 선택은 configuration에서만 수행한다. 실패 후 임시 공급자로 자동 전환하지 않는다.
- 인터페이스는 유스케이스·외부 경계에 둔다. 단순 변환마다 추상화하지 않으며 빈 domain/command 패키지를 미리 만들지 않는다.
- 정적 계층 검사와 호출 스레드의 실제 트랜잭션 검증을 모두 둔다([POL-TEST-005](testing.md#pol-test-005-계층과-트랜잭션)).

## POL-CQRS-003 변경 경로

- 변경 기능은 `application/command`에 `…Command`·`…CommandHandler`로 둔다.
- 조회 처리기에서 상태를 바꾸지 않는다.
- 현재 두 서비스의 업무 API에는 변경 기능이 없다. 조회·기술 중계만 제공한다.

## 이름 규칙

| 종류 | 이름 | 예 |
| --- | --- | --- |
| 조회 조건 | `<대상><동작>Query` | `MemberDetailQuery` |
| 조회 처리기 | `<대상><동작>QueryHandler` | `MemberDetailQueryHandler` |
| 조회 결과 | `<대상>View`, `<대상>SummaryView` | `MemberView` |
| 외부 서비스 계약 | `<서비스>Port` 또는 `<대상>Reader` | `GroupMembershipReader`(멤버 서비스가 그룹 데이터를 읽는 계약) |
| 엔티티 | `<대상>Entity` | `GroupEntity` |
