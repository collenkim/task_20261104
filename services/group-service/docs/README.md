# 그룹 서비스

| 항목 | 내용 |
| --- | --- |
| 목적 | 그룹 서비스의 책임·구성·실행 방법을 정리한다 |
| 범위 | `services/group-service/` |
| 관련 문서 | [공통 문서](../../../docs/common/README.md), [로컬 실행](../../../docs/common/local-development.md), [서비스 간 통신](../../../docs/common/service-communication.md) |

## 책임

- 그룹 정보와 소속 관계(그룹 ↔ 멤버 식별자)를 소유하고 조회 API를 제공한다.
- 멤버 정보가 필요하면 멤버 서비스 API를 호출한다. 멤버 데이터는 식별자만 저장한다.

## 구성

| 항목 | 값 |
| --- | --- |
| Gradle 프로젝트 | `:group-service` → `build/libs/group-service.jar` |
| 패키지 | `task.group` |
| 앱 포트 | 8082 |
| DB | `group-db`(PostgreSQL 18.6), DB·스키마 `group_service`, 앱 계정 `group_app`, 호스트 포트 127.0.0.1:25432 |
| Compose | `infra/compose.yaml`, 프로젝트 `task-group`, 볼륨 `group-db-data`, 네트워크 `group-db-net` |
| 이미지 | `task/group-service:local` (`Dockerfile`, 실행 사용자 `app`) |
| JWT `aud` | `group-service` |
| DB 접속 환경변수 | `GROUP_DB_HOST`(기본 `localhost`), `GROUP_DB_PORT`(기본 `25432`) |
| 멤버 서비스 주소 | `TASK_MEMBER_CLIENT_BASE_URL` (local 기본 `http://localhost:8081`, 컨테이너 기본 `http://host.docker.internal:8081`) |

현재 코드(SETUP-001): 공통 정책 구현과 멤버 서비스 클라이언트 설정만 있다.

| 경로 | 내용 |
| --- | --- |
| `presentation/error/` | ProblemDetail + `code` 오류 응답 |
| `infrastructure/configuration/` | JWT 인증, 401·403 응답, OpenAPI, local Swagger 자동 인증 |
| `infrastructure/client/` | 멤버 서비스 `RestClient`(연결 2초·응답 3초, 조회의 연결 실패·시간 초과만 1회 재시도, 서비스 간 토큰 자동 첨부) |
| `src/main/resources/db/migration/V1__init_schema.sql` | 스키마 설명만 있는 첫 마이그레이션 |

- 멤버 서비스가 꺼져 있어도 기동한다. readiness는 자기 DB만 본다.

## 실행

```powershell
./gradlew generateLocalSecrets                                     # 처음 한 번

# 방식 1: DB는 Docker, 앱은 bootRun
docker compose -f services/group-service/infra/compose.yaml up -d
./gradlew :group-service:bootRun

# 방식 2: 앱과 DB 모두 Docker
./gradlew :group-service:bootJar
docker compose -f services/group-service/infra/compose.yaml --profile app up -d --build

# 종료(볼륨 유지)
docker compose -f services/group-service/infra/compose.yaml --profile app down
```

- 확인: http://localhost:8082/actuator/health, http://localhost:8082/swagger-ui.html
- 테스트: `./gradlew :group-service:test` (Docker 필요)

## 이후 작업에서 쓸 문서

- `policies.md`(서비스 정책), `entities.md`(엔티티·테이블), `api.md`(API 정의서): 조회 기능을 만들 때 쓴다.
- 멤버 서비스 호출 실패·없는 식별자 처리 방식은 서비스 간 조회를 만들 때 정한다.
