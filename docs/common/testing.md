# 테스트

| 항목 | 내용 |
| --- | --- |
| 목적 | 테스트 계층, 테스트 DB, 테스트용 비밀값을 다루는 규칙을 정한다 |
| 범위 | `services/*/src/test/` |
| 관련 코드 | `services/*/src/test/kotlin/task/*/support/`(`TestSupport.kt`, `TestJwt.kt`), `CommonPolicyTests.kt`, `SwaggerAutoAuthTests.kt`, `MemberClientTests.kt` |

## POL-TEST-001 계층

| 계층 | 도구 | 대상 |
| --- | --- | --- |
| 단위 | JUnit Jupiter, AssertJ, `ApplicationContextRunner` | 설정 검증, 클라이언트 재시도 같은 작은 단위 |
| 통합 | `@SpringBootTest` + MockMvc + Testcontainers PostgreSQL | 인증·오류 응답·페이지네이션·Flyway·health |

- `./gradlew build`가 모든 테스트를 실행한다. 서비스 하나만: `./gradlew :member-service:test`.
- 테스트 이름은 기대 동작을 한국어 문장으로 쓴다.
- 서비스 간 계약 테스트·아키텍처 계층 테스트는 기능 개발 때 추가한다.

## POL-TEST-002 테스트 DB

- Testcontainers가 서비스 DB와 같은 이미지(`postgres:18.6`)를 띄운다. **Docker가 실행 중이어야 한다.**
- 연결 정보는 `@ServiceConnection`으로 넘긴다. 로컬 DB(15432·25432)를 쓰지 않는다.
- 테스트도 Flyway 마이그레이션과 `ddl-auto: validate`를 그대로 거친다.

## POL-TEST-003 테스트 비밀값

- 테스트는 저장소 `secrets/`를 읽지 않는다.
  - configtree 위치를 없는 폴더로 바꾼다(`TestProperties.NO_LOCAL_SECRETS`).
- JWT 키는 테스트가 실행 중에 만든다(`TestJwt`). 공개키는 임시 PEM 파일로 주입한다.
  - 다른 키로 서명한 토큰, 만료된 토큰, `aud`가 다른 토큰도 실행 중에 만든다.
- 가짜 토큰·비밀번호 같은 값은 완성된 형태로 소스에 쓰지 않는다. 필요하면 실행 중에 조각을 이어 붙여 만든다.

## POL-TEST-004 테스트 전용 코드

- 공통 정책 확인용 API(`ProbeController`, `/test-probe/**`)는 테스트 소스에만 있다. 운영 jar에 들어가지 않는다.
- 다른 서비스 호출은 테스트 안의 가벼운 HTTP 서버(JDK `HttpServer`)로 흉내 낸다. 실제 다른 서비스를 띄우지 않는다.
