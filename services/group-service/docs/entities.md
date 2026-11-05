# 그룹 엔티티와 데이터

그룹 서비스 소유 DB는 group_service다. 그룹 V2에서 아래 테이블을 생성한다.
[공통 데이터 규칙](../../../docs/common/data-conventions.md)과 [소속 정책](policies.md)을 따른다.

## user_group

| 열 | 타입 | 제약/의미 |
| --- | --- | --- |
| id | CHAR(10), ascii_bin | PK, NOT NULL |
| name | VARCHAR(100) | NOT NULL, 그룹 이름 |
| pricing | VARCHAR(20), ascii_bin | NOT NULL, 과금 code |
| created_at | TIMESTAMP(6) | NOT NULL, UTC 생성 시각 |
| created_by | VARCHAR(50) | NOT NULL, 생성 주체 |

## group_membership

| 열 | 타입 | 제약/의미 |
| --- | --- | --- |
| id | BIGINT AUTO_INCREMENT | PK |
| group_id | CHAR(10), ascii_bin | NOT NULL, user_group FK |
| member_id | CHAR(10), ascii_bin | NOT NULL, 외부 멤버 ID |
| permission | VARCHAR(20), ascii_bin | NOT NULL, 권한 code |
| joined_at | TIMESTAMP(6) | NOT NULL, UTC 그룹 가입 시각 |
| created_at | TIMESTAMP(6) | NOT NULL, UTC 생성 시각 |
| created_by | VARCHAR(50) | NOT NULL, 생성 주체 |

UNIQUE(member_id)는 한 멤버의 다른 그룹 가입도 차단한다. UNIQUE(group_id, member_id)는 같은 그룹의 중복 소속을 제한한다. 두 인덱스로 멤버별 조회와 그룹 인원 집계를 지원한다. 다른 서비스 DB에 FK나 JPA 관계를 만들지 않는다.

## local 시드

R__seed_group.sql은 local 프로필의 migration + seed/local 검색 위치에서만 실행한다. 최초와 체크섬 변경 시 재적용하며 단순 재기동에서는 다시 실행하지 않는다.

- arutjf9348/Test1/Premium: tu3fgxd45k(Editor), dj8fy4bewk(Viewer).
- demo000002/프로젝트팀/Advanced: q1w2e3r4t5(Admin).
- demo000003/무료팀/Free: 0명.
- k2m9x0p1q3, j0n8k2m4p6은 미소속이다.

고정 합성 값만 사용한다. created_by가 정확히 seed인 행의 업무 값만 변경하며 생성 시각·생성자는 보존한다. 비시드 행은 덮어쓰지 않고 충돌 검증 대상으로 보고한다. 시드에서 사라진 항목을 자동 삭제하지 않는다. 기본 프로필에는 적용하지 않는다.
