# MEMBER-001 3단계 공개 조회·local 시드 검증

| 항목 | 내용 |
| --- | --- |
| 분류 | 검증 기록 |
| 상태 | 이력 · 당시 요청·계획·검증 내용을 보존. 현재 구현 완료 선언이 아님 |
| 현재 기준 | [문서 색인](../../README.md)의 공통·서비스 정의를 따른다 |

| 항목 | 상태 |
| --- | --- |
| 기준 | 2026-11-05, `feature/MEMBER-001`, HEAD `e5a8c6b` + 작업 폴더 |
| 2단계 커밋 | 예약 `7e0a650739b3` 완료. `e5a8c6b3afec5160b8184536bdb40f5137368954`의 35개 경로·내용이 검증본과 일치 |
| 이번 범위 | M-05 공개 조회, M-06 local 자동 시드, M-09 계층/트랜잭션 검사와 관련 문서 |
| 완료 판정 | 3단계 구현·작업 폴더 검증 완료. 전체 빌드 성공, 멤버 90개 실패·오류·건너뜀 0 |
| 커밋 | 예약 `a8f79a2714eb` 완료. `c350e541d0309682b5bda34321f6a2b8dc69d277`의 46개 경로·내용 일치 확인 |

## 구현 내용

- `GET /api/members`: 멤버 기준 목록·페이지 메타데이터, 이름 ASC/DESC와 ID ASC 보조 정렬, 다중 그룹 소속.
- `GET /api/member/{id}`: 기본 정보·가입 시각·생성 감사 정보·소속, `Cache-Control: no-store`.
- Controller → 입력 포트 → QueryHandler → 출력 포트로 분리했다. Query/View와 공개 DTO를 나누고 application에서 JPA·HTTP·OpenAPI에 의존하지 않는다.
- 원시 파라미터의 반복·공백·부호·소수·비ASCII 숫자·정수 범위·offset 초과를 DB 진입 전에 거부한다. 유효한 size를 최대 100으로 적용한다. 기존 pageable 설정과 공통 오류 처리는 유지한다.
- DB에서 멤버 기본 정보를 읽고 트랜잭션이 끝난 후 그룹 포트를 한 번 호출한다. 빈 페이지·잘못된 입력·없는 멤버에는 그룹 요청이 없다.
- 공개 스키마·operationId·입력 제한·400/404/500/503 예제를 코드에 정의했다. 공유용 OpenAPI 파일과 읽기 전용 비교 작업은 M-07에서 남아 있다.
- `R__seed_member.sql`은 다섯 고정 멤버를 넣고, 생성자가 정확히 `seed`인 행의 조회용 열만 갱신한다. 생성 감사 정보와 비시드 행을 보존한다. 기본 문자열 collation과 별개로 소유자는 이진 비교한다.
- `application-local.yaml`에는 기본 migration과 local seed 위치를 함께 지정했다. 실제 프로필 파일을 읽는 Flyway·MariaDB 검사에서 local 최초 적용·무변경 재기동·기본 프로필 미적용을 확인했다. 이 검사에서는 테스트가 시드 위치를 덮어쓰지 않는다.

정확한 HTTP 계약은 [멤버 API](../../../services/member-service/docs/api.md)가 소유한다. 그룹 fixture/HTTP 공급자 구현은 [2단계](stage-2.md) 그대로 사용한다.

## 실행 검사

2단계 예약 파일을 보존한 선행 구현에서 멤버 테스트 88개와 전체 빌드가 통과했다. 2단계 커밋 확인 후 local 기본 설정을 연결했고 `:member-service:test --tests task.member.MemberSeedProfileTests --tests task.member.MemberLocalSeedTests`의 3개 검사도 통과했다. 최종 소유권 보완을 포함한 `./gradlew.bat spotlessApply build --no-daemon --console=plain`은 성공했다(41개 작업: 실행 7, 최신 상태 34). 멤버 90개 실제 실행, 실패·오류·건너뜀 0이다. 그룹 9개는 기존 성공 결과를 재사용했으며 이번 실제 실행으로 세지 않는다.

| 신규 테스트 | 개수 | 검증 내용 |
| --- | ---: | --- |
| MemberQueryRequestTests | 4 | 파라미터 생략·정수/offset 경계·반복·형식·정렬·상세 ID |
| MemberQueryApiTests | 10 | 실제 MariaDB + fixture, 공개 목록/상세·정렬·미소속/다중 소속·마이크로초·오류·호출 생략·OpenAPI |
| MemberQueryHttpIntegrationTests | 2 | 실제 HTTP 소비자 + 테스트 HTTP 서버, 같은 유스케이스 재사용·일괄 조회·오류 분류·호출 스레드 트랜잭션 |
| MemberLocalSeedTests | 1 | 실제 MariaDB에서 기본 위치 시드 미실행·최초/무변경/체크섬 변경·감사/비시드 보존·충돌 식별 |
| MemberSeedProfileTests | 2 | 실제 application/application-local 설정에서 시드 자동 적용·재기동·기본 프로필 격리 |
| MemberArchitectureTests | 4 | application/포트의 금지 참조, presentation의 입력 경계, 다른 서비스 코드 참조 금지, 의도적 위반 검출 |
| 기존 멤버 검사 | 67 | 저장소·공통 pageable/오류·그룹 공급자·명세 전달 회귀 |

트랜잭션 검사는 테스트 메서드에 `@Transactional`을 붙이지 않는다. 실제 저장소 프록시 내부에서는 읽기 전용 트랜잭션이 활성이고, HTTP 어댑터 진입에서는 같은 호출 스레드의 트랜잭션/동기화가 비활성인지 검사한다. 테스트 서버 스레드의 상태로 대체하지 않는다.

계층 검사는 ArchUnit `1.4.2`를 테스트 의존성으로 고정했다. 실제 JDK 21·Kotlin 2.3.21·Spring Boot 4.1.2 컴파일 및 바이트코드 검사를 통과했다. 기술 스택 공통 문서에도 반영했다. [공식 릴리스](https://github.com/TNG/ArchUnit/releases/tag/v1.4.2)

시드 재실행은 Flyway의 체크섬 변경 규칙을 실제 DB에서 검증했다. 변경 테스트는 테스트 전용 복사본을 사용하고, 실제 프로필 검사는 저장소 설정의 위치를 그대로 사용한다. 단순 재시작은 시드 행을 덮어쓰지 않으며 체크섬이 바뀌어도 생성 감사 정보·다른 소유자의 데이터는 유지한다. `SEED`·`seed `는 `seed`와 다른 소유자로 검사한다. [Flyway 반복 마이그레이션](https://documentation.red-gate.com/fd/repeatable-migrations-273973335.html)

## 3단계 완료 시점의 후속 범위

1. 3단계 예약 실행과 커밋 SHA·46개 경로·내용 일치를 확인했다. AI 턴 중 직접 커밋·병합은 하지 않았다.
2. 4단계에서 M-08 지수 백오프·Full Jitter를 구현한다. 3단계 당시 HTTP 재시도는 즉시 재시도 정책이며 후속 구현은 [4단계 기록](stage-4.md)을 따른다.
3. 5단계에서 M-07 OpenAPI 스냅샷·읽기 전용 비교·실제 응답 스키마 검사, M-11 Compose/Swagger 업무 실행·독립성을 검증한다.
4. 기존 local DB에 같은 ID의 비시드 행이 있으면 실행 검증에서 충돌을 보고한다. 이번 자동 테스트는 전용 Testcontainers DB에서 세 가지 비시드 소유자 충돌을 식별하고 데이터를 보존한 증거다. 사용자의 실행 DB를 조사·초기화한 것은 아니다.

이번 성공은 fixture 또는 테스트 HTTP 서버의 소비자 검증이다. 실제 그룹 업무 API는 미구현이므로 실제 그룹 통합 성공으로 표시하지 않는다. Compose·브라우저 검증도 아직 수행하지 않았다.

## 로깅·예약 상태 확인

선행 작업의 읽기 전용 상태 검사에서 Codex hook 실제 실행은 확인되지 않았고 턴은 `unknown (turn_started_without_hook)`이었다. 당시 마지막 수집 결과(2026-11-04 23:24:27 UTC)에 검증 보류 221건이 있었다. API 빌드 통과를 로그 제출 사본의 최신화 완료로 해석하지 않는다. 이후 사용자의 `task run` 출력과 실제 Git에서 2단계 커밋 성공을 확인했다. 로깅 설정·허용/치환 목록·로그 원본·스냅샷은 이번 단계에서 수정하지 않았다.

선행 문서 검사는 40개 문서·로컬 링크/앵커 211개·JSON 예제 5개에서 오류 0이었다. 2단계 커밋 확인 때까지 예약 35개 파일을 보존했다. 확인 이후에는 3단계 내용으로 local 설정과 현황 문서를 갱신했다. README AI 기록 영역과 기본 application.yaml(pageable 포함)은 그대로이며 운영 jar에는 시드·fixture만 포함되고 테스트 Probe·위반 대조군·ArchUnit은 제외된다.

최종 문서 40개·로컬 링크/앵커 224개·JSON 예제 5개에서 오류 0이며 `git diff --check`도 통과했다. 상세 결과와 검증 대상 파일 해시는 Git 제외 `build/verification/member-001/stage-3.json`에 보관한다.
