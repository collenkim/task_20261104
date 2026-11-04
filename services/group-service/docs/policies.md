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
- 업무 식별자·조회 조건·정렬·없음 처리·공개 필드는 기능 구현 전 이 문서와 엔티티·API 정의서에 확정한다.
