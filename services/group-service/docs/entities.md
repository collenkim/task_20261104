# 그룹 서비스 엔티티

| 항목 | 내용 |
| --- | --- |
| 목적 | 현재 스키마와 엔티티 구현 여부를 명확히 한다 |
| 범위 | MariaDB DB·스키마 `group_service` |
| 관련 코드 | `src/main/resources/db/migration/mariadb/`, `src/main/resources/application.yaml` |

| 대상 | 현재 상태 |
| --- | --- |
| 업무 JPA 엔티티·Repository | 미구현. 프로젝트 구성 단계에서 업무 필드·키·관계를 임의로 확정하지 않는다 |
| 업무 테이블 | 없음 |
| `flyway_schema_history` | Flyway가 생성·관리하는 이력 테이블. 업무 엔티티로 매핑하지 않음 |
| `jpa_probe` / `JpaProbe` | 테스트 전용. 운영 스키마·jar에 포함하지 않음 |

Spring Data JPA 스타터, Hibernate, Kotlin JPA 기본 생성자·프록시 지원은 구성돼 있다. `ddl-auto=validate`로 검증하고 Flyway만 DDL을 적용한다.

후속 설계에서는 엔티티별 목적·열/타입·null 허용·기본값·키·인덱스·고유성·변경 이력·관련 정책을 기록한다. 타 서비스 데이터는 ID로만 참조한다. 자료형·UTC·문자셋은 [공통 데이터 규칙](../../../docs/common/data-conventions.md)을 따른다.
