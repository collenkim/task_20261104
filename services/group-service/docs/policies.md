# 그룹 서비스 정책

| 항목 | 내용 |
| --- | --- |
| 목적 | 책임·경계·후속 업무 규칙 |
| 범위 | group-service |
| 관련 코드 | src/main/kotlin/task/group/, infra/compose.yaml |

- 소유 데이터: 그룹 정보와 멤버 식별자 기반 소속 관계.
- 자기 MariaDB와 계정만 사용하며 타 서비스 코드·DTO·엔티티·DB를 직접 참조하지 않는다.
- 내부 키가 있는 요청만 처리한다. health·오류 처리는 예외다. 그룹 앱의 호스트 포트는 없다.
- 다른 서비스를 호출하지 않는다. 응답에는 멤버 상세 정보 대신 식별자를 포함하고, 필요한 조합은 멤버가 수행한다.
- 그룹 명세는 멤버를 경유해 공개하며 그룹 Swagger UI는 제공하지 않는다.
- [보안](../../../docs/common/security.md), [통신](../../../docs/common/service-communication.md), [API](../../../docs/common/api-conventions.md), [CQRS](../../../docs/common/cqrs.md)를 따른다.
- 업무 소속 조회의 목표 계약은 [API 정의](api.md)에 기록한다. 다음은 제공자 미구현 정책이다.

## POL-GROUP-001 다중 소속 계약 (제공자 미구현)

- 소속 관계는 멤버 1명당 0~N개다. 그룹마다 권한을 별도로 가진다. 같은 `(member_id, group_id)`의 중복은 허용하지 않는다.
- 멤버 ID는 외부 식별자다. 멤버 DB와 외래 키·JPA 연관 관계를 만들거나 멤버를 역호출하지 않는다.
- 권한 enum은 그룹 구현에서 `code`, `status`, `codeName`, `description`을 소유한다. status는 권한 코드의 업무 상태이며 HTTP 상태와 혼용하지 않는다. 실제 권한 집합·상태값은 제공자 구현 때 정책을 확정한다. 현재 Editor/Viewer는 개발 예시다.
- API는 권한의 안정적인 코드와 코드명을 반환한다. 멤버는 enum 정의를 복제하지 않는다. 그룹 과금 정보는 소속 조회의 필수 조건이 아니다.
- 멤버 local fixture는 멤버 선행 개발용이다. 그룹의 실제 데이터나 구현 완료 증거가 아니며, 제공자 구현 후 별도 계약·실행 검증이 필요하다.
