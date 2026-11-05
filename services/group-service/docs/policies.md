# 그룹 서비스 정책

그룹 서비스는 자기 DB의 그룹과 소속 관계를 소유한다. 멤버 API·DB를 역조회하지 않는다. 그룹 명세는 멤버를 경유한다.
공통 기준은 [보안](../../../docs/common/security.md), [통신](../../../docs/common/service-communication.md), [API](api.md), [데이터](../../../docs/common/data-conventions.md)다.

## POL-GROUP-001 단일 소속과 정합성

- 현재 멤버당 소속은 0~1개다. group_membership.member_id의 NOT NULL + UNIQUE로 동시 입력까지 차단한다.
- (group_id, member_id)도 UNIQUE다. 멤버 DB에 FK를 만들지 않으며 외부 식별자만 저장한다.
- 상세 0건은 전용 404, 1건은 단일 객체, 2건 이상은 GroupMembershipAmbiguousException과 500 GROUP_MEMBERSHIP_AMBIGUOUS다.
- summary에서도 같은 멤버의 복수 소속을 정상으로 숨기지 않는다.
- 예외는 ERROR로 기록한다. 정상적인 목록·상세·미소속 결과와 오류를 구분한다.
- 다중 소속은 향후 별도 마이그레이션과 API·소비자 변경으로 도입한다.

## POL-GROUP-002 조회와 집계

- summary는 요청 멤버의 소속과 그룹 전체 인원을 구분한다.
- 읽기 트랜잭션의 REPEATABLE READ 스냅샷에서 projection과 집계를 최대 2회로 조회한다.
- Handler는 트랜잭션 밖에서 묶기·정렬을 수행한다. 엔티티를 포트 밖으로 노출하지 않는다.
- 멤버 존재를 확인하지 않으므로 알 수 없는 ID와 미소속 ID는 구분할 수 없다.

## 코드와 시각

- 권한: Viewer, Editor, Admin. 과금: Free, Advanced, Premium.
- enum은 code/status/codeName/description을 가진다. status는 코드 사용 상태 ACTIVE/DEPRECATED이며 권한 상하 관계나 소속 활성 상태가 아니다.
- DB와 API에는 code를 사용한다. codeName·description은 내부 관리용이다. 알려지지 않은 저장 code는 500이다.
- 그룹 가입 시각은 joined_at이며 멤버 가입 시각과 독립적이다. 응답 표시는 한국 시간의 분 단위이고 저장은 UTC TIMESTAMP(6)다.
