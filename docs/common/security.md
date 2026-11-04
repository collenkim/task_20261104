# 보안

| 항목 | 내용 |
| --- | --- |
| 목적 | 인증 방식, 인증 없이 여는 경로, 키·토큰·비밀번호 관리 규칙을 정한다 |
| 범위 | 모든 서비스의 HTTP API, 서비스 간 호출, 로컬 실행 환경 |
| 관련 코드 | `services/*/src/main/kotlin/task/*/infrastructure/configuration/`(`SecurityConfiguration`, `ProblemDetailSecurityHandler`, `SwaggerAutoAuthConfiguration`), `build-logic/src/main/kotlin/task/buildlogic/LocalSecretsTask.kt` |

## POL-SEC-001 인증 방식

- 모든 API는 JWT Bearer(`Authorization: Bearer <토큰>`)로 인증한다. 세션·쿠키·Basic·폼 로그인을 쓰지 않는다(무상태, CSRF 비활성).
- 서비스는 **공개키로 검증만** 한다. 토큰을 발급하지 않는다.

| 검증 항목 | 값 | 설정 |
| --- | --- | --- |
| 서명 | RS256, 공개키 파일 | `spring.security.oauth2.resourceserver.jwt.public-key-location`, `jws-algorithms` |
| 만료 | `exp` 확인(기본 허용 오차 60초) | Spring Security 기본 |
| 대상 | `aud`에 자기 서비스 이름이 있어야 함 | `...jwt.audiences`(`member-service` / `group-service`) |

- 실패하면 401 ProblemDetail(`UNAUTHORIZED`)과 `WWW-Authenticate: Bearer ...`를 돌려준다([POL-API-002](api-conventions.md#pol-api-002-오류-응답)).
- 권한(역할·범위)별 인가는 기능 개발 때 정한다. 지금은 유효한 토큰이면 모든 API를 허용한다.

## POL-SEC-002 인증 없이 여는 경로

| 경로 | 용도 |
| --- | --- |
| `/actuator/health`, `/actuator/health/**` | 상태 확인(liveness·readiness). 노출하는 actuator 엔드포인트는 `health`뿐 |
| `/v3/api-docs`, `/v3/api-docs/**` | OpenAPI 명세 |
| `/swagger-ui.html`, `/swagger-ui/**` | Swagger UI |
| `/error` | Spring 오류 처리 경로 |

- 목록은 `SecurityConfiguration.PUBLIC_PATHS` 한곳에서 관리한다. 늘릴 때는 이 표도 함께 고친다.

## POL-SEC-003 토큰 종류

| 토큰 | `sub` | `aud` | 쓰는 곳 | 파일 |
| --- | --- | --- | --- | --- |
| Swagger·로컬 호출용 | `local-swagger` | `member-service`, `group-service` | Swagger UI 자동 인증, 로컬 수동 호출 | `secrets/<서비스>/config/task.security.swagger-token` |
| 서비스 간 호출용 | `group-service` | `member-service` | 그룹 → 멤버 호출 | `secrets/group-service/config/task.member-client.token` |

- 공통: 발급자 `iss=task-local`, 유효 기간 365일, `jti` 포함, 헤더 `kid`는 공개키 지문.
- 서비스 간 토큰은 `aud`가 멤버 서비스뿐이라 그룹 서비스 자신에게 보내면 401이다(실행 확인).
- 사용자 로그인·토큰 발급 서버는 이번 범위가 아니다([ADR-003](decisions/ADR-003-fixed-jwt-keys.md)).

## POL-SEC-004 키·토큰·비밀번호

- **생성**: `./gradlew generateLocalSecrets`가 저장소 `secrets/`에 만든다. 값은 출력하지 않는다.

  | 파일 | 내용 | 다시 실행할 때 |
  | --- | --- | --- |
  | `jwt/private-key.pem`, `jwt/public-key.pem` | RSA 2048 키 쌍(PKCS#8 / X.509) | 유지 |
  | `<서비스>/db-admin-password` | DB 관리자(`postgres`) 비밀번호. DB 컨테이너만 읽음 | 유지 |
  | `<서비스>/config/spring.datasource.password` | 앱 DB 계정 비밀번호 | 유지 |
  | `<서비스>/config/task.security.swagger-token` | Swagger·로컬 호출용 토큰 | 유지 |
  | `group-service/config/task.member-client.token` | 서비스 간 호출용 토큰 | 유지 |

  - 키 쌍과 토큰 교체: `./gradlew rotateLocalJwt`. 실행 중인 서비스는 재시작해야 새 공개키를 읽는다.
  - DB 비밀번호는 DB 볼륨을 처음 만들 때만 적용되므로 자동으로 교체하지 않는다([local-development](local-development.md)).
- **전달은 파일로만** 한다.
  - Spring: configtree(`spring.config.import: optional:configtree:${TASK_SECRETS_DIR}/`). 파일 이름이 속성 이름이다.
  - Compose: `secrets:`로 `/run/secrets/<속성 이름>`에 마운트한다. PostgreSQL은 `POSTGRES_PASSWORD_FILE`을 쓴다.
  - 비밀값을 환경변수·설정 파일·명령줄 인수·이미지에 넣지 않는다. 환경변수에는 파일 위치만 둔다.
- **보관**
  - `secrets/`는 `.gitignore`로 Git에서 제외한다.
  - Docker 빌드 컨텍스트에는 jar만 들어간다(`.dockerignore`).
  - AI 에이전트는 `secrets/`를 읽지 않는다(`AGENTS.md`, `.claude/settings.json` deny).
- 개인 PC 로컬 실행 전용 값이다. 다른 환경은 그 환경의 비밀 저장소에서 같은 파일 이름으로 주입한다.

## POL-SEC-005 Swagger 자동 인증 (local 프로필)

- local 프로필에서만 켜진다(`SwaggerAutoAuthConfiguration`, `@Profile("local")`).
- `/swagger-ui/swagger-initializer.js` 응답에 Swagger·로컬 호출용 토큰을 `preauthorizeApiKey("bearerAuth", …)`로 넣는다.
  - 화면을 열면 바로 인증된 상태로 API를 호출할 수 있다.
  - 응답은 `Cache-Control: no-store`로 캐시하지 않는다.
- local 프로필인데 토큰 파일이 없으면 기동하지 않고 `./gradlew generateLocalSecrets`를 안내한다.
- 기본 프로필(운영 가정)에서는 토큰을 넣지 않는다. Swagger UI의 Authorize 버튼으로 직접 입력한다.
