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
- 업무 식별자·조회 조건·정렬·없음 처리·공개 필드는 기능 구현 전 이 문서와 엔티티·API 정의서에 확정한다.
