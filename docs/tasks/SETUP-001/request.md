# SETUP-001 프로젝트 구성 요청 재구성

| 항목 | 내용 |
| --- | --- |
| 분류 | 사후 재구성한 작업 요청 |
| 상태 | 이력 · REVIEW-001에서 2026-11-05 작성. 사용자 원문이 아님 |
| 근거 | [당시 계획](plan.md), [구조 변경 계획](structure-change-plan.md), [검증 기록](../../verification/SETUP-001/setup-verification.md), master 병합 1bfa20f |
| 현재 기준 | [기술 스택](../../common/technology-stack.md), [아키텍처](../../common/architecture.md), [문서 색인](../../README.md) |

## 확인 가능한 요청

JDK 21 기준 Kotlin·Spring Boot·Gradle 멀티 모듈을 구성하고 멤버 조회와 그룹 조회를 독립된 애플리케이션으로 실행한다. 메시지 브로커 없이 서비스 API로 통신하며 서비스 사이 코드·DB 직접 참조는 금지한다. 각 서비스가 자기 DB·스키마·계정·Docker 인프라를 소유하고 루트 Compose는 편의 실행을 조합한다.

JPA·Swagger, 구글 계열 포맷터, CQRS 계층과 낮은 결합도, 정책·엔티티·API·컨벤션 문서를 준비한다. 공통 내용은 루트 docs, 개별 서비스 계약은 각 서비스 docs에서 관리한다. enum은 code/status/codeName/description을 가진다. 이후 사용자 요청에 따라 MariaDB와 Compose 앱 실행을 반영했다.

후속 구조 결정은 멤버 → 그룹 단방향, 그룹 포트 호스트 미공개, 간단한 서비스 ID·키 헤더 검증이다. 사용자 인증은 제외했고 그룹 명세는 멤버를 경유한다. 그룹 Swagger의 실제 실행 중계는 이후 GROUP-001에서 보완됐으므로 SETUP-001 최초 완료 기능으로 소급 표시하지 않는다.

## 절차와 확인 한계

master에서 feature를 생성하고 구현·검증 후 develop에서 통합 검증한다. 사용자만 검증된 feature를 master에 반영한다. 당시 세부 단계와 결과는 기존 기록을 그대로 따른다.

이 문서는 대화에서 확인 가능한 요구와 저장소 자료를 요약한다. 모든 과거 프롬프트를 일대일 전사하거나 모든 중간 커밋의 통과를 증명하지 않는다. 최초 사전 조사 때의 임시 버전과 현재 고정 버전을 혼용하지 않으며 현재 실행에는 기술 스택 문서를 사용한다. 원본 세션은 수정하지 않는다.
