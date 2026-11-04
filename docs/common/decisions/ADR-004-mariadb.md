# ADR-004 서비스별 MariaDB 사용

| 항목 | 내용 |
| --- | --- |
| 상태 | 확정 (2026-11-04), 현재 구성 기준 |
| 범위 | 두 서비스의 DB·드라이버·Flyway·테스트 |
| 관련 코드 | `services/*/infra/compose.yaml`, `gradle/libs.versions.toml`, `application.yaml` |

## 결정과 근거

조회 서비스의 관계형 데이터와 트랜잭션을 처리할 DB로 MariaDB 11.8.9를 사용한다. 요구 기능을 충족하며 Spring Data JPA·Flyway·Testcontainers와 함께 검증한다.

- 서비스마다 별도 DB 서버 컨테이너·DB/스키마·계정·볼륨·네트워크를 소유한다. MariaDB의 DB와 SCHEMA는 같은 논리 공간이다.
- 개발 Compose와 Testcontainers에 같은 이미지 태그를 고정한다. `latest`는 사용하지 않는다.
- MariaDB Connector/J와 Flyway `flyway-mysql`의 Java 라이브러리 버전은 Spring Boot BOM으로 관리한다.
- 문자셋은 `utf8mb4`, 기본 정렬은 `utf8mb4_unicode_ci`, 서버와 JDBC 시각 기준은 UTC다. 업무별 고유성·대소문자 정책은 각 엔티티 문서에서 정한다.
- 비밀번호는 Compose secrets 파일로 전달한다. 앱 계정은 자기 DB 범위의 권한만 가지며 root는 컨테이너 내부 관리용이다.
- Flyway는 `db/migration/mariadb/`만 읽고 JPA는 `ddl-auto=validate`로 검증한다.
- 일괄/단독 실행의 볼륨 구분과 데이터 보존 절차는 [로컬 실행](../local-development.md)을 따른다.

## 확인 근거

- [공식 이미지 태그](https://github.com/docker-library/official-images/blob/master/library/mariadb)
- [Flyway MariaDB 모듈](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mariadb)
- [프로젝트 검증 결과](../setup-verification.md)
