# 보안

| 항목 | 내용 |
| --- | --- |
| 분류 | 공통 기준 |
| 상태 | 현재(REVIEW-002 개정) |
| 목적 | 멤버 사용자 인증(JWT), 그룹 내부 호출 키, 요청 제한, 비밀 파일 관리 기준 |
| 범위 | 멤버·그룹 HTTP 요청, 로컬 실행 |
| 관련 코드 | 멤버 `infrastructure/security/`, `infrastructure/web/`, `GroupClientConfiguration`. 그룹 `InternalServiceConfiguration`. build-logic `LocalSecretsTask` |
| 결정 | [ADR-006 멤버 API JWT 인증](decisions/ADR-006-member-jwt-authentication.md) |

<a id="pol-sec-001"></a>

## POL-SEC-001 인증 방식

### 사용자 → 멤버: JWT Bearer

- 멤버의 업무 API와 그룹 중계는 `Authorization: Bearer <JWT>`를 요구한다(사용자 결정, [ADR-006](decisions/ADR-006-member-jwt-authentication.md)). Spring Security OAuth2 Resource Server가 검증한다.
- 서명은 RS256만 허용한다. 검증 공개키는 configtree 파일 `task.security.jwt.public-key`(X.509 PEM)다. 운영에서는 발급 서버(IdP)의 공개키를 같은 위치에 둔다.
- 검증 claim: `iss`(=`task.security.jwt.issuer`, local 기본 `task-local`), `aud`에 `member-service` 포함, `exp` 필수, `nbf`, 비어 있지 않은 `sub`. 시각 오차는 `clock-skew`(기본 30초, 0~5분)만 허용한다.
- 경로별 scope: `/api/**`는 `member.read`, `/internal-api/group/**`는 `group.read`. 그 밖의 경로는 [POL-SEC-002](#pol-sec-002-인증-없이-여는-경로)의 공개 경로가 아니면 인증을 요구한다.
- 토큰 없음·검증 실패는 401 `UNAUTHORIZED`, scope 부족은 403 `FORBIDDEN`이다. 상태와 `WWW-Authenticate`(RFC 6750, `resource_metadata` 포함)는 Spring Security가 정하고 본문은 공통 ProblemDetail이다. 토큰 값은 본문·로그에 쓰지 않는다.
- 세션·쿠키·CSRF·폼 로그인·HTTP Basic은 쓰지 않는다(STATELESS). 요청 캐시도 끈다.
- RFC 9728 보호 자원 메타데이터 `/.well-known/oauth-protected-resource`는 인증 없이 GET으로 연다. 자원 이름·scope·Bearer 헤더 방식만 알리고, 지원하지 않는 mTLS 바인딩은 `false`로 알린다. 발급 서버 주소는 운영 IdP를 정할 때 추가한다.
- 멤버는 사용자 토큰을 그룹에 전달하지 않는다. 그룹 호출은 아래 내부 키로만 인증한다.

### 멤버 → 그룹: 내부 호출 키

- 멤버 → 그룹 요청에 `X-Internal-Service-Id`와 `X-Internal-Service-Key`를 추가한다. 그룹은 등록 호출자의 키와 함께 비교한다.
- 미등록 호출자·누락·불일치·중복 헤더·IP 범위 위반은 403 `FORBIDDEN`(type `urn:problem-type:internal-access-denied`) ProblemDetail이다. 사용자 인증이 아니라 호출자 식별 실패이므로 401을 쓰지 않는다. 키 값은 응답·로그에 쓰지 않는다.
- 두 앱은 필수 키 설정이 없으면 기동하지 않는다. 값 비교는 SHA-256 고정 길이 해시를 `MessageDigest.isEqual`로 수행한다.
- 이 방식은 공유 키 소유를 확인한다. 특정 프로세스의 신원을 증명하는 방식은 아니다.
- 키와 토큰이 평문 HTTP를 암호화하지는 않는다. 로컬 컨테이너 통신 외의 호스트 간 배포에는 HTTPS를 사용한다.

## POL-SEC-002 인증 없이 여는 경로

| 경로(GET만) | 멤버 | 그룹 |
| --- | --- | --- |
| 업무 API | JWT `member.read` | 내부 키 필요 |
| `/actuator/health`, `/actuator/health/**` | 인증 없음 | 인증 없음 |
| `/v3/api-docs`, `/v3/api-docs/**` | 인증 없음(데이터 없음) | 내부 키 필요 |
| `/swagger-ui.html`, `/swagger-ui/**` | 인증 없음 | 제공하지 않음 |
| `/.well-known/oauth-protected-resource` | 인증 없음 | 제공하지 않음 |
| `/internal-docs/group` | 인증 없음, local 전용. 멤버 서버가 그룹 호출에 키 추가 | 제공하지 않음 |
| `/internal-api/group/api/groups/summary`, `/internal-api/group/api/group/{memberId}` | JWT `group.read`, local 전용. 멤버 서버가 내부 키 추가 | 제공하지 않음 |
| `/error`, 오류 디스패치 | 인증 없음 | 인증 없음 |

모든 경로에서 GET 외 메서드는 인증 전에 405다([POL-API-008](api-conventions.md#pol-api-008)). 그룹에서 존재하지 않는 경로도 키 필터를 먼저 거친다. 키 검사 제외 여부는 컨테이너가 해석한 경로로 판단하며 점 경로를 health 예외로 허용하지 않는다. 키 없는 Swagger 요청은 403, 정상 키를 보낸 Swagger 요청은 404다. health 응답에는 상세 정보를 공개하지 않는다.

## POL-SEC-003 내부 호출 키

- 멤버 설정: `task.group-client.service-id=member-service`, `task.internal-service.key`.
- 그룹 설정: `task.internal-service.clients.<서비스ID>.key`와 선택 `allowed-cidrs` 목록. 다른 서비스 추가는 호출자 설정·별도 키 배포로 진행한다.
- local은 secrets/internal/task.internal-service.key를 clients.member-service.key로 연결한다. Compose/IDE/bootRun에 같은 local 매핑이 적용되며 기본 프로필은 호출자별 설정을 명시한다.
- CIDR은 숫자 IPv4/IPv6만 받고 잘못된 설정은 기동 실패다. 소켓 remoteAddr로 검사하며 X-Forwarded-For는 신뢰하지 않는다. 생략 시 IP 제한 없이 호출자·키를 검사한다.
- 두 서버만 파일로 읽는다. 브라우저·Swagger·이미지·소스·Git에 값을 넣지 않는다.
- 멤버 클라이언트는 설정한 키로 헤더를 덮어쓴다. 사용자 인증 헤더를 그룹에 전달하지 않는다. 검증한 X-Request-Id와 서버 생성 X-Internal-Call-Id·X-Internal-Attempt만 추적용으로 전파한다.
- 리다이렉트를 따라 키를 다른 주소로 보내지 않는다.

## POL-SEC-004 키·비밀번호

`secrets/`는 커밋하지 않는다. 프로젝트를 처음 실행할 때 `./gradlew.bat generateLocalSecrets`(`startLocal`이 먼저 실행)가 없는 파일만 만든다.

| 명령·파일 | 동작 |
| --- | --- |
| `./gradlew generateLocalSecrets` | 없는 내부 키·DB 비밀번호·JWT 키 쌍만 생성. 기존 값 유지 |
| `./gradlew rotateInternalServiceKey` | 내부 키만 교체. 두 앱을 재시작해야 적용 |
| `secrets/<서비스>/db-admin-password` | DB root 전용. 앱에 전달하지 않음 |
| `secrets/<서비스>/config/spring.datasource.password` | 해당 DB 앱 계정 비밀번호 |
| `secrets/internal/task.internal-service.key` | 두 앱의 내부 호출 키 |
| `secrets/member-service/config/task.security.jwt.public-key` | 멤버 JWT 검증 공개키(RSA 2048, X.509 PEM) |
| `secrets/member-service/config/task.security.local-token.signing-key` | local Swagger 토큰 서명 키(PKCS#8 PEM). local 프로필만 사용 |

- JWT 키 두 파일은 짝이다. 하나라도 없으면 생성 도구가 둘 다 새로 만든다. 짝이 맞지 않으면 local 멤버 앱이 기동하지 않고 `generateLocalSecrets` 실행을 안내한다.
- IDE·bootRun은 서비스 configtree와 내부 키 configtree를 읽는다. Compose는 파일을 `/run/secrets`에 마운트한다.
- 생성 도구는 값 대신 파일 이름만 출력한다. 환경변수에는 비밀값 대신 위치를 넣는다.
- AI는 `secrets/`를 열지 않는다(AGENTS.md). 생성 도구·실행 앱이 파일을 사용한다.
- 테스트는 실행 중에 만든 RSA 키 쌍과 토큰만 쓴다(`TestJwt`). 키·토큰 문자열을 소스·파일에 남기지 않는다.
- DB 비밀번호는 기존 데이터 볼륨 초기화 이후 자동 교체하지 않는다.
- Docker 빌드 허용 목록에서 secrets·AI 로그·Git·테스트·캐시를 제외한다.

<a id="pol-sec-005"></a>

## POL-SEC-005 Swagger 자동 인증 (local 프로필)

- local 프로필에서만 `LocalAccessTokenIssuer`가 init 단계에서 만든 서명 키로 짧은 만료(기본 30분, 1분~12시간) 토큰을 요청마다 서명한다. 고정 토큰 문자열은 파일·소스·이미지에 두지 않는다.
- 토큰: RS256, `iss`·`aud`는 검증 설정과 같고 `sub=local-swagger`, scope `member.read group.read`, `iat`·`nbf`·`exp`·`jti`.
- `/swagger-ui/swagger-initializer.js` 앞에 `window.taskSwaggerSettings`(토큰·만료 시각·그룹 중계 사용 여부)를 붙이고 `Cache-Control: no-store`로 보낸다. Swagger는 `preauthorizeApiKey("bearerAuth", ...)`로 자동 인증하며 만료 시각을 화면에 알린다. 만료되면 새로 고침으로 새 토큰을 받는다.
- 기본(운영) 프로필은 서명 키를 읽지 않고 토큰을 넣지 않는다(`accessToken: null`). 운영 토큰은 외부 발급 서버가 만든다.

<a id="pol-sec-006"></a>

## POL-SEC-006 그룹 서비스 접근 통제

- 그룹 앱의 호스트 포트는 공개하지 않는다. 루트 Compose의 앱 통신 네트워크에는 두 앱만 연결한다.
- DB 네트워크는 각 서비스 소유이며 앱 통신 네트워크에 DB를 연결하지 않는다.
- 네트워크는 추가 방어다. Docker 관리 권한·같은 호스트의 관리자 접근까지 차단한다고 보장하지 않는다.
- IDE·bootRun은 앱을 127.0.0.1로 바인딩한다. 컨테이너는 0.0.0.0으로 바인딩하되 그룹 ports는 없다.
- 그룹 업무 API와 명세에는 내부 키가 필요하다.
- 사용자 진입점에서 그룹 조회에 닿는 유일한 경로는 멤버의 Swagger 실행 중계다. 중계와 그룹 명세 전달은 `task.group-proxy.enabled`(기본 `false`, local `true`)가 켜졌을 때만 등록하고, 중계는 JWT scope `group.read`를 요구한다. 고정 GET 경로만 제공하고 클라이언트의 내부 인증 헤더·원격 URL은 전달하지 않는다. 브라우저용 명세에는 중계 주소만 남긴다.

<a id="pol-sec-007"></a>

## POL-SEC-007 요청 제한

- 멤버의 `/api/`·`/internal-api/` 경로는 인증된 JWT `sub`별 토큰 버킷으로 제한한다. 기본 용량 100, 초당 50 보충(`task.rate-limit.*`). 초과하면 429 `TOO_MANY_REQUESTS`와 `Retry-After`(초, 1 이상)다.
- 순서: 요청 추적 → GET 전용 → Spring Security → 요청 제한 → MVC. 인증 실패는 제한 전에 401로 끝나고, 상태 확인·명세·Swagger는 제한하지 않는다.
- 서버 한 대의 메모리에서만 센다. 추적 호출자 상한(`max-clients`, 기본 10000)에 닿으면 가득 찬 유휴 버킷부터 지운다. 여러 인스턴스를 묶는 분산 제한과 IP 기준 제한은 앞단 게이트웨이 책임이다.
- 그룹 서비스는 내부 호출만 받으므로 제한하지 않는다. 멤버의 원격 호출 제한 시간·재시도가 그룹 부하를 제한한다([통신 정책](service-communication.md)).
