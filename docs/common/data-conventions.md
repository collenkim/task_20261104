# 데이터 규칙

| 항목 | 내용 |
| --- | --- |
| 목적 | 서비스별 DB 소유권, JPA와 스키마 변경 규칙을 정한다 |
| 범위 | MariaDB·Flyway·Spring Data JPA |
| 관련 코드 | `services/*/infra/compose.yaml`, `services/*/src/main/resources/application.yaml`, `db/migration/mariadb/` |

## POL-DATA-001 서비스별 DB

| 서비스 | DB 컨테이너 | DB = 스키마 | 앱 계정 | 호스트 포트 | 볼륨 |
| --- | --- | --- | --- | --- | --- |
| member-service | `member-db` (MariaDB 11.8.9) | `member_service` | `member_app` | 127.0.0.1:13306 | `task-member_member-mariadb-data` |
| group-service | `group-db` (MariaDB 11.8.9) | `group_service` | `group_app` | 127.0.0.1:23306 | `task-group_group-mariadb-data` |

- MariaDB의 DATABASE와 SCHEMA는 동의어다. 서비스마다 독립 서버와 자기 논리 DB를 가진다.
- 각 서비스는 별도 MariaDB 컨테이너를 소유한다. 관리자 `root`는 DB 컨테이너 내부에서만 사용하도록 `MARIADB_ROOT_HOST=localhost`로 제한한다.
- 공식 이미지의 `MARIADB_DATABASE`·`MARIADB_USER`·`MARIADB_PASSWORD_FILE`로 앱 계정을 초기화한다. 앱 계정은 자기 DB의 권한만 갖고 전역 관리자 권한을 받지 않는다. 로컬 구성에서는 Flyway도 이 계정을 사용한다.
- DB 포트는 호스트 `127.0.0.1`에만 연다. 각 Compose의 DB 네트워크에는 자기 앱과 DB만 둔다.
- 위 볼륨은 단독 실행 기준이다. 루트 일괄 실행은 `task-local_member-mariadb-data`·`task-local_group-mariadb-data`를 사용한다. 두 모드는 데이터가 자동 공유되지 않는다([로컬 실행](local-development.md)).

## POL-DATA-002 Flyway

- 스키마 변경은 Flyway만 수행한다. JPA는 `ddl-auto=validate`, `open-in-view=false`다.
- 현재 검색 위치: `classpath:db/migration/mariadb`. 이름은 `V<번호>__<설명>.sql`이고 SQL 개행은 LF다.
- DB는 Compose가 먼저 생성한다. Flyway `create-schemas=false`로 실행하며 이력은 각 DB의 `flyway_schema_history`에 저장한다.
- 적용한 MariaDB 마이그레이션 내용은 수정하지 않고 다음 버전을 추가한다.
- MariaDB V1은 최초 이력만 만든다. 업무 테이블은 후속 기능에서 V2부터 추가한다. 운영 경로에 시드 데이터나 테스트 테이블을 넣지 않는다.
- 테스트의 `db/test-migration/V900__jpa_probe.sql`은 테스트 리소스에서만 추가된다. 운영 jar에는 포함하지 않는다.

## POL-DATA-003 다른 서비스 데이터

- 다른 서비스 DB를 조회·조인하거나 외래 키로 참조하지 않는다.
- 다른 서비스 자원은 식별자만 저장한다. 표시용 정보는 해당 서비스의 API로 조회한다.
- 다른 서비스 식별자의 존재 여부와 원격 장애 처리 정책은 업무 기능 계약에서 정한다. 분산 트랜잭션을 가정하지 않는다.

## 테이블·열·JPA 매핑

- 테이블·열은 소문자 snake_case, 테이블은 단수형이다. 예약어 `group` 대신 업무 문맥이 드러나는 이름을 정한다.
- 기본 키는 `id`, 외부 서비스 식별자는 `<대상>_id`다. 실제 키 자료형은 서비스 엔티티 문서에서 확정한다.
- 시각은 UTC `Instant` ↔ `DATETIME(6)` 기준이다. DB 기본 시간대와 `hibernate.jdbc.time_zone`을 UTC로 맞춘다. 저장 정밀도는 마이크로초다.
- `utf8mb4_unicode_ci`는 기본적으로 대소문자를 구분하지 않는다. 이메일·식별자의 비교 및 고유성은 업무 정책을 먼저 정하고 열 정렬·인덱스에 반영한다.
- 엔티티는 일반 클래스이며 Kotlin `plugin.jpa`와 `allOpen`을 사용한다. 조회 트랜잭션은 `readOnly=true`, API에는 엔티티 대신 조회 DTO를 반환한다.
- 실제 업무 엔티티 현황은 [멤버 엔티티](../../services/member-service/docs/entities.md)·[그룹 엔티티](../../services/group-service/docs/entities.md)를 따른다.
