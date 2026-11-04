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
| 빌드 이미지 | `eclipse-temurin:21.0.13_9-jdk-noble` | 서비스별 `Dockerfile` 빌드 단계 | Gradle Wrapper로 소스 빌드 |
| 실행 이미지 | `eclipse-temurin:21.0.13_9-jre-noble` | `Dockerfile` | curl 포함(컨테이너 healthcheck) |
| Kotlin | 2.3.21 | `libs.versions.toml`(`kotlin`) | Spring Boot BOM의 `kotlin.version`과 같음 |
| Spring Boot | 4.1.2 | `libs.versions.toml`(`spring-boot`) | Java 17 이상, Gradle 8.14 이상 |
| Gradle | 9.3.0 | `gradle-wrapper.properties` | 배포 파일 SHA-256 고정(`distributionSha256Sum`) |
| MariaDB | 11.8.9 | `compose.yaml`, 테스트 `TestcontainersConfiguration` | 데이터 볼륨 경로 `/var/lib/mysql` |
| Spring Data JPA | 4.1.2 (BOM) | 서비스 `build.gradle.kts`의 `spring-boot-starter-data-jpa` | Hibernate + Kotlin JPA/allOpen, Flyway + `ddl-auto=validate` |
| MariaDB Connector/J | 3.5.10 (BOM) | `org.mariadb.jdbc:mariadb-java-client` | JDBC 드라이버 |
| springdoc-openapi | 3.2.0 | `libs.versions.toml`(`springdoc`) | Spring Boot 4 대응 버전대 |
| Spotless | 8.9.0 | `libs.versions.toml`(`spotless`) | |
| ktfmt | 0.64 | `libs.versions.toml`(`ktfmt`) | Google 스타일 |

Spring Boot BOM이 관리하는 버전(직접 고정하지 않음, 4.1.2 기준). 런타임 라이브러리는 이번 bootJar의 `BOOT-INF/lib/`에서도 확인했다.

| 항목 | 버전 |
| --- | --- |
| Spring Framework | 7.0.10 |
| Hibernate ORM | 7.4.13.Final |
| Flyway | 12.4.0 |
| Jackson | 3.1.7 (`tools.jackson`) |
| Testcontainers | 2.0.5 |
| JUnit | 6.0.3 |

- 라이브러리는 Spring Boot BOM 버전을 우선 쓴다. BOM 밖의 플러그인·라이브러리만 `libs.versions.toml`에 고정한다.
- 버전을 올리면 이 표와 `libs.versions.toml`을 함께 고치고, 두 서비스를 모두 빌드·테스트한다.

## 확인 근거 (2026-11-04)

- Gradle 배포 목록(`services.gradle.org`): 9.3.0 배포 파일과 SHA-256.
- Docker Hub: `mariadb:11.8.9`, `eclipse-temurin:21.0.13_9-jre-noble` 태그.
- Spring Boot 시스템 요구사항 문서(Java·Gradle 범위), Kotlin Gradle 플러그인 호환 표(KGP 2.3.2x ↔ Gradle 9.3.0).

## Spring Boot 4에서 바뀐 점 (이 저장소에 반영)

| 항목 | 이 저장소에서 쓰는 것 |
| --- | --- |
| 스타터 이름 | `spring-boot-starter-webmvc`, `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-restclient`, `spring-boot-starter-flyway` |
| 테스트 스타터 | `spring-boot-starter-webmvc-test`(`AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure`), `spring-boot-starter-security-test` |
| Jackson 3 | 패키지 `tools.jackson`, Kotlin 모듈 `tools.jackson.module:jackson-module-kotlin`, `JsonMapper` 주입 |
| Flyway | DB별 모듈 필요: `flyway-mysql` |
| Testcontainers 2 | `org.testcontainers:testcontainers-mariadb`, 클래스 `org.testcontainers.mariadb.MariaDBContainer` |
| Kotlin 애너테이션 대상 | 컴파일 옵션 `-Xannotation-default-target=param-property` |

## 빌드 설정

- Gradle 구성 캐시·빌드 캐시·병렬 빌드를 켠다(`gradle.properties`).
- 저장소는 Maven Central 하나다. 프로젝트별 저장소 선언은 금지한다(`FAIL_ON_PROJECT_REPOS`).
- 공통 빌드 규칙은 `build-logic`의 컨벤션 플러그인(`task.spring-service`, `task.root`)에 둔다.

## Docker 실행 기준 (2026-11-04 실측)

| 구성 요소 | 이 PC의 버전 | 역할 |
| --- | --- | --- |
| Docker Desktop | 4.94.0.241994 (실행 파일 버전) | Windows 컨테이너 실행 환경 |
| Docker CLI | 29.8.2 | 사용자가 호출하는 클라이언트 |
| Docker Engine | 29.8.2 | Linux 컨테이너 실행 서버 |
| Docker API | 클라이언트·서버 1.56, 서버 최소 1.40 | 클라이언트·Engine 통신 규약 |
| Docker Compose | 5.5.1 | 서비스별 compose.yaml 실행 |

- 프로젝트 검증 기준은 설치된 CLI/Engine 29.8.2와 Compose 5.5.1이다. Desktop·CLI·Compose·이미지는 서로 별도 버전 체계이며 숫자를 같게 만들지 않는다.
- Compose 파일은 최신 Compose Specification을 사용하고 최상위 `version:`을 넣지 않는다. 이를 `29.8.2` 또는 `5.5.1`로 적는 것은 호환성 설정이 아니다([Docker 공식 문서](https://docs.docker.com/reference/compose-file/version-and-name/)).
- Testcontainers는 Gradle 의존성인 Docker Java 클라이언트로 Engine에 접근한다. 설치된 Docker CLI 버전을 Java 라이브러리 버전으로 사용하지 않으며 `DOCKER_API_VERSION`을 임의로 강제하지 않는다. 실제 API 호환성은 컨테이너를 생성하는 통합 테스트로 확인한다.
- 확인 명령: `docker version`, `docker compose version`. 실패 시 접근 권한·Docker context·daemon 상태를 구분한다.
- MariaDB 선정과 운영 기준: [ADR-004](decisions/ADR-004-mariadb.md). 현재 실행 검증: [재검증 기록](setup-verification.md).

## Compose 실행과 빌드

- 루트 `compose.yaml`의 `include`는 각 서비스 정의의 상대 경로와 관리 책임을 유지한다([Docker include](https://docs.docker.com/compose/how-tos/multiple-compose-files/include/)).
- `docker compose up -d --build --wait`가 두 앱과 전용 DB를 실행한다. 앱 프로필이나 사전 bootJar가 필요 없다.
- Dockerfile은 빌드/실행 단계를 분리한다. Gradle 캐시는 BuildKit의 잠금된 캐시 마운트를 공유하며 실행 이미지에는 포함하지 않는다. 서비스 런타임끼리 캐시·코드를 공유하지 않는다.
- Dockerfile별 허용 목록과 루트 기본 허용 목록으로 빌드 입력을 제한한다([Docker 빌드 컨텍스트](https://docs.docker.com/build/concepts/context/)).

- 사용자 JWT 의존성은 제거했다. 그룹은 springdoc webmvc-api, 멤버는 webmvc-ui·RestClient를 사용한다. 내부 호출 키는 JDK SecureRandom으로 생성한다.
- 앱 통신 네트워크는 루트 compose.override.yaml에서 추가한다. 숫자 IP·포트 기준은 서비스 통신 문서를 따른다.
