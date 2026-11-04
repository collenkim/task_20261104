# MEMBER-001 구현 요청서

| 항목 | 내용 |
| --- | --- |
| 목적 | 멤버 조회 API를 다중 소속·교체 가능한 공급자·엄격한 계약으로 선행 구현한다 |
| 상태 | 2026-11-05 문서 개정. 구현 전 요청이며 완료 보고가 아님 |
| 범위 | 멤버 서비스 코드·공통 정책/검증 문서. 그룹은 계약 문서만 변경 가능 |
| 정본 | 저장소의 이 파일. 이전 Temp 단일 소속 요청서를 대체 |
| 전체 계획 | [member-query-plan](../member-query-plan.md) |

## 0. 시작 조건과 문서 우선순위

- 작업 저장소는 `C:\IdeaProjects\task_20261104`, 구현 브랜치는 master에서 만든 `feature/MEMBER-001`이다. 실제 브랜치·HEAD·작업 폴더 상태를 먼저 확인하고 보고한다. 문서가 이미 커밋되어 있다고 추정하지 않는다.
- [AGENTS.md](../../../AGENTS.md)를 따른다. AI 턴 중 Git 변경 금지, 커밋은 예약, secrets/·.env*·인증 파일 미열람, 로그 원본·스냅샷 직접 편집 금지다.
- 현재 문서 변경을 유지한 브랜치 준비 방법은 [계획의 단계 절차](../member-query-plan.md#8-단계와-커밋-절차)를 따른다. 자동 stash/reset/clean을 하지 않는다.
- 기준은 [멤버 API](../../../services/member-service/docs/api.md), [멤버 엔티티](../../../services/member-service/docs/entities.md), [멤버 정책](../../../services/member-service/docs/policies.md), [그룹 내부 계약](../../../services/group-service/docs/api.md), [공통 API](../api-conventions.md), [CQRS](../cqrs.md), [통신](../service-communication.md), [데이터](../data-conventions.md), [테스트](../testing.md)다.
- 최신 사용자 결정이 우선이다. 문서끼리 충돌하면 구현으로 임의 확정하지 말고 차이와 수정안을 보고한다. 미적용 목표와 현재 동작의 차이는 구현 대상이며 그 자체를 문서 충돌로 오인하지 않는다.

## 1. 포함·제외와 검증 범위

- 포함: member V2, 공개 GET 2개, 다중 그룹 소속, local fixture/HTTP 출력 포트 구현, local 시드, 지수 백오프·Full Jitter, OpenAPI 스냅샷, 계층·트랜잭션·계약·실행 검증.
- 제외: 그룹 서비스의 Controller/엔티티/DB/시드/실제 업무 OpenAPI 생성, 사용자 인증·인가, 쓰기 API, 공유 런타임 모듈.
- 그룹 제공자 완성 전에도 fixture 모드의 실제 멤버 목록·상세는 200으로 실행되어야 한다. fixture 성공, 테스트 HTTP 어댑터 성공, 실제 그룹 통합 성공을 구분한다. 마지막 항목은 이번 완료 기준에서 미검증이다.

## 2. 공개 API

- `GET /api/members`: `members` + `page`, 각 멤버는 `id`, `name`, `groups[]`다.
- `GET /api/member/{id}`: 기본 정보·등록/생성 시각·생성자 + `groups[]`다. 상세는 no-store.
- 다중 소속을 오류로 처리하지 않는다. 미소속은 빈 배열. 그룹 핵심 필드는 목록/상세 모두 `id`, `name`, `permission`, `permissionName`. 상세만 선택 `joinedAt`, `pricing`을 추가한다.
- 정확한 요청/응답 JSON과 타입·필수/null·오류 예시는 [멤버 API](../../../services/member-service/docs/api.md#member-001-공개-계약-미적용-m-05)가 소유한다. 요청서에서 별도의 JSON 복사본을 관리하지 않는다.
- page/size 원시 문자열의 공백·빈 값·음수·소수·숫자 외 문자·Int 범위 초과는 거부한다. page/size/sort 반복은 값이 같아도 400이며 중복을 제거해 처리하지 않는다. 생략에만 기본값을 쓴다.
- size 범위 검증 후 최대 100을 적용하고 Long으로 offset을 계산해 Integer.MAX_VALUE 이하인지 검증한다. 정렬은 이름 ASC/DESC만 허용하며 ID ASC를 내부 보조 정렬로 고정한다.
- **기존 pageable 설정 블록과 CommonPolicyTests는 유지한다.** 신규 요청 DTO와 별개다.
- 시각은 UTC RFC 3339, 마이크로초 보존. 그룹 소속은 joinedAt으로 구분한다. KST 분 단위 표시는 소비자 책임이다.
- 과금 정보는 선택이다. 새로운 코드·추가 필드는 수용하고 그룹 enum은 복제하지 않는다.

## 3. 그룹 공급자

- application 소유 `GroupMembershipReader`를 fixture/HTTP로 구현한다. Controller·QueryHandler에 공급자 분기나 멤버별 임시 데이터를 넣지 않는다.
- `task.group-membership.source`: 기본 http, local fixture. fixture는 local 외 기동 실패, 정확히 한 구현만 등록한다. HTTP 실패 시 자동 fallback은 없다.
- 임시 데이터 파일은 계획된 local 자원에서 로드한다. 시드 ID마다 명시적인 소속 배열이 필요하다. fixture 누락을 실제 미소속으로 꾸미지 않는다.
- HTTP는 기존 groupRestClient·숫자 IP:포트·서버 내부 키를 재사용한다. `/internal/group-memberships`의 반복 memberIds는 1~100개의 중복 없는 ID다. 공개 page/size 중복 금지와 다른 계약이다.
- 소속 0/1/N은 정상, 요청 ID 누락/추가/중복·같은 멤버의 중복 group ID·필수 필드 오류는 계약 위반이다. 선택 정보 누락은 허용한다.
- 기존 그룹 명세 프록시는 실제 HTTP를 유지한다. fixture로 가짜 제공자 명세를 공개하지 않는다.

## 4. 낮은 결합도와 처리 순서

- Controller → 입력 포트 → QueryHandler → 출력 포트 순서다. 입력은 Query, 결과는 View다. DB·외부 HTTP·공개 API DTO를 각각 분리한다.
- application에 infrastructure/presentation/JPA/Spring Data/HTTP/Servlet/RestClient/Jackson/OpenAPI 참조를 넣지 않는다.
- 목록 DB 페이지 → 내부 모델 변환 → 읽기 트랜잭션 종료 → 소속 논리 일괄 조회 1회 → ID 결합 → Response 변환. 상세는 멤버 존재 확인 후 같은 그룹 계약을 ID 하나로 호출한다.
- 빈 목록·입력 오류·없는 멤버에서는 그룹 포트를 호출하지 않는다. 그룹 배열은 group ID 순으로 정렬한다.
- 읽기 트랜잭션은 Spring 프록시를 거치는 저장소 어댑터에만 둔다. HTTP 호출 때 DB 트랜잭션이 살아 있으면 완료로 처리하지 않는다.
- 경계 인터페이스와 작은 순수 변환으로 재사용한다. 범용 기반 클래스나 서비스 간 DTO 모듈을 추가하지 않는다.

## 5. 재시도

- [POL-COMM-003](../service-communication.md#pol-comm-003-제한-시간과-재시도)의 capped exponential backoff + Full Jitter를 구현한다.
- 기본 추가 재시도 1회, base 200ms, cap 2s. n번째 재시도 상한은 min(cap, base × 2^(n-1)), 실제 대기는 0부터 상한까지 균등 난수다.
- 설정 최대 3회까지 지원하며 지연 상한·설정 범위·overflow를 검증한다. 정상 1회, 기본 실패 시 최대 2회 전송이다.
- 응답 전 연결 실패·시간 초과의 GET/HEAD만 대상이다. 4xx/5xx·리다이렉트·계약 오류·변경 요청은 재시도하지 않는다. 본문 수신 중 시간 초과는 별도로 503 분류를 확인한다.
- 기존 마지막 인터셉터에서 단일 재시도 정책을 적용하고 호출부에 중첩하지 않는다. 난수·대기를 주입하고 인터럽트를 복구·중단한다. 전체 API의 고정 최대 지연을 보장했다고 쓰지 않는다.

## 6. DB와 local 데이터

- V2 member 스키마와 ID·열·인덱스는 [엔티티 문서](../../../services/member-service/docs/entities.md)가 기준이다. V1은 수정하지 않는다.
- local 시드는 R__이며 local locations에 기본 마이그레이션과 시드 경로를 모두 명시한다.
- 최초·체크섬 변경 시 적용하고 같은 파일의 재기동은 무변경이다. 시드 소유 행만 갱신하고 생성 감사 정보·비시드 행을 보존한다. CURRENT_TIMESTAMP·무작위 ID·전체 삭제로 재현성을 만들지 않는다.
- 일반 테스트/기본 프로필에는 시드가 자동 적용되지 않는다. 별도 실제 MariaDB local 시드 검사에서 재실행과 충돌을 확인한다. jar 포함과 기본 프로필 실행 제외를 구분한다.

## 7. 검증

필수 매트릭스는 [POL-TEST-005~008](../testing.md#pol-test-005-계층과-트랜잭션-미적용-m-09)을 따른다. 특히 아래를 빠뜨리지 않는다.

- 공백/범위/중복/offset 입력, 고정 데이터의 정렬·페이지 건수, 미소속/다중 소속, 그룹별 다른 권한.
- 두 어댑터 교체와 동일 유스케이스 재사용, 알 수 없는 코드/필드 허용, 선택 정보 부재.
- 실제 호출 스레드의 트랜잭션 종료, 금지 계층 의존, 엔티티·외부 DTO 비노출.
- 지수 증가·cap·지터 경계·재시도 종료·인터럽트·기존 명세 조회 회귀.
- 시드 최초/재기동/변경, 감사 정보/비시드 보호, 기본 프로필 격리.
- OpenAPI 생성용 문맥에서 test-probe 제외, 서버 주소 정규화, 결정적 JSON, 일반 빌드 읽기 전용 비교, 실제 응답 스키마 검사.
- 계층/스키마 검사 의존성은 실제 JDK 21·Kotlin·Spring Boot 호환성을 확인한 뒤 버전을 고정하고 기술 스택 문서에 기록한다. 계획 단계에서 설치된 것으로 표시하지 않는다.

## 8. 실행 확인과 완료 기준

- `./gradlew.bat spotlessApply` 후 `./gradlew.bat build --no-daemon --console=plain`을 수행한다(Docker 필요).
- 구현 후 루트 Compose에서 실제 멤버 API의 정상 목록/상세·다중 소속·잘못된 입력·없는 멤버·빈 페이지를 검증한다. Swagger도 실제 업무 명세로 실행한다.
- 그룹이 중지되거나 업무 API가 없어도 fixture 멤버 조회는 성공해야 한다. 실제 그룹 명세 실패와 혼동하지 않는다.
- 명세 갱신/검사 목표 명령은 테스트 문서가 소유하며, 구현 전에는 실행 가능한 명령이라고 안내하지 않는다.
- 전체 빌드 실패/건너뜀 0, 목표 기능·격리·계약 검증 통과, 문서와 실제 결과 일치가 단계 완료 기준이다. 실제 그룹 업무 통합은 미검증으로 남긴다.

## 9. 문서와 단계 커밋

- 구현/검증한 M-xx만 상태를 바꾼다. 문서 생성만으로 구현 완료를 표시하지 않는다.
- 공통/서비스 문서, README의 현재 상태, 검증 기록을 구현 결과에 맞춘다. 링크·앵커·정책 ID·예제도 검사한다.
- 단계는 문서 → 저장소 → HTTP/fixture 어댑터와 선택 설정 → 공개 조회·시드 → 재시도 → 계약·실행 검증 순서다. QueryHandler를 도입하기 전에 두 공급자를 준비해 기본 프로필의 포트 빈이 빠지는 중간 커밋을 만들지 않는다. 각 단계에 테스트를 포함한다.
- 커밋 예약은 실행 시 파일을 읽는다. 단계 검증 후 예약한 경로를 다음 단계 내용으로 다시 수정하기 전에 응답 종료 후 예약 실행과 실제 커밋 SHA를 확인한다. 여러 중간 단계를 한 번에 예약해 각각 검증됐다고 하지 않는다.
- `[MEMBER-001] 제목` / 빈 줄 / 두 칸 본문 / Co-Authored-By. AI 턴 중 직접 Git 변경 금지. develop 병합·push-develop·merge-feature·master push는 이 구현 요청에서 실행하지 않는다(사용자 절차).

## 10. 보고

구현 ID, 명령, 테스트 수와 실패/건너뜀, 검증한 커밋, 변경 문서, fixture/테스트 HTTP/실제 그룹 검증 범위, 남은 제한을 한국어로 보고한다. 비밀값·키·실제 개인정보를 출력하지 않는다.
