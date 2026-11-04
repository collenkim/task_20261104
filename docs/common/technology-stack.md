# 기술 스택

| 항목 | 내용 |
| --- | --- |
| 목적 | 사용하는 기술과 버전, 버전을 고정한 위치와 확인 근거를 남긴다 |
| 범위 | 두 서비스와 공통 빌드 |
| 관련 코드 | `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `services/*/Dockerfile`, `services/*/infra/compose.yaml` |

## 버전

| 항목 | 버전 | 고정 위치 | 비고 |
| --- | --- | --- | --- |
| JDK | 21 (Eclipse Temurin) | `libs.versions.toml`(`java`) → Gradle toolchain | [ADR-001](decisions/ADR-001-jdk-21.md) |
| 실행 이미지 | `eclipse-temurin:21.0.13_9-jre-noble` | `Dockerfile` | curl 포함(컨테이너 healthcheck) |
| Kotlin | 2.3.21 | `libs.versions.toml`(`kotlin`) | Spring Boot BOM의 `kotlin.version`과 같음 |
| Spring Boot | 4.1.2 | `libs.versions.toml`(`spring-boot`) | Java 17 이상, Gradle 8.14 이상 |
| Gradle | 9.3.0 | `gradle-wrapper.properties` | 배포 파일 SHA-256 고정(`distributionSha256Sum`) |
| PostgreSQL | 18.6 | `compose.yaml`, 테스트 `TestcontainersConfiguration` | 데이터 볼륨 경로 `/var/lib/postgresql` |
| springdoc-openapi | 3.2.0 | `libs.versions.toml`(`springdoc`) | Spring Boot 4 대응 버전대 |
| Spotless | 8.9.0 | `libs.versions.toml`(`spotless`) | |
| ktfmt | 0.64 | `libs.versions.toml`(`ktfmt`) | Google 스타일 |
| nimbus-jose-jwt | 10.10 | `libs.versions.toml` | `build-logic`(로컬 키·토큰 생성)에서만 사용 |

Spring Boot BOM이 관리하는 버전(직접 고정하지 않음, 4.1.2 기준)

| 항목 | 버전 |
| --- | --- |
| Spring Framework | 7.x |
| Hibernate ORM | 7.4.13 |
| Flyway | 12.4.0 |
| Jackson | 3.1.7 (`tools.jackson`) |
| Testcontainers | 2.0.5 |
| JUnit | 6.0.3 |

- 라이브러리는 Spring Boot BOM 버전을 우선 쓴다. BOM 밖의 플러그인·라이브러리만 `libs.versions.toml`에 고정한다.
- 버전을 올리면 이 표와 `libs.versions.toml`을 함께 고치고, 두 서비스를 모두 빌드·테스트한다.

## 확인 근거 (2026-11-04)

- Maven Central 메타데이터: Spring Boot·Kotlin Gradle 플러그인·springdoc·Spotless·ktfmt·nimbus의 최신 안정 버전과 Spring Boot BOM의 관리 버전.
- Gradle 배포 목록(`services.gradle.org`): 9.3.0 배포 파일과 SHA-256.
- Docker Hub: `postgres:18.6`, `eclipse-temurin:21.0.13_9-jre-noble` 태그.
- Spring Boot 시스템 요구사항 문서(Java·Gradle 범위), Kotlin Gradle 플러그인 호환 표(KGP 2.3.2x ↔ Gradle 9.3.0).

## Spring Boot 4에서 바뀐 점 (이 저장소에 반영)

| 항목 | 이 저장소에서 쓰는 것 |
| --- | --- |
| 스타터 이름 | `spring-boot-starter-webmvc`, `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-restclient`, `spring-boot-starter-flyway` |
| 테스트 스타터 | `spring-boot-starter-webmvc-test`(`AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure`), `spring-boot-starter-security-test` |
| Jackson 3 | 패키지 `tools.jackson`, Kotlin 모듈 `tools.jackson.module:jackson-module-kotlin`, `JsonMapper` 주입 |
| Flyway | DB별 모듈 필요: `flyway-database-postgresql` |
| Testcontainers 2 | `org.testcontainers:testcontainers-postgresql`, 클래스 `org.testcontainers.postgresql.PostgreSQLContainer` |
| Kotlin 애너테이션 대상 | 컴파일 옵션 `-Xannotation-default-target=param-property` |

## 빌드 설정

- Gradle 구성 캐시·빌드 캐시·병렬 빌드를 켠다(`gradle.properties`).
- 저장소는 Maven Central 하나다. 프로젝트별 저장소 선언은 금지한다(`FAIL_ON_PROJECT_REPOS`).
- 공통 빌드 규칙은 `build-logic`의 컨벤션 플러그인(`task.spring-service`, `task.root`)에 둔다.
