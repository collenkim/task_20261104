# 데이터 규칙

| 항목 | 내용 |
| --- | --- |
| 분류 | 공통 기준 |
| 상태 | 현재 · 적용 예정 사항은 해당 절에서 구분 |
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
- 기본 검색 위치: `classpath:db/migration/mariadb`. 두 서비스의 local 프로필은 `classpath:db/seed/local`도 추가한다. 이름은 `V<번호>__<설명>.sql`이고 SQL 개행은 LF다.
- DB는 Compose가 먼저 생성한다. Flyway `create-schemas=false`로 실행하며 이력은 각 DB의 `flyway_schema_history`에 저장한다.
- 적용한 MariaDB 마이그레이션 내용은 수정하지 않고 다음 버전을 추가한다.
- MariaDB V1은 최초 이력만 만든다. 멤버 V2는 member 테이블, V3는 시각 타입 전환이다. 그룹 V2는 user_group·group_membership 테이블을 생성한다. 운영 마이그레이션 경로에 시드 데이터나 테스트 테이블을 넣지 않는다.
- 테스트의 `db/test-migration/V900__jpa_probe.sql`은 테스트 리소스에서만 추가된다. 운영 jar에는 포함하지 않는다.

## POL-DATA-003 다른 서비스 데이터

- 다른 서비스 DB를 조회·조인하거나 외래 키로 참조하지 않는다.
- 다른 서비스 자원은 식별자만 저장한다. 표시용 정보는 해당 서비스의 API로 조회한다.
- 다른 서비스 식별자의 존재 여부와 원격 장애 처리 정책은 업무 기능 계약에서 정한다. 분산 트랜잭션을 가정하지 않는다.

## 테이블·열·JPA 매핑

- 테이블·열은 소문자 snake_case, 테이블은 단수형이다. 예약어 `group` 대신 업무 문맥이 드러나는 이름을 정한다.
- 기본 키는 `id`, 외부 서비스 식별자는 `<대상>_id`다. 실제 키 자료형은 서비스 엔티티 문서에서 확정한다.
- 시각은 UTC `Instant` ↔ `TIMESTAMP(6)` 기준이다. DB 기본 시간대와 `hibernate.jdbc.time_zone`을 UTC로 맞춘다. 저장 정밀도는 마이크로초다.
- `utf8mb4_unicode_ci`는 기본적으로 대소문자를 구분하지 않는다. 이메일·식별자의 비교 및 고유성은 업무 정책을 먼저 정하고 열 정렬·인덱스에 반영한다.
- 엔티티는 일반 클래스이며 Kotlin `plugin.jpa`와 `allOpen`을 사용한다. 조회 트랜잭션은 `readOnly=true`, API에는 엔티티 대신 조회 DTO를 반환한다.
- 실제 업무 엔티티 현황은 [멤버 엔티티](../../services/member-service/docs/entities.md)·[그룹 엔티티](../../services/group-service/docs/entities.md)를 따른다.

## POL-DATA-004 local 시드

멤버와 그룹 모두 local 전용 repeatable 시드를 사용한다. 최초/재기동·체크섬 변경·기본 프로필 격리는 실제 MariaDB로 검사한다. 과거 멤버 증거는 [3단계 기록](../verification/MEMBER-001/stage-3.md), 그룹 증거는 [GROUP-001 검증](../verification/GROUP-001/group-query-verification.md)에 있다. 운영 Flyway 기본 경로에는 시드를 추가하지 않는다.

- local의 `spring.flyway.locations`에는 `classpath:db/migration/mariadb`와 `classpath:db/seed/local`을 모두 명시한다. 프로필 목록 덮어쓰기 때문에 기존 마이그레이션 경로를 빠뜨리지 않는다.
- 시드는 `R__seed_member.sql`처럼 repeatable로 둔다. 최초 migrate와 체크섬 변경 시, 대기 중인 버전 마이그레이션 뒤 실행된다. 같은 파일의 단순 재기동에서는 다시 실행되지 않는다. [Flyway 동작](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/repeatable-migrations)
- 매 기동 강제 실행용 timestamp placeholder, 무작위 ID, CURRENT_TIMESTAMP 기반 감사 정보 덮어쓰기를 사용하지 않는다. 값은 고정된 합성 데이터이며 이메일은 example.invalid를 사용한다.
- 시드 ID가 없으면 삽입한다. 기존 `created_by`가 이진 비교로 정확히 `seed`인 소유 행만 업무 값을 갱신하고 생성 시각·생성자는 유지한다. 멤버의 갱신 대상은 이름·이메일·주소·가입 시각이고, 그룹의 대상은 이름·과금·소속 권한·그룹 가입 시각이다. 비시드 행은 덮어쓰지 않는다. 그룹은 소속 ID·멤버 ID·그룹 관계 충돌을 쓰기 전에 거부한다. 세부 보호 조건은 각 서비스 엔티티 문서와 시드가 소유하며 충돌을 정상 통과로 기록하지 않는다.
- 시드 소유 ID 외 행을 삭제하거나 수정하지 않는다. 사라진 시드 항목의 자동 삭제·Flyway clean·전체 볼륨 초기화를 재실행 방법으로 삼지 않는다. 데이터 초기화는 사용자가 명시적으로 선택한 별도 절차다.
- `src/main/resources/db/seed/local`과 fixture는 jar에 포함되지만 기본 프로필에서 로드하지 않는다. 이 범위는 **운영 실행에서 격리**이며 jar에 물리적으로 없다는 주장은 하지 않는다.
- 일반 통합 테스트는 local 시드를 로드하지 않고 독립 fixture를 넣는다. 별도 local 시드 테스트는 실제 MariaDB에서 최초 적용·무변경 재기동·내용 변경 재적용·감사 정보 보존·비시드 데이터 보호를 검증한다.
- 기본 프로필에서 시드 행이 생성되지 않고 fixture 공급자 선택이 거부되는지 검증한다. 검증한 환경·프로젝트·볼륨을 기록한다.

## GROUP-001 기존 시각 타입 전환

멤버 V2는 변경하지 않고 V3에서 registered_at/created_at을 TIMESTAMP(6)으로 전환한다. UTC 세션에서 적용하고 원본 행 수·키·초·마이크로초를 비교한다. 적용 전 백업과 타입 지원 범위/zero date/NULL 검사를 수행하며 잘못된 값은 자동 보정하지 않는다. MariaDB DDL의 롤백을 가정하지 않는다. 실패 시 자동 재실행·볼륨 초기화 대신 원인과 스키마 상태를 확인하고 백업 복구 또는 전진 수정 절차를 사용한다. 그룹은 V2부터 TIMESTAMP(6)을 사용한다.
