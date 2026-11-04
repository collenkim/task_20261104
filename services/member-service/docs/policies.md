# 멤버 서비스 정책

| 항목 | 내용 |
| --- | --- |
| 목적 | 책임·경계·후속 업무 규칙 |
| 범위 | member-service |
| 관련 코드 | src/main/kotlin/task/member/, infra/compose.yaml |

- 소유 데이터: 멤버 정보.
- 자기 MariaDB와 계정만 사용하며 타 서비스 코드·DTO·엔티티·DB를 직접 참조하지 않는다.
- 사용자 인증·인가는 이번 범위에서 제외한다. 멤버가 사용자 진입점이다.
- 그룹 클라이언트가 서버 내부 키를 첨부한다. 사용자 헤더를 그룹으로 그대로 전달하지 않는다.
- 그룹 장애가 멤버 기동·readiness·자기 명세를 막지 않는다. 현재 그룹 명세 요청은 장애 시 503으로 실패한다.
- [보안](../../../docs/common/security.md), [통신](../../../docs/common/service-communication.md), [API](../../../docs/common/api-conventions.md), [CQRS](../../../docs/common/cqrs.md)를 따른다.
- 업무 식별자·조회 조건·공개 필드는 [API 정의](api.md)와 [엔티티](entities.md)에 기록한다. 아래 MEMBER-001 정책은 미적용 목표다.

## POL-MEMBER-001 조회와 다중 소속 (미적용 M-02·M-05)

- 멤버는 0~N개 그룹에 소속될 수 있으며 권한은 소속별로 다르다. 공개 계약은 항상 `groups[]`다.
- 멤버 DB에서 정렬·페이지를 먼저 결정하고, 해당 ID만 그룹 포트로 일괄 조회한다. 전체 건수는 멤버 수, 멤버 순서는 DB 결과를 유지한다.
- 각 멤버의 그룹 배열은 ID 오름차순이다. 동일 멤버 안에서 중복 group ID는 계약 오류다. 같은 group ID가 서로 다른 멤버에 있는 것은 정상이다.
- 요청 ID마다 정확히 한 소속 목록이 필요하다. 누락·추가 member ID·중복 member ID, 비어 있는 필수 코드/코드명/그룹명은 오류다. 소속이 여러 개인 것은 오류가 아니다.
- 알 수 없는 비어 있지 않은 권한 코드는 허용한다. 권한 enum을 멤버에 복제하지 않는다. 상세의 선택 정보 `joinedAt`·`pricing` 누락은 null로 매핑한다. 새로운 알 수 없는 응답 필드는 수용한다.
- 조회 트랜잭션은 저장소 경계에서 종료한다. fixture/HTTP 선택과 무관하게 그룹 포트 호출 시 활성 트랜잭션이 없어야 한다.

## POL-MEMBER-002 임시 그룹 공급자 (미적용 M-04)

- `GroupMembershipReader`의 `FixtureGroupMembershipReader`와 `HttpGroupMembershipReader` 구현을 둔다. application·Controller에는 공급자 종류 분기나 특정 멤버 ID 하드코딩을 넣지 않는다.
- 설정 키는 `task.group-membership.source`(`fixture`/`http`)다. 기본 프로필은 http, local 프로필은 선행 작업 동안 fixture를 기본값으로 사용한다. 환경변수 `TASK_GROUP_MEMBERSHIP_SOURCE`로 명시적으로 선택할 수 있다.
- fixture는 활성 프로필이 local을 포함할 때만 허용한다. 기본 프로필에서 fixture 지정, 알 수 없는 값, 공급자 중복/누락은 기동 실패다. 같은 포트에는 정확히 한 구현만 등록한다.
- fixture 파일은 [시드 계획](entities.md#local-시드-계획-m-06)의 다섯 ID를 모두 포함한다. 파일 존재·필수 값·ID/소속 중복은 기동 시 검사한다. 미소속 ID도 빈 배열 항목을 명시한다.
- fixture에 없는 ID는 자동으로 미소속을 추정하지 않고 설정 오류로 처리한다. 개발 데이터를 추가하면 대응 fixture 항목도 추가한다.
- fixture와 HTTP는 동일한 내부 소속 모델과 순수 계약 검증 규칙을 사용한다. fixture 모드는 업무 그룹 HTTP 호출을 하지 않는다.
- HTTP 실패 시 fixture로 대체하지 않는다. 실제 그룹 API가 준비되면 설정만 http로 전환하고 실제 제공자 검증을 추가한다.
- 기존 `/internal-docs/group`은 실제 그룹 명세 HTTP 조회를 유지한다. fixture로 가짜 그룹 업무 명세를 생성하거나 그룹 내부 API가 구현된 것처럼 표시하지 않는다.
- 현재 source 설정·fixture 코드는 없다. 목표 명령과 확인 항목은 [로컬 실행](../../../docs/common/local-development.md)에 있다.
