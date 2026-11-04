# 데이터 규칙

| 항목 | 내용 |
| --- | --- |
| 목적 | 서비스별 DB 분리, 스키마 변경 방법, 다른 서비스 데이터를 다루는 규칙을 정한다 |
| 범위 | 두 서비스의 PostgreSQL DB·스키마·계정, Flyway 마이그레이션, JPA 엔티티 |
| 관련 코드 | `services/*/infra/postgres/initdb/01-app-role.sh`, `services/*/src/main/resources/db/migration/`, `application.yaml`(`spring.datasource`, `spring.flyway`, `spring.jpa`) |

## POL-DATA-001 서비스별 DB

| 서비스 | DB 컨테이너 | DB | 스키마 | 앱 계정 | 호스트 포트 | 볼륨 |
| --- | --- | --- | --- | --- | --- | --- |
| member-service | `member-db` (PostgreSQL 18.6) | `member_service` | `member_service` | `member_app` | 127.0.0.1:15432 | `task-member_member-db-data` |
| group-service | `group-db` (PostgreSQL 18.6) | `group_service` | `group_service` | `group_app` | 127.0.0.1:25432 | `task-group_group-db-data` |

- 서비스마다 PostgreSQL 컨테이너가 따로 있다. 같은 서버를 나눠 쓰지 않는다.
- **관리자 계정**(`postgres`)은 DB 컨테이너만 안다. 앱 설정에는 없다.
- **앱 계정**은 DB 초기화 스크립트(`01-app-role.sh`)가 볼륨을 처음 만들 때 생성한다.
  - 슈퍼유저가 아니고, 자기 스키마를 소유한다.
  - `PUBLIC`의 DB 권한을 걷어 내고 앱 계정에만 `CONNECT`를 준다. 기본 `search_path`는 자기 스키마다.
- 호스트 포트는 `127.0.0.1`에만 연다.
- DB 이름·스키마 이름은 `<서비스 이름의 밑줄 표기>`(`member_service`), 계정은 `<영역>_app`(`member_app`)으로 짓는다.

## POL-DATA-002 Flyway

- 스키마 변경은 Flyway 마이그레이션으로만 한다. JPA `ddl-auto`는 `validate`다.
- 위치: `src/main/resources/db/migration/`. 이름: `V<번호>__<설명>.sql`(예: `V1__init_schema.sql`).
- 적용한 마이그레이션은 고치지 않는다. 바꿀 내용은 새 번호로 추가한다.
- Flyway 이력 테이블은 서비스 스키마(`<스키마>.flyway_schema_history`)에 있다. 서비스마다 이력이 따로 쌓인다.
- 검증·시연용 데이터는 Flyway 기본 경로에 넣지 않는다. 프로필 전용 경로를 기능 개발 때 정한다.
- SQL 파일은 LF 개행이다(`.gitattributes`).

## POL-DATA-003 다른 서비스 데이터

- 다른 서비스의 테이블을 조회·조인·외래 키로 참조하지 않는다.
- 다른 서비스 자원은 **식별자만** 저장한다. 예: 그룹 서비스의 소속 관계는 `member_id` 값만 가진다.
  - 이름 같은 표시용 값을 복제해 두지 않는다. 필요하면 그 서비스 API로 조회한다.
- 식별자가 실제로 있는지는 저장 시점에 DB로 보장할 수 없다. 처리 방식은 기능 개발 때 정한다.

## 테이블·열 이름

- 테이블·열은 소문자 snake_case, 테이블은 단수형(`member`, `group_membership`).
- 기본 키는 `id`, 다른 서비스 식별자는 `<대상>_id`.
- 시각 열은 `timestamptz`(`created_at`, `updated_at`).
- 엔티티·테이블 설계는 서비스 문서(`services/<서비스>/docs/entities.md`)에 기능 개발 때 쓴다.
