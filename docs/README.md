# 프로젝트 문서 시작점

| 항목 | 내용 |
| --- | --- |
| 분류 | 문서 색인 |
| 상태 | 현재 |
| 목적 | 현재 기준, 적용 예정 계약, 과거 작업과 검증 증거를 구분한다 |

## 읽는 순서와 소유권

| 순서 | 위치 | 역할 |
| --- | --- | --- |
| 1 | [루트 README](../README.md) | 최소 설치 조건·프로젝트 목적·실행·빌드·AI 도구 요약 |
| 2 | [common](common/README.md) | 두 서비스에 공통으로 적용되는 정책·기술·운영 |
| 3 | 아래 서비스 소유 문서 | 현재 업무 계약·정책·엔티티·공유 명세 |
| 4 | [tasks](tasks/README.md) | 작업별 요청·계획·결정·인계. 과거 범위를 현재 계약으로 사용하지 않음 |
| 5 | [verification](verification/README.md) | 특정 커밋·환경·시점에서 실행한 검증 결과와 확인 불가 항목 |

## 서비스 소유 문서

| 서비스 | 실행 | 정책 | 데이터 | 현행 API | 검토·개정안 |
| --- | --- | --- | --- | --- | --- |
| 멤버 | [README](../services/member-service/docs/README.md) | [정책](../services/member-service/docs/policies.md) | [엔티티](../services/member-service/docs/entities.md) | [API](../services/member-service/docs/api.md)·[공유](../services/member-service/docs/contracts.md) | [개정안](../services/member-service/docs/api-review.md) |
| 그룹 | [README](../services/group-service/docs/README.md) | [정책](../services/group-service/docs/policies.md) | [엔티티](../services/group-service/docs/entities.md) | [API](../services/group-service/docs/api.md) | [개정안](../services/group-service/docs/api-review.md) |

## 문서 상태

- 현재: 실행 중인 코드·설정의 기준. 코드에 없는 새 요구는 적용 상태를 따로 적는다.
- 제안: 아직 결정하지 않은 대안. 구현 승인으로 사용하지 않는다.
- 적용 예정: 승인됐지만 코드에 반영하지 않은 계약. 현재 OpenAPI와 구분한다.
- 이력: 당시의 요청·계획·검증. 과거 수치·명령을 현재 결과로 고쳐 쓰지 않는다.
- 사후 재구성: 원래 요청서가 없어 기존 문서·이력으로 정리한 기록. 확인 근거와 한계를 표시한다.

공통 내용은 common, 서비스 업무 내용은 서비스 docs에서 관리한다. 계약 변경 시 사용자 결정과 소유 계약을 먼저 정하고 코드·예제·OpenAPI·테스트를 함께 맞춘다. 문서에 완료라고 쓰는 것만으로 구현·검증 완료가 되지 않는다.

AI 수집·제출 절차는 [운영 문서](common/ai-logging.md)와 [에이전트 규칙](../AGENTS.md)을 따른다. ai-logs의 원본 사본은 문서 정리 대상에서 제외한다.
