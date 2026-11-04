# SETUP-001 프로젝트 구성 계획

| 항목 | 내용 |
| --- | --- |
| 목적 | 멤버·그룹 서비스의 독립 실행 기반과 요구 문서를 구성한다 |
| 범위 | Gradle 멀티 모듈, JPA, 서비스별 Docker 인프라, Swagger, 공통·서비스 문서 |
| 작업 ID·브랜치 | `SETUP-001`, `feature/SETUP-001`; 커밋 형식은 현재 AGENTS.md 기준 |
| 관련 문서 | [기술 스택](technology-stack.md), [재검증 기록](setup-verification.md), [로컬 실행](local-development.md) |

## 1. 요구사항과 현재 범위

- Java 21, Kotlin 2.3 이상, Spring Boot 4.1.0 이상, Gradle 9 이상을 사용한다. 선정 버전은 기술 스택에 한곳에서 관리한다.
- 멤버·그룹 서비스는 각각 실행 산출물·host·DB 서버·DB/스키마·계정·볼륨·네트워크·Compose를 소유한다.
- 메시지 브로커와 서비스 간 코드/DB 직접 참조를 사용하지 않는다. 연동은 HTTP API로만 한다.
- 사용자는 멤버 서비스만 호출한다. 그룹 서비스는 멤버 서비스만 접근할 수 있는 내부 전용 서비스이고, 호출은 멤버 → 그룹 단방향이다([ADR-005](decisions/ADR-005-member-edge-group-internal.md)). 현재 구조와 검증 기준은 [구조 변경 계획](structure-change-plan.md)을 따른다.
- Spring Data JPA/Hibernate, Flyway, Swagger와 Google 스타일 포매터를 구성한다. CQRS는 서비스 내부 조회·변경 경계를 분리하는 방식으로 적용한다.
- 공통 규칙은 `docs/common/`, 서비스 정책·엔티티·API 정의는 `services/<서비스>/docs/`에서 관리한다.
- 이번 작업의 완료 대상은 실행 골격이다. 업무 조회 API·업무 엔티티·실제 멤버 → 그룹 조회 흐름은 아직 미구현이며 전체 서비스 기능 완료로 표시하지 않는다.
- DB는 MariaDB 11.8.9로 통일한다([ADR-004](decisions/ADR-004-mariadb.md)).

## 2. 실행·소유권

| 서비스 | 앱 | DB 호스트 포트 | DB = 스키마 | 앱 계정 | Compose | 새 데이터 볼륨 |
| --- | --- | --- | --- | --- | --- | --- |
| member-service | 8081 | 127.0.0.1:13306 | `member_service` | `member_app` | `task-member` | `member-mariadb-data` |
| group-service | 8082 (내부 전용: 호스트 미노출) | 127.0.0.1:23306 | `group_service` | `group_app` | `task-group` | `group-mariadb-data` |

- MariaDB는 서비스마다 별도 컨테이너이고 내부 포트는 각각 3306이다. DB와 SCHEMA는 같은 논리 공간이다.
- 서비스별 Compose는 기본적으로 자기 앱과 DB를 함께 띄운다. 루트 `docker compose up -d --build --wait`는 두 서비스와 DB를 일괄 실행한다. IDE 개발 시 DB 서비스 이름만 지정해 실행한다.
- 다른 서비스의 DB 네트워크에 참여하지 않고 다른 서비스에 `depends_on`을 걸지 않는다. 두 서비스의 readiness는 자기 DB만 확인한다.
- 공통 Compose나 공유 런타임 모듈을 필수로 두지 않는다. 공통 `build-logic`은 빌드 규칙만 공유한다.
- DB 관리자는 컨테이너 안에서만 사용한다. 앱과 Flyway는 자기 DB 범위의 앱 계정을 사용한다.
- 일괄 실행은 `task-local` 프로젝트로 서비스별 볼륨을 만든다. 단독 실행과는 별도 볼륨이며 모드 간 데이터 자동 복사는 하지 않는다.

## 3. 산출물

```text
settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew(.bat), gradle/
compose.yaml                    서비스 소유 Compose를 include하는 일괄 실행 진입점
compose.override.yaml           통합 앱 네트워크·고정 IP·그룹 주소
build-logic/                    JDK·Kotlin·JPA·포매터·서비스 직접 의존 차단
docs/README.md                  문서 시작점
docs/common/                    기술 스펙·공통 정책·계획·검증 결과
  decisions/                    결정 배경과 대안
services/<서비스>/
  build.gradle.kts, Dockerfile, Dockerfile.dockerignore
  infra/compose.yaml            자기 앱·MariaDB·볼륨·네트워크·비밀 파일 참조
  docs/README.md                서비스 구성·독립 실행
  docs/policies.md              소유권·적용 정책·미확정 업무 규칙
  docs/entities.md              현재 스키마·JPA 현황·후속 설계 기준
  docs/api.md                   제공 경로·아직 없는 업무 계약 구분
  src/main/                     실행 골격·DB 설정·MariaDB 마이그레이션
  src/test/                     공통 정책·JPA 왕복·클라이언트 설정 검증
secrets/                        로컬 실행 도구가 읽는 비밀값; Git 제외·AI 미열람
```

빈 업무 패키지나 실제 응답이 없는 API 계약을 만들어 완료로 취급하지 않는다. DB 변경은 `db/migration/mariadb/`에서만 관리한다.

## 4. 단계와 검증 기준

| 단계 | 수행 내용 | 통과 기준 |
| --- | --- | --- |
| 1 | 요구사항·기술 스택·문서 책임 정리 | 현재 설정과 문서가 일치하고 미구현 범위가 드러남 |
| 2 | Gradle·JPA·서비스 경계·Google 포매터 | 두 모듈 빌드·포맷 검사 통과, 서비스 간 project 의존 금지 |
| 3 | MariaDB 드라이버·Flyway·테스트 전환 | 실제 MariaDB에 Flyway 적용, JPA 저장·flush·clear·재조회 통과 |
| 4 | 서비스별 Compose와 실행 | 로컬 CLI/Engine 29.8.2·Compose 5.5.1에서 설정 검사·이미지 빌드·health 확인 |
| 5 | 독립성 검증 | 동시 실행 충돌 없음, 그룹 중단 중 멤버 readiness 유지, 각 DB/계정/볼륨 분리 |
| 6 | 문서·산출물 재검증 | 서비스 정책·엔티티·API 문서 존재, 현재/과거 결과 분리, 테스트 코드가 운영 jar에 없음 |
| 7 | develop 통합 검증·사용자 master 반영 | 아래 브랜치 흐름 준수. 로컬 검증만으로 develop/master 검증 완료를 선언하지 않음 |

실제 통과 여부·명령·제한은 [재검증 기록](setup-verification.md)에 적는다. 검증 결과는 현재 파일 구성과 실제 실행 명령을 기준으로 갱신한다.

## 5. 브랜치와 AI 기록

1. 신규 feature는 master 기준으로 생성한다. 현재 작업은 기존 `feature/SETUP-001`에서 보완한다.
2. feature 구현·검증 → develop 병합 → develop에서 기능 검증한다.
3. 보완이 필요하면 저장소 `resume` 절차로 같은 feature에 복귀해 최신 master를 반영하고 수정·검증 후 develop에 다시 병합한다.
4. develop의 로그 전용 커밋은 저장소 `push-develop` 절차로 동기화한다. develop 전체를 master에 병합하지 않는다.
5. 사용자만 `merge-feature`로 검증된 feature를 master에 병합하고 master를 push한다. 자세한 권한·예약 규칙은 AGENTS.md·저장소 README를 따른다.
6. AI 턴 중 Git 변경을 직접 실행하지 않는다. `ai-logs/`·원본 세션·로깅 설정은 프로젝트 빌드나 포매터가 수정하지 않는다. 자동 수집과 스냅샷은 기존 LOG-001 도구가 담당한다.

## 6. 후속 기능에서 확정할 사항

- 업무 엔티티·필드·식별자·인덱스·고유성, 조회 조건·정렬·페이지 응답 계약.
- 멤버 → 그룹 HTTP 조회 흐름, 없는 식별자·시간 초과·그룹 장애의 API 표현(503 전용 `code`).
- 그룹 업무 조회 기능의 계약과 장애 처리 검증. 구조 변경 기록은 [구조 변경 계획](structure-change-plan.md)을 따른다.
- 기능 계약 테스트, 아키텍처 계층 검사, CI, 배포·롤백 절차.
- 배포용 통합 환경이나 정적 OpenAPI 내보내기는 실제 필요가 생겼을 때 도입한다. 로컬 일괄 실행 진입점은 서비스별 실행을 대체하지 않는다.

내부 키·페이지네이션·오류 응답·재시도 정책은 해당 공통 문서에 유지한다. 구성 계획에 같은 표와 과거 확장안 전체를 중복 보관하지 않는다.
