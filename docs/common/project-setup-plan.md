# SETUP-001 프로젝트 구성 계획

| 항목 | 내용 |
| --- | --- |
| 목적 | 멤버·그룹 마이크로서비스의 프로젝트 구성과 공통 정책을 갖추고, 두 서비스를 각각 독립 host로 기동한다 |
| 범위 | 저장소·빌드·서비스 골격·서비스별 인프라·공통 정책 문서 |
| 작업 ID·브랜치 | `SETUP-001`, `feature/SETUP-001` (커밋 `[SETUP-001] 제목`) |
| 관련 문서 | [README](README.md)(공통 문서 목록), [local-development](local-development.md)(실행 방법) |

## 1. 범위와 완료 목표

- **범위: 프로젝트 구성 + host 기동.**
  - 세부 기능(엔티티·조회 API·실제 서비스 간 조회)은 이후 작업에서 개발한다.
  - 2026-11-04에 조회·연동·통합 Compose·배포까지 넣은 확장안을 검토했다. 사용자가 이 범위로 다시 확정했고, 확장안 내용은 8절에 '이후 작업'으로만 남긴다.
- **완료 목표**: 멤버 서비스와 그룹 서비스가 각각 독립 host로 기동한다.
  - 전용 DB에 연결하고 health가 UP이다.
  - 인증과 오류 응답이 공통 정책대로 동작한다.
- **원 요구사항**
  - 메시지 브로커 없는 마이크로서비스 2개(멤버 조회·그룹 조회)
  - 서비스별 별도 DB·스키마, 서비스 간 직접 참조 불가(네트워크 API 호출만)
  - 멀티 모듈, 각자 구동, 서비스별 인프라는 Docker
  - 공통 문서: 코드 컨벤션, 포매터(Google), CQRS, 정책서, 엔티티 문서, API 정의서. Swagger로 API 명세
- **문서 위치**
  - 공통 정책: `docs/common/`
  - 서비스 문서: `services/*/docs/`. 이번에는 개요와 실행 방법만 쓰고, 정책·엔티티·API 문서는 기능 개발 때 쓴다.

## 2. 확정 사항

| 항목 | 확정 |
| --- | --- |
| JDK | 21 ([ADR-001](decisions/ADR-001-jdk-21.md)). Gradle toolchain 21, 실행 이미지 `eclipse-temurin:21.0.13_9-jre-noble` |
| 기술 | [technology-stack](technology-stack.md). Spring Boot 4.1.2, Kotlin 2.3.21, Gradle 9.3.0 등. 2026-11-04에 공식 저장소·문서로 확인 |
| 페이지네이션 | 기본 20, 최대 100 ([POL-API-003](api-conventions.md#pol-api-003-페이지네이션)) |
| 서비스 간 호출 | 연결 2초, 응답 3초. 연결 실패·시간 초과인 조회만 1회 재시도. 실패를 빈 결과로 숨기지 않음. 서킷 브레이커는 다루지 않음 ([service-communication](service-communication.md)) |
| 오류 응답 | RFC 9457 ProblemDetail + `code` ([POL-API-002](api-conventions.md#pol-api-002-오류-응답)) |
| API 버전 | 경로·헤더에 두지 않음 ([POL-API-001](api-conventions.md#pol-api-001-api-버전)) |
| 인증 | JWT Bearer. 미리 생성한 고정 키·토큰 사용, local 프로필 Swagger 자동 인증 ([security](security.md)) |
| 포트 | 서비스마다 모두 다르게 (아래 표). 두 서비스가 같은 호스트 포트를 쓰지 않음 |
| 공통 코드 | 공유 런타임 모듈 없음. 공통은 정책 문서와 `build-logic`(빌드 규칙)뿐. 서비스 간 `project(...)` 의존 금지 ([ADR-002](decisions/ADR-002-no-shared-module.md)) |
| 커밋 | `[SETUP-001] 제목` + 빈 줄 + 두 칸 들여쓴 본문 ([commit-conventions](commit-conventions.md)) |

| 서비스 | 앱 포트 | DB 호스트 포트 | DB·스키마 | 앱 계정 | Compose 프로젝트 | 볼륨 |
| --- | --- | --- | --- | --- | --- | --- |
| member-service | 8081 | 127.0.0.1:15432 | `member_service` | `member_app` | `task-member` | `member-db-data` |
| group-service | 8082 | 127.0.0.1:25432 | `group_service` | `group_app` | `task-group` | `group-db-data` |

- 앱 포트(`server.port`)는 bootRun과 컨테이너에서 같다.
- DB 컨테이너 내부 포트는 둘 다 5432다(컨테이너가 달라 충돌 없음).

## 3. 저장소 구성

```text
settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew(.bat), gradle/
  libs.versions.toml            버전 고정 위치
  wrapper/                      Gradle 9.3.0 (배포 파일 SHA-256 고정)
build-logic/                    공통 빌드 규칙(컨벤션 플러그인)
  task.spring-service           JDK 21·Kotlin·Spring·JPA·Spotless·서비스 독립성 검사·bootJar 이름
  task.root                     빌드 스크립트 포맷 검사, generateLocalSecrets·rotateLocalJwt
docs/common/                    공통 정책 문서
services/<서비스>/
  build.gradle.kts, Dockerfile, .dockerignore
  infra/compose.yaml            서비스 단독 실행(DB 기본, --profile app 으로 앱 포함)
  infra/postgres/initdb/        전용 DB 초기화(앱 계정·스키마)
  docs/README.md                서비스 개요·실행 방법
  src/main/kotlin/task/<서비스>/  presentation·application·domain·infrastructure (cqrs.md)
secrets/                        로컬 비밀값(Git 제외, AI 미열람). generateLocalSecrets가 만든다
```

## 4. 단계와 결과

| 단계 | 작업 | 통과 기준 | 결과 |
| --- | --- | --- | --- |
| 0 | (사용자) `new-feature SETUP-001` | `feature/SETUP-001` 생성 | 완료 |
| 1 | 이 계획 문서 | 범위·확정 사항 반영 | 완료 |
| 2 | 공통 정책 문서 | 문서 목록·정책 ID·머리말 일관 | 완료 |
| 3 | Gradle·`build-logic`·포맷·버전 고정·`.gitattributes` | `./gradlew build`(포맷 검사 포함) 통과 | 완료 |
| 4 | 키·토큰·DB 비밀번호 생성 도구 | 값 출력 없이 `secrets/` 생성, Git·Docker 컨텍스트 제외 | 완료 |
| 5 | 멤버 서비스 골격·전용 인프라 | 단독 기동, health UP, 인증·오류 응답 확인 | 완료 |
| 6 | 그룹 서비스 골격·전용 인프라·멤버 클라이언트 설정 | 멤버 서비스 없이 기동, 설정 검증 통과 | 완료 |
| 7 | 동시 실행·충돌·문서 일치 검증 | 5절 완료 기준 | 완료 |
| 8 | (사용자) develop 병합·검증 → 보완 → `push-develop` → `merge-feature` → master push | 저장소 브랜치 규칙(AGENTS.md) | 남음 |

## 5. 완료 기준과 확인 결과 (2026-11-04, 로컬 Windows·Docker Desktop)

| # | 기준 | 확인 방법 | 결과 |
| --- | --- | --- | --- |
| 1 | JDK 21 toolchain에서 `./gradlew build`(포맷 검사·테스트) 통과 | `./gradlew build` | 통과. 테스트 26개(멤버 10, 그룹 16) |
| 2 | 각 서비스가 자기 Compose만으로(앱+DB), 그리고 bootRun + Docker DB로 기동, health UP | 두 방식 모두 실행. 상태 확인·인증·문서 확인 스크립트 9개 항목 | 두 방식 × 두 서비스 모두 9/9 |
| 3 | 앱·DB 포트가 서비스별로 다르고, 동시 실행 시 포트·이름·볼륨 충돌이 없으며, 한쪽 종료가 다른 쪽에 영향 없음 | 두 Compose 프로젝트 동시 실행 → 멤버 `down` → 그룹 상태 확인 | 충돌 없음. 멤버 중단 뒤에도 그룹 health·readiness UP |
| 4 | 서비스별 DB·스키마·계정·볼륨·Flyway 이력 분리 | DB별 역할·스키마 소유자·Flyway 이력, 볼륨·네트워크 이름 조회 | `member_app`/`group_app`(슈퍼유저 아님)이 자기 스키마 소유, Flyway V1 각자 적용, 볼륨·네트워크는 프로젝트별, 그룹 컨테이너는 멤버 DB 이름을 해석하지 못함 |
| 5 | 서비스 간 `project` 의존 없음(빌드 검사로 강제) | `build-logic`의 project 의존 검사 | 의존이 생기면 구성 단계에서 빌드 실패 |
| 6 | 토큰이 없으면 401 ProblemDetail, 유효한 고정 토큰이면 통과, local Swagger UI는 자동 인증 | 테스트와 실행 확인 | 401(`UNAUTHORIZED`, `WWW-Authenticate: Bearer`). 고정 토큰 통과. aud·만료·서명이 틀리면 401. Swagger 초기화 스크립트에 토큰 주입(`no-store`) |
| 7 | 오류 응답 ProblemDetail + `code`, 페이지네이션 기본 20 / 최대 100 | 테스트 전용 API로 400·404·405·500·검증 실패 확인 | 통과. 500 응답에 내부 메시지 없음 |
| 8 | 그룹의 멤버 클라이언트 설정이 기동 시 검증되고, 멤버 서비스가 꺼져 있어도 그룹 서비스 기동 | 단위 테스트, 그룹 컨테이너에서 멤버 호출 | 주소·토큰이 없으면 기동 실패. 시간 초과만 1회 재시도, 5xx·POST는 재시도 안 함. 서비스 토큰은 멤버에서 통과하고 그룹 자신에서는 401(aud 검증) |
| 9 | 키·토큰·DB 비밀번호가 저장소·이미지·빌드 컨텍스트에 없음 | `.gitignore`, 이미지 `/app` 목록, 컨테이너 환경변수 이름 | `secrets/` Git 제외. 이미지에는 `app.jar`만 있음. 환경변수에는 파일 위치만 있고 값은 Compose secrets 파일로만 전달 |
| 10 | 포맷·빌드 작업이 `ai-logs/`와 로깅 상태를 바꾸지 않음 | 빌드 전후 `git status ai-logs` | 변경 없음. 포맷 대상에 `ai-logs/`·`scripts/` 없음 |

## 6. 구현 중 결정

- **비밀값 전달은 파일로만 한다.**
  - Spring은 configtree로 읽는다(파일 이름 = 속성 이름). PostgreSQL은 `POSTGRES_PASSWORD_FILE`을 쓴다.
  - 값이 환경변수나 `docker compose config` 출력에 나오지 않는다.
  - `.env` 파일을 쓰지 않으므로 AI 미열람 규칙과도 충돌하지 않는다.
- **configtree 위치는 절대 경로로 준다.**
  - `..`이 든 상대 경로를 주면 configtree가 속성을 하나도 읽지 않았다(실행 확인 중 발견).
  - 그래서 bootRun은 `secrets/`의 절대 경로를 환경변수로 넘긴다. 위치가 없으면 기동하지 않는다.
- **DB 계정**
  - 관리자 계정(`postgres`)은 DB 컨테이너만 안다.
  - 초기화 스크립트가 앱 계정과 그 계정이 소유한 스키마를 만든다. 앱 계정은 슈퍼유저가 아니다.
- **보안 필터·오류 처리기·Swagger 설정은 서비스마다 따로 있다.**
  - 공유 모듈을 두지 않기로 한 결정([ADR-002](decisions/ADR-002-no-shared-module.md))에 따른 중복이다.
  - 규칙은 공통 문서 하나로 관리한다.

## 7. 이번 범위 제외 (세부 개발 단계)

- 엔티티·테이블·시드 데이터, 조회 API, 서비스별 정책·엔티티·API 문서
- 실제 서비스 간 조회, 통합 Compose(`infra/compose.integration.yaml`), 계약 호환성 테스트
- 아키텍처 계층 테스트, 정적 분석, CI
- 운영용 토큰 발급 서버(지금은 고정 키·토큰 방식)

## 8. 이후 작업에서 구체화할 항목 (2026-11-04 확장안 검토 결과, 이번에는 구현하지 않음)

- **확장안 범위**
  - 조회: 멤버 목록·상세·식별자 일괄 조회, 그룹 목록·상세·소속 멤버 조회
  - 연동: 그룹 → 멤버 HTTP 연동, 연결 실패·시간 초과·없는 식별자 처리 검증
  - 서비스 문서: 정책·엔티티·API·`openapi.yaml`·`operations.md`
  - 통합 검증 환경(`task-integration`)
  - 운영: 서비스별 이미지 태그·배포·롤백, 계약 호환성 검증
- **확장안의 목표 구조(참고)**
  - `docs/README.md`
  - `services/*/infra/{README.md, compose.yaml, compose.local.yaml, postgres/}`
  - `services/*/docs/{README.md, policies.md, entities.md, api.md, openapi.yaml, operations.md}`
  - `infra/{README.md, compose.integration.yaml, compose.integration.override.yaml}`
- **검토에서 나온 구현 이슈**
  1. 서비스 기본 Compose의 호스트 포트를 통합 환경이 include로 물려받으면 단독 실행과 충돌한다. 호스트 포트를 로컬·통합 덮어쓰기 파일로 옮길지 정한다.
  2. include한 서비스를 같은 이름으로 다시 정의하면 충돌 오류가 난다. 통합용 덮어쓰기는 include 항목의 `path` 목록으로 적용하고 `docker compose config`로 확인한다.
  3. 그룹 서비스 readiness에 멤버 서비스 상태를 넣지 않는다. 지금 구성도 자기 DB만 본다.
  4. `openapi.yaml`은 실행 중인 `/v3/api-docs`에서 만들고, 실제와 어긋나면 실패하는 검사를 둔다.
  5. 검증용 데이터는 Flyway 기본 경로가 아닌 local·integration 프로필 전용 경로로 분리한다.
  6. 남은 결정: 일괄 조회 식별자 개수 상한(100 권장), 없는 `memberId` 표시 방식, 계약 호환성 검증 수단, 배포·롤백 대상 환경.
