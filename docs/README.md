# 프로젝트 공통 문서 시작점

| 항목 | 내용 |
| --- | --- |
| 목적 | AI와 개발자가 현재 스펙·정책·구현 범위·실행 방법을 빠르게 파악한다 |
| 범위 | 저장소 공통 내용과 서비스 소유 문서의 연결 |
| 관련 문서 | [저장소 README](../README.md), [공통 문서 목록](common/README.md) |

## 현재 프로젝트 기준

- JDK 21, Kotlin 2.3.21, Spring Boot 4.1.2, Gradle 9.3.0, Spring Data JPA, MariaDB 11.8.9를 사용한다.
- 멤버·그룹 서비스는 각각 앱·DB/스키마·계정·볼륨·네트워크를 소유한다. 서비스 간 직접 코드·DB 참조와 메시지 브로커는 사용하지 않는다.
- **공개 범위**([ADR-005](common/decisions/ADR-005-member-edge-group-internal.md))
  - 사용자는 멤버 서비스만 호출한다(공개 진입점, 두 서비스 명세를 제공하는 Swagger).
  - 그룹 서비스는 내부 키로 호출하는 내부 전용 서비스다. 호출은 멤버 → 그룹 단방향이다.
  - 구조 변경 항목과 완료 기준은 [구조 변경 계획](common/structure-change-plan.md)에 있다.
- 루트 `docker compose up -d --build --wait`로 두 앱과 DB를 실행한다. 서비스 소유 Compose도 단독 실행할 수 있다.
- 공통 실행 규칙과 기술 정책은 `docs/common/`, 업무 정책·엔티티·API 계약은 `services/<서비스>/docs/`에서 관리한다.
- 현재는 실행 기반과 멤버 테이블·조회 저장소·소속 공급자 경계를 구현했다. 공개 API·실제 그룹 업무 연동은 아직 없다. 완료 여부를 Swagger 노출이나 설정 존재만으로 판단하지 않는다.
- MEMBER-001의 [통합 계획](common/member-query-plan.md)·[구현 요청서](common/requests/MEMBER-001.md)가 기준이다. [1단계 저장소](common/member-query-stage-1-verification.md)와 [2단계 공급자](common/member-query-stage-2-verification.md)의 검증 범위를 구분한다. 공개 API·local 시드·백오프/지터는 후속 단계다.

## 공통 규칙 읽는 순서

| 문서 | 확인할 내용 |
| --- | --- |
| [구성 계획](common/project-setup-plan.md)·[기술 스택](common/technology-stack.md) | 요구사항·버전·현재 구현 범위 |
| [멤버 조회 계획](common/member-query-plan.md)·[구현 요청](common/requests/MEMBER-001.md) | MEMBER-001 범위·소유 계약·단계·미적용 상태 |
| [구조 변경 계획](common/structure-change-plan.md) | 멤버 공개·그룹 내부 전용 전환의 보완 항목(G-01~G-14)과 완료 기준 |
| [아키텍처](common/architecture.md)·[CQRS](common/cqrs.md) | 서비스 독립성·계층·조회/변경 경계 |
| [코드 컨벤션](common/code-conventions.md)·[포매터](common/formatting.md) | Kotlin·Spring·JPA·enum 코드 필드, Google 스타일 |
| [데이터 규칙](common/data-conventions.md) | DB 소유권·Flyway·UTC·문자셋 |
| [API 규칙](common/api-conventions.md)·[서비스 통신](common/service-communication.md) | 오류 코드·코드명/설명·HTTP 연동·타임아웃 |
| [보안](common/security.md) | 내부 호출 키·검사 제외 경로·비밀 파일·빌드 입력 제한 |
| [로컬 실행](common/local-development.md) | 일괄/단독 실행·DB 전용 실행·모드별 볼륨 |
| [테스트](common/testing.md)·[검증 결과](common/setup-verification.md) | 확인한 동작과 미검증 범위 |
| [커밋·브랜치](common/commit-conventions.md) | master 기준 feature 생성·develop 검증·사용자 master 반영 |
| [결정 기록](common/decisions/README.md) | 현재 적용 중인 기술·구조 결정 |

## 서비스 소유 문서

| 서비스 | 개요·실행 | 정책 | 엔티티 | API |
| --- | --- | --- | --- | --- |
| 멤버 | [README](../services/member-service/docs/README.md) | [정책](../services/member-service/docs/policies.md) | [엔티티](../services/member-service/docs/entities.md) | [API](../services/member-service/docs/api.md) |
| 그룹 | [README](../services/group-service/docs/README.md) | [정책](../services/group-service/docs/policies.md) | [엔티티](../services/group-service/docs/entities.md) | [API](../services/group-service/docs/api.md) |

## 문서 관리

현재 실행 방법·스펙·검증 결과를 기준으로 유지한다. 같은 정책 표를 여러 문서에 복제하지 않고 소유 문서로 연결한다. 미구현·미검증 항목은 완료로 표시하지 않는다.

AI 기록 관리 절차는 저장소 README·AGENTS.md와 [AI 로깅 계획](ai-logging-plan.md)을 따른다. 수집된 원본과 스냅샷은 프로젝트 문서 정리 대상으로 편집하지 않는다.
