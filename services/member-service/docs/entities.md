# 멤버 서비스 엔티티

| 항목 | 내용 |
| --- | --- |
| 목적 | 현재 스키마와 엔티티 구현 여부를 명확히 한다 |
| 범위 | MariaDB DB·스키마 `member_service` |
| 관련 코드 | `src/main/resources/db/migration/mariadb/`, `src/main/resources/application.yaml` |

| 대상 | 현재 상태 |
| --- | --- |
| 업무 JPA 엔티티·Repository | MemberEntity·MemberJpaRepository·JpaMemberReader 구현, `ef03e16`에서 검증 |
| 업무 테이블 | Flyway V2의 member와 local 전용 R__seed_member.sql |
| `flyway_schema_history` | Flyway가 생성·관리하는 이력 테이블. 업무 엔티티로 매핑하지 않음 |
| `jpa_probe` / `JpaProbe` | 테스트 전용. 운영 스키마·jar에 포함하지 않음 |

Spring Data JPA 스타터, Hibernate, Kotlin JPA 기본 생성자·프록시 지원은 구성돼 있다. `ddl-auto=validate`로 검증하고 Flyway만 DDL을 적용한다.

자료형·UTC·문자셋은 [공통 데이터 규칙](../../../docs/common/data-conventions.md)을 따른다. 다음 스키마는 MEMBER-001 M-01에 구현됐으며 실제 MariaDB 검증은 [1단계 기록](../../../docs/common/member-query-stage-1-verification.md)에 있다. 실행 DB에는 다음 앱 기동의 Flyway migrate 때 적용된다.

## member 테이블 (M-01 적용)

마이그레이션은 `src/main/resources/db/migration/mariadb/V2__create_member.sql`이다. 기존 V1을 수정하지 않는다. `MemberEntity`는 infrastructure가 소유하며 application·공개 API에 반환하지 않는다.

| 열 | 자료형 | null | 의미·공개 매핑 |
| --- | --- | --- | --- |
| id | CHAR(10) CHARACTER SET ascii COLLATE ascii_bin | 불가, PK | 소문자 영숫자 10자리 / id |
| name | VARCHAR(100) | 불가 | 이름 / name |
| email | VARCHAR(254) | 불가 | 이메일, 이번 범위 고유 제약 없음 / email |
| address | VARCHAR(255) | 불가 | 주소 / address |
| registered_at | DATETIME(6) | 불가 | 가입 시각 UTC / registeredDate |
| created_at | DATETIME(6) | 불가 | 생성 시각 UTC / createdAt |
| created_by | VARCHAR(50) | 불가 | 생성 주체 / createdBy |

- `ix_member_name_id(name, id)`를 적용했다. 검증 데이터셋에서 ASC는 Using index, DESC·ID ASC는 Using index; Using filesort였다. 대량 데이터 성능 보장으로 해석하지 않는다.
- 기본 문자열 비교는 `utf8mb4_unicode_ci`, ID는 `ascii_bin`이다. 형식·공백·길이는 입력/데이터 검증에서 따로 보장한다.
- 그룹명·권한·과금·소속 관계는 저장하지 않는다. 그룹 서비스 DB와 외래 키·JPA 연관 관계를 만들지 않는다.
- 등록·생성 필드는 서로 다른 의미이며 임의로 같은 값으로 추론하지 않는다. 시드에서는 명시적으로 고정값을 지정할 수 있다.

## local 시드 (M-06 적용)

상태: 시드 M-06 구현. `src/main/resources/db/seed/local/R__seed_member.sql`을 local Flyway 위치에 연결했다. 기본 프로필에는 적용하지 않는다. 소속 fixture `src/main/resources/fixtures/local/group-memberships.json`은 M-04에 추가했다. fixture만으로 멤버 DB 행이 생성되지는 않는다. 위치·재실행·기존 데이터 보호는 [POL-DATA-004](../../../docs/common/data-conventions.md#pol-data-004-local-시드)를 따른다.

| 멤버 ID | 이름 | fixture 소속 | 목적 |
| --- | --- | --- | --- |
| tu3fgxd45k | user1 | arutjf9348/Editor, demo000002/Viewer | 다중 소속·그룹별 다른 권한 |
| dj8fy4bewk | user2 | arutjf9348/Viewer | 단일 소속 |
| k2m9x0p1q3 | user3 | 빈 배열 | 명시적 미소속 |
| j0n8k2m4p6 | 동명이인 | 빈 배열 | 이름 동률·ID 보조 정렬 |
| q1w2e3r4t5 | 동명이인 | demo000002/Editor | 이름 동률·소속 조합 |

`arutjf9348`은 개발팀, `demo000002`는 프로젝트팀으로 둔다. 이메일은 `<멤버ID>@example.invalid`, 주소는 가상 문자열이다. 시드의 등록/생성 시각은 명시적인 UTC 고정값, 생성자는 `seed`다. fixture에는 다섯 ID가 모두 존재해야 하며 실제 그룹 데이터를 복제하지 않는다. 미래 그룹 시드가 준비되면 동일 테스트 ID를 사용할 수 있지만 데이터 자동 동기화를 가정하지 않는다.
