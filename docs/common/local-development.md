# 로컬 실행

| 항목 | 내용 |
| --- | --- |
| 목적 | 개인 PC에서 두 서비스를 각각 독립 host로 기동하는 방법을 정리한다 |
| 범위 | Windows·macOS·Linux 로컬 개발 환경(명령 예시는 PowerShell 기준, Git Bash에서도 같음) |
| 관련 코드 | `services/*/infra/compose.yaml`, `build-logic/src/main/kotlin/task.spring-service.gradle.kts`(bootRun), `task.root.gradle.kts`(비밀값 생성) |

## 준비

| 항목 | 조건 |
| --- | --- |
| JDK | 21. Gradle toolchain이 21을 찾는다(없으면 설치된 JDK 21 위치를 `org.gradle.java.installations.paths`로 지정) |
| Docker | Docker Desktop 등 Compose v2. 서비스 DB·컨테이너 실행과 테스트(Testcontainers)에 필요 |
| 포트 | 8081, 8082, 15432, 25432가 비어 있어야 함 |

처음 한 번 비밀값을 만든다. 값은 화면에 나오지 않고 `secrets/`에 파일로만 생긴다.

```powershell
./gradlew generateLocalSecrets
```

## 포트와 이름

| 서비스 | 앱 | DB(호스트) | Swagger UI | Compose 프로젝트 |
| --- | --- | --- | --- | --- |
| member-service | http://localhost:8081 | 127.0.0.1:15432 | http://localhost:8081/swagger-ui.html | `task-member` |
| group-service | http://localhost:8082 | 127.0.0.1:25432 | http://localhost:8082/swagger-ui.html | `task-group` |

- 포트는 서비스마다 모두 다르다. 두 서비스를 동시에 실행해도 충돌하지 않는다.
- 상태 확인: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`.

## 방식 1: DB는 Docker, 앱은 bootRun

```powershell
# 멤버 서비스
docker compose -f services/member-service/infra/compose.yaml up -d
./gradlew :member-service:bootRun

# 그룹 서비스 (다른 터미널)
docker compose -f services/group-service/infra/compose.yaml up -d
./gradlew :group-service:bootRun
```

- bootRun은 `local` 프로필로 실행하고, `secrets/`의 절대 경로를 환경변수 `TASK_SECRETS_DIR`·`TASK_JWT_PUBLIC_KEY`로 넘긴다.
  - 다른 프로필: `SPRING_PROFILES_ACTIVE` 환경변수로 바꾼다.
- 그룹 서비스는 멤버 서비스가 꺼져 있어도 기동한다. 멤버 서비스 주소 기본값은 `http://localhost:8081`이다.

## 방식 2: 앱과 DB 모두 Docker

```powershell
./gradlew :member-service:bootJar
docker compose -f services/member-service/infra/compose.yaml --profile app up -d --build

./gradlew :group-service:bootJar
docker compose -f services/group-service/infra/compose.yaml --profile app up -d --build
```

- 이미지는 `build/libs/<서비스>.jar`만 담는다. 코드를 바꾸면 `bootJar`부터 다시 실행한다.
- 컨테이너는 비밀값을 Compose secrets(`/run/secrets/...`)로 받는다.
- 그룹 컨테이너는 멤버 서비스를 `http://host.docker.internal:8081`로 호출한다. 바꾸려면 환경변수 `TASK_MEMBER_CLIENT_BASE_URL`을 준다.
- 상태: `docker compose -f services/<서비스>/infra/compose.yaml --profile app ps` (앱 컨테이너 healthcheck가 readiness를 확인한다).

## 종료와 초기화

```powershell
docker compose -f services/member-service/infra/compose.yaml --profile app down   # 볼륨(DB 데이터)은 남는다
```

- 한 서비스를 종료해도 다른 서비스의 컨테이너·볼륨·네트워크는 그대로다.
- `down -v`는 그 서비스의 DB 볼륨을 지운다. DB를 처음 상태로 되돌릴 때만 쓴다.
- **DB 비밀번호를 바꾸려면** 볼륨을 다시 만들어야 한다. 초기화 스크립트와 관리자 비밀번호는 볼륨을 처음 만들 때만 적용된다.
  1. `down -v`로 그 서비스 볼륨을 지운다.
  2. `secrets/<서비스>/db-admin-password`, `secrets/<서비스>/config/spring.datasource.password`를 지운다.
  3. `./gradlew generateLocalSecrets`로 다시 만들고 기동한다.
- JWT 키·토큰 교체: `./gradlew rotateLocalJwt` 후 두 서비스를 재시작한다.

## API 직접 호출

- Swagger UI(local 프로필)는 자동으로 인증된다. 바로 **Try it out**으로 호출한다.
- 명령줄에서 호출할 때는 토큰 파일을 변수로 읽어 헤더에 넣는다. 토큰 값을 화면·로그·명령 기록에 남기지 않는다.

  ```powershell
  $token = Get-Content -Raw secrets/member-service/config/task.security.swagger-token
  curl.exe -s -o NUL -w "%{http_code}`n" -H "Authorization: Bearer $token" http://localhost:8081/actuator/health
  ```

## IDE에서 실행

IntelliJ 등에서 `*ServiceApplication`을 직접 실행할 때는 실행 구성에 환경변수를 넣는다(값이 아니라 파일 위치).

| 환경변수 | 값 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local` |
| `TASK_SECRETS_DIR` | `<저장소 절대 경로>/secrets/<서비스>/config` |
| `TASK_JWT_PUBLIC_KEY` | `<저장소 절대 경로>/secrets/jwt/public-key.pem` |

- `TASK_SECRETS_DIR`은 절대 경로로 준다. `..`이 든 경로는 configtree가 읽지 못한다.
- 위치가 없으면 `TASK_JWT_PUBLIC_KEY` 자리표시자를 풀지 못해 기동하지 않는다.

## 자주 나는 오류

| 증상 | 원인·조치 |
| --- | --- |
| `local 프로필에는 Swagger 토큰이 필요합니다` | `secrets/`가 없음 → `./gradlew generateLocalSecrets` |
| `Could not resolve placeholder 'TASK_JWT_PUBLIC_KEY'` | IDE 실행에 환경변수 없음 → 위 표대로 설정 |
| DB 접속 실패(`password authentication failed`) | 볼륨을 만든 뒤 비밀번호 파일이 바뀜 → "종료와 초기화"의 비밀번호 교체 절차 |
| 테스트가 Docker 오류로 실패 | Docker가 꺼져 있음 → Docker 실행 후 다시 테스트 |
| `Port 8081 was already in use` | 같은 서비스가 이미 실행 중(bootRun·컨테이너 중 하나만 실행) |
