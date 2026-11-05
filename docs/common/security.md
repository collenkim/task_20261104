# 보안

| 항목 | 내용 |
| --- | --- |
| 분류 | 공통 기준 |
| 상태 | 현재 · 적용 예정 사항은 해당 절에서 구분 |
| 목적 | 사용자 무인증 범위와 그룹 내부 호출 키·비밀 파일 관리 기준 |
| 범위 | 멤버·그룹 HTTP 요청, 로컬 실행 |
| 관련 코드 | 그룹 InternalServiceConfiguration, 멤버 GroupClientConfiguration, LocalSecretsTask |

## POL-SEC-001 인증 방식

- 사용자 로그인·JWT·역할·권한은 이번 범위에서 제외한다. 멤버 API는 사용자 토큰 없이 호출한다.
- 멤버 → 그룹 요청에 `X-Internal-Service-Id`와 `X-Internal-Service-Key`를 추가한다. 그룹은 등록 호출자의 키와 함께 비교한다.
- 미등록 호출자·누락·불일치·중복 헤더·IP 범위 위반은 403 `INTERNAL_ACCESS_DENIED` ProblemDetail이다. 키 값은 응답·로그에 쓰지 않는다.
- 두 앱은 필수 키 설정이 없으면 기동하지 않는다. 값 비교는 SHA-256 고정 길이 해시를 `MessageDigest.isEqual`로 수행한다.
- 이 방식은 공유 키 소유를 확인한다. 특정 프로세스의 신원을 증명하는 방식은 아니다.
- 키가 평문 HTTP를 암호화하지는 않는다. 로컬 컨테이너 통신 외의 호스트 간 배포에는 HTTPS를 사용한다.

## POL-SEC-002 인증 없이 여는 경로

| 경로 | 멤버 | 그룹 |
| --- | --- | --- |
| 업무 API | 사용자 인증 없음 | 내부 키 필요 |
| GET/HEAD `/actuator/health`, `/actuator/health/**` | 키 없음 | 키 없음 |
| `/v3/api-docs`, `/v3/api-docs/**` | 키 없음 | 내부 키 필요 |
| `/swagger-ui.html`, `/swagger-ui/**` | 키 없음 | 제공하지 않음 |
| GET `/internal-api/group/api/groups/summary`, `/internal-api/group/api/group/{memberId}` | 키 없음. 멤버 서버가 등록 호출자 키를 추가 | 제공하지 않음 |
| GET `/internal-docs/group` | 키 없음. 멤버 서버가 그룹 호출에 키 추가 | 제공하지 않음 |
| `/error`, 오류 디스패치 | 키 없음 | 키 없음 |

그룹에서 존재하지 않는 경로도 필터를 먼저 거친다. 키 검사 제외 여부는 컨테이너가 해석한 경로로 판단하며 점 경로를 health 예외로 허용하지 않는다. 키 없는 Swagger 요청은 403, 정상 키를 보낸 Swagger 요청은 404다. health의 응답에는 상세 정보를 공개하지 않는다.

## POL-SEC-003 내부 호출 키

- 멤버 설정: `task.group-client.service-id=member-service`, `task.internal-service.key`.
- 그룹 설정: `task.internal-service.clients.<서비스ID>.key`와 선택 `allowed-cidrs` 목록. 다른 서비스 추가는 호출자 설정·별도 키 배포로 진행한다.
- local은 기존 secrets/internal/task.internal-service.key를 clients.member-service.key로 연결한다. Compose/IDE/bootRun에 같은 local 매핑이 적용되며 기본 프로필은 호출자별 설정을 명시한다.
- CIDR은 숫자 IPv4/IPv6만 받고 잘못된 설정은 기동 실패다. 소켓 remoteAddr로 검사하며 X-Forwarded-For는 신뢰하지 않는다. 생략 시 IP 제한 없이 호출자·키를 검사한다.
- 파일: `secrets/internal/task.internal-service.key`. 생성 도구가 32바이트 난수의 Base64URL 값을 만든다.
- 두 서버만 파일로 읽는다. 브라우저·Swagger·이미지·소스·Git에 값을 넣지 않는다.
- 멤버 클라이언트는 설정한 키로 헤더를 덮어쓴다. 사용자 인증 헤더를 그룹에 전달하지 않는다. 검증한 X-Request-Id와 서버 생성 X-Internal-Call-Id·X-Internal-Attempt만 추적용으로 전파한다.
- 리다이렉트를 따라 키를 다른 주소로 보내지 않는다.

## POL-SEC-004 키·비밀번호

| 명령·파일 | 동작 |
| --- | --- |
| `./gradlew generateLocalSecrets` | 없는 내부 키·DB 비밀번호만 생성. 기존 값 유지 |
| `./gradlew rotateInternalServiceKey` | 내부 키만 교체. 두 앱을 재시작해야 적용 |
| `secrets/<서비스>/db-admin-password` | DB root 전용. 앱에 전달하지 않음 |
| `secrets/<서비스>/config/spring.datasource.password` | 해당 DB 앱 계정 비밀번호 |
| `secrets/internal/task.internal-service.key` | 두 앱의 내부 호출 키 |

- IDE·bootRun은 서비스 configtree와 내부 키 configtree를 읽는다. Compose는 파일을 `/run/secrets`에 마운트한다.
- 생성 도구는 값 대신 파일 이름만 출력한다. 환경변수에는 비밀값 대신 위치를 넣는다.
- AI는 `secrets/`를 열지 않는다(AGENTS.md). 생성 도구·실행 앱이 파일을 사용한다.
- JWT 파일은 생성·사용하지 않는다. 기존 로컬 JWT 파일을 자동으로 삭제하거나 읽지 않는다. 사용자가 필요에 따라 정리한다.
- DB 비밀번호는 기존 데이터 볼륨 초기화 이후 자동 교체하지 않는다.
- Docker 빌드 허용 목록에서 secrets·AI 로그·Git·테스트·캐시를 제외한다.

## POL-SEC-005 Swagger 자동 인증 (local 프로필)

**폐기**: 사용자 토큰 주입을 제거했다. 현재 Swagger는 키 입력 없이 명세를 조회하고 멤버 API를 실행한다. 그룹 조회도 멤버 중계로 실행 가능하다([API 문서](api-conventions.md#pol-api-004-api-문서)).

## POL-SEC-006 그룹 서비스 접근 통제

- 그룹 앱의 호스트 포트는 공개하지 않는다. 루트 Compose의 앱 통신 네트워크에는 두 앱만 연결한다.
- DB 네트워크는 각 서비스 소유이며 앱 통신 네트워크에 DB를 연결하지 않는다.
- 네트워크는 추가 방어다. Docker 관리 권한·같은 호스트의 관리자 접근까지 차단한다고 보장하지 않는다.
- IDE·bootRun은 앱을 127.0.0.1로 바인딩한다. 컨테이너는 0.0.0.0으로 바인딩하되 그룹 ports는 없다.
- 그룹 업무 API와 명세에는 내부 키가 필요하다. 명세는 멤버가 받아 공개한다.

그룹 업무 API의 서버 간 인증은 유지하며 두 조회의 멤버 중계는 사용자 무인증 범위다. 고정 GET 경로만 제공하고 클라이언트의 내부 인증 헤더·원격 URL은 전달하지 않는다. 브라우저용 명세에는 중계 주소만 남긴다.
