# 멤버 서비스

| 항목 | 내용 |
| --- | --- |
| 목적 | 멤버 서비스의 책임·구성·실행 방법을 정리한다 |
| 범위 | `services/member-service/` |
| 관련 문서 | [공통 문서](../../../docs/common/README.md), [로컬 실행](../../../docs/common/local-development.md) |

## 책임

- 멤버 정보를 소유하고 조회 API를 제공한다.
- 다른 서비스를 호출하지 않는다. 그룹 서비스가 이 서비스의 API를 호출한다(서비스 간 토큰, `aud=member-service`).

## 구성

| 항목 | 값 |
| --- | --- |
| Gradle 프로젝트 | `:member-service` → `build/libs/member-service.jar` |
| 패키지 | `task.member` |
| 앱 포트 | 8081 |
| DB | `member-db`(PostgreSQL 18.6), DB·스키마 `member_service`, 앱 계정 `member_app`, 호스트 포트 127.0.0.1:15432 |
| Compose | `infra/compose.yaml`, 프로젝트 `task-member`, 볼륨 `member-db-data`, 네트워크 `member-db-net` |
| 이미지 | `task/member-service:local` (`Dockerfile`, 실행 사용자 `app`) |
| JWT `aud` | `member-service` |
| DB 접속 환경변수 | `MEMBER_DB_HOST`(기본 `localhost`), `MEMBER_DB_PORT`(기본 `15432`) |

현재 코드(SETUP-001): 공통 정책 구현만 있다.

| 경로 | 내용 |
| --- | --- |
| `presentation/error/` | ProblemDetail + `code` 오류 응답 |
| `infrastructure/configuration/` | JWT 인증, 401·403 응답, OpenAPI, local Swagger 자동 인증 |
| `src/main/resources/db/migration/V1__init_schema.sql` | 스키마 설명만 있는 첫 마이그레이션 |

## 실행

```powershell
./gradlew generateLocalSecrets                                     # 처음 한 번

# 방식 1: DB는 Docker, 앱은 bootRun
docker compose -f services/member-service/infra/compose.yaml up -d
./gradlew :member-service:bootRun

# 방식 2: 앱과 DB 모두 Docker
./gradlew :member-service:bootJar
docker compose -f services/member-service/infra/compose.yaml --profile app up -d --build

# 종료(볼륨 유지)
docker compose -f services/member-service/infra/compose.yaml --profile app down
```

- 확인: http://localhost:8081/actuator/health, http://localhost:8081/swagger-ui.html
- 테스트: `./gradlew :member-service:test` (Docker 필요)

## 이후 작업에서 쓸 문서

- `policies.md`(서비스 정책), `entities.md`(엔티티·테이블), `api.md`(API 정의서): 조회 기능을 만들 때 쓴다.
