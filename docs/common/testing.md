# 테스트

| 항목 | 내용 |
| --- | --- |
| 목적 | 테스트 계층, 테스트 DB, 테스트용 비밀값을 다루는 규칙을 정한다 |
| 범위 | `services/*/src/test/` |
| 관련 코드 | `services/*/src/test/kotlin/task/*/support/`(`TestSupport.kt`), `JpaPersistenceTests.kt`, `CommonPolicyTests.kt`, `GroupApiDocsTests.kt`, `GroupClientTests.kt`, `InternalServiceConfigurationTests.kt` |

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
