# 테스트

| 항목 | 내용 |
| --- | --- |
| 목적 | 테스트 계층, 테스트 DB, 테스트용 비밀값을 다루는 규칙을 정한다 |
| 범위 | `services/*/src/test/` |
| 관련 코드 | `services/*/src/test/kotlin/task/*/`, 멤버 MemberPage/MemberPersistence/GroupMembershipDecoder/FixtureGroupMembership/HttpGroupMembership/GroupMembershipConfiguration 테스트 |

## POL-TEST-001 계층

| 계층 | 도구 | 대상 |
| --- | --- | --- |
| 단위 | JUnit Jupiter, AssertJ, `ApplicationContextRunner` | 설정 검증, 클라이언트 재시도 같은 작은 단위 |
| 통합 | `@SpringBootTest` + MockMvc + Testcontainers MariaDB | 내부 키·오류 응답·페이지네이션·Flyway·health·JPA 저장/조회 |

- `./gradlew build`가 모든 테스트를 실행한다. 서비스 하나만: `./gradlew :member-service:test`.
- 테스트 이름은 기대 동작을 한국어 문장으로 쓴다.
- 서비스 간 계약 테스트·아키텍처 계층 테스트는 기능 개발 때 추가한다.

## POL-TEST-002 테스트 DB

- Testcontainers가 서비스 DB와 같은 이미지(`mariadb:11.8.9`)를 띄운다. **Docker가 실행 중이어야 한다.**
- 연결 정보는 `@ServiceConnection`으로 넘긴다. 로컬 DB(13306·23306)를 쓰지 않는다.
- 테스트도 Flyway 마이그레이션과 `ddl-auto: validate`를 그대로 거친다.

## POL-TEST-003 테스트 비밀값

- 테스트는 저장소 `secrets/`를 읽지 않는다.
  - configtree import를 비운다(`TestProperties.NO_LOCAL_SECRETS`).
- 내부 키는 테스트 실행 중 문자열 조각을 결합한다. 누락·불일치·중복 헤더도 검사한다.
- 가짜 토큰·비밀번호 같은 값은 완성된 형태로 소스에 쓰지 않는다. 필요하면 실행 중에 조각을 이어 붙여 만든다.

## POL-TEST-004 테스트 전용 코드

- 공통 정책 확인용 API(`ProbeController`, `/test-probe/**`)는 테스트 소스에만 있다. 운영 jar에 들어가지 않는다.
- 다른 서비스 호출은 테스트 안의 가벼운 HTTP 서버(JDK `HttpServer`)로 흉내 낸다. 실제 다른 서비스를 띄우지 않는다.

## JPA 실동작 검증

- `JpaPersistenceTests`는 테스트 전용 엔티티·Spring Data Repository로 저장한 뒤 flush·clear하고 다시 조회한다. 한글·4바이트 문자와 UTC 마이크로초 시각을 검증한다.
- `db/test-migration/V900__jpa_probe.sql`은 테스트에서만 Flyway 위치에 추가한다. 운영 위치는 `db/migration/mariadb/`뿐이며 테스트 리소스는 bootJar에서 제외된다.
- 현재 실행 결과와 이전 DB 검증 결과를 혼용하지 않는다([재검증 기록](setup-verification.md)). 기존 HTTP 클라이언트 단위 테스트는 실제 그룹 업무 API 계약의 증거가 아니다.

## 실행·브라우저 검증

- `python scripts/verification/runtime-smoke.py`: 실제 Compose 네트워크·키 거부·명세 전달·호스트 포트를 검사한다.
- `python scripts/verification/runtime-smoke.py --independence`: 서비스별 앱·DB를 단독 실행으로 전환한 뒤 루트 모드로 복구한다. 볼륨을 삭제하지 않는다. 단독 모드가 이미 실행 중이면 중단한다.
- `node scripts/verification/swagger-browser.mjs`: Windows Edge headless에서 실제 명세를 읽고 선택·읽기 전용·외부 직접 요청 없음을 확인한다. 업무 API가 아직 없어 실행 버튼 검사는 브라우저 메모리에 테스트 명세를 넣어 수행한다. 실제 업무 API 호출 증거로 사용하지 않는다.
- 브라우저 검사에는 Node.js 24와 로컬 Edge를 사용한다. 다른 설치 경로는 TASK_BROWSER_EXECUTABLE, 멤버 주소는 TASK_VERIFY_ORIGIN으로 지정한다. 검사 프로필은 Git 제외 build/verification/에 남는다.

## POL-TEST-005 계층과 트랜잭션 (미적용 M-09)

현재 MemberPersistenceTests는 저장소 프록시의 읽기 트랜잭션과 반환 후 종료를 검증한다. 아래 전체 유스케이스·계층 검사는 아직 남아 있다.

- application에서 infrastructure·presentation·JPA·Spring Data·RestClient·HTTP/Servlet·Jackson·OpenAPI 타입을 참조하지 않는지 검사한다. 입력/출력 포트의 인자·반환 타입도 검사 대상이다.
- presentation은 입력 포트와 Query/View에만 의존하고 JPA·외부 HTTP DTO를 사용하지 않는다. 다른 서비스 코드·공유 런타임 모듈 참조도 금지한다. 계층 검사 도구가 실제 금지 예제를 검출하는지 최소 위반 fixture로 확인한다.
- 실제 Spring 프록시와 MariaDB를 사용하여 MemberReader 내부에서 읽기 트랜잭션이 활성화되고, 반환 후 GroupMembershipReader 진입 시 비활성임을 확인한다. 이 테스트 메서드 자체에 `@Transactional`을 붙이지 않는다.
- HTTP 어댑터 진입 시 멤버 호출 스레드의 `TransactionSynchronizationManager` 상태를 검사한다. 테스트 HTTP 서버의 별도 스레드에서 상태를 검사해 통과 처리하지 않는다. 지연 로딩이 밖으로 새지 않는지도 검증한다.
- fixture와 HTTP를 같은 입력 포트/QueryHandler에 연결하여 공개 계약·정렬·결합 결과가 같은지 확인한다. 난수·대기·클라이언트 교체를 위해 업무 코드를 바꾸지 않아야 한다.

## POL-TEST-006 MEMBER-001 기능 검증

필수 완료 매트릭스다. 현재 저장소(M-01·M-02), HTTP/fixture 포트·설정(M-03·M-04)은 [1단계](member-query-stage-1-verification.md)·[2단계](member-query-stage-2-verification.md)에서 검증했다. 원시 HTTP 입력·공개 API·시드·백오프/지터는 후속 단계다.

| 영역 | 필수 사례 |
| --- | --- |
| 입력 | 생략 기본값; 빈/공백/앞뒤 공백/음수/소수/문자/정수 범위 초과; page/size/sort 동일값·다른값 반복 모두 400 |
| 범위 | size 101→100; size 2147483648→400(보정 전 검증); offset 경계 안/밖; 허용 범위 내 마지막 페이지 초과→빈 배열·그룹 호출 0회 |
| 정렬 | 이름 ASC/DESC, 고정 ID 보조 정렬, 고정 DB 데이터셋의 동명이인 페이지 중복·누락 없음, 전체 건수는 멤버 수 |
| 결합 | 0/1/N 소속, 그룹별 다른 권한, 멤버·그룹 응답 순서 변경, 정상 논리 호출 1회, 중복 없는 요청 ID |
| 소속 계약 | 요청 ID 누락/추가/중복, 같은 멤버의 중복 group ID, 필수 필드 null/누락/공백, 잘못된 JSON/시각은 오류; 다중 소속은 정상 |
| 확장성 | 새로운 권한/과금 코드·추가 알 수 없는 필드 허용; 선택 joinedAt/pricing 부재 허용; 필수 배열 부재를 기본 emptyList로 숨기지 않음 |
| 상세 | 정상·미소속·다중 소속·감사 정보·no-store; ID 형식 400/미존재 404에서 그룹 포트 미호출 |
| 시각 | UTC와 마이크로초 보존, 날짜 경계, 가입/소속/생성 의미 구분, KST 분 단위 절삭 없음 |
| 공급자 | local fixture 실제 200, 기본 프로필 fixture 거부, 알 수 없는 source 거부, 포트 구현 정확히 1개, fixture 중복/미등록 ID 오류, HTTP 실패 시 fixture 미호출 |
| HTTP 실패 | 연결/응답 전 시간 초과 재시도, 본문 수신 중 시간 초과 분류, 5xx→503 재시도 없음, 4xx/리다이렉트/계약 오류→500, 내부 키·주소·본문 비노출 |
| 백오프 | 첫 시도 무대기; retry=0; 횟수 상한; 200/400/800 증가와 cap; 고정 난수 0/중간/상한; 성공/최종 실패 뒤 무대기; 인터럽트 즉시 중단·플래그 복구 |
| DB·시드 | V2·ddl validate·ID 비교; local 최초/체크섬 변경/재기동; 중복·감사 정보·비시드 보호; 기본 프로필 미적용 |

기존 CommonPolicyTests의 Pageable 최대 100과 일반 BAD_REQUEST 동작을 유지한다. 새 업무 API의 엄격한 입력과 기존 테스트 API를 혼동하지 않는다. 기대 DB 순서는 Kotlin 정렬로 계산하지 않고 고정 데이터의 명시된 순서를 사용한다. 정상 호출 1회와 재시도 포함 실제 HTTP 전송 최대 2회(기본값)를 구분한다.

## POL-TEST-007 OpenAPI 스냅샷과 계약 (미적용 M-07)

- 대상은 `services/member-service/docs/openapi.json`이다. 현재 파일·아래 Gradle 작업은 아직 없다. 구현 시 재현 가능한 생성/비교 작업을 추가한다.
- 생성 명령(목표): `./gradlew.bat :member-service:updateOpenApiSnapshot --no-daemon --console=plain`.
- 읽기 전용 검사 명령(목표): `./gradlew.bat :member-service:checkOpenApiSnapshot --no-daemon --console=plain`; 일반 `check`/`build`에도 연결한다. 일반 테스트나 build에서 스냅샷을 갱신하지 않는다.
- 별도 생성 문맥은 실제 운영 Controller·DTO를 로드하고 `/test-probe/**` 같은 테스트 Controller는 스캔에서 제외한다. `/internal-docs/group`도 기존 Hidden 정책에 따라 제외한다. 허용 업무 경로 두 개를 명시적으로 검증한다.
- servers는 생성/검사에 동일한 기준 상대 주소(`/`)로 정규화한다. 객체 키만 재귀 정렬하고 설명·필수값·스키마·배열 순서를 무차별 제거하지 않는다. UTF-8/LF로 기록한다. 같은 입력으로 두 번 생성한 결과가 같아야 한다.
- 명세 JSON 자체의 유효성·operationId 중복·필수/nullable·요청 범위·오류 예제를 검사한다. 실제 MockMvc 응답과 JSON 예제들을 같은 OpenAPI 스키마로 검증한다. 400·404·500·503도 검사한다.
- 실제 응답을 자동 복사한 기대값만으로 검증하지 않는다. 요청/응답 소유 계약의 의미를 독립된 기대값으로 확인한다. 갱신 시 필드 삭제·타입/필수 여부 변경은 소비자 영향과 함께 리뷰한다.
- 멤버 소비자 fixture/테스트 HTTP 서버의 성공은 실제 그룹 제공자의 적합성 증거가 아니다. 그룹 구현 후 그룹 원본 명세와 소비자 계약 검사·실제 연동을 별도로 수행한다.

## POL-TEST-008 실행 증거 구분 (미적용 M-11·M-12)

- fixture 모드: 실제 Compose 멤버 + MariaDB + 임시 그룹 어댑터에서 목록·상세·다중 소속을 실행하고 Swagger Try it out 성공을 기록한다. 기존 브라우저 검사의 메모리 테스트 명세가 아니라 실제 업무 API를 검증한다.
- 테스트 HTTP 모드: 멤버 HTTP 어댑터의 직렬화·키·오류·재시도·계약을 검사한다. 실제 제공자는 아니다.
- 실제 그룹 모드: 그룹 업무 API가 생긴 뒤 수행한다. 현재는 미검증이다.
- 그룹 중지/단독 실행에서도 fixture 기반 멤버 업무 API가 동작해야 한다. 실제 그룹 명세 조회는 실패할 수 있으며 그 검증을 섞지 않는다. 현재 동작 중인 다른 프로젝트·볼륨은 변경하지 않는다.
- 단계별로 커밋 SHA, 실행 명령, 성공/실패/건너뜀 수를 기록한다. 최종 작업 폴더 검사만으로 모든 중간 커밋이 통과했다고 하지 않는다.
