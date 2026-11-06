# 결정 기록 (ADR)

| 항목 | 내용 |
| --- | --- |
| 분류 | 결정 색인 |
| 상태 | 현재 색인 · 개별 ADR의 승인·대체 상태를 따름 |
| 목적 | 되돌리기 어려운 공통 결정의 배경·대안·결과를 남긴다 |
| 범위 | 두 서비스에 함께 적용되는 결정 |
| 관련 문서 | [공통 문서 목록](../README.md) |

| ID | 제목 | 상태 |
| --- | --- | --- |
| [ADR-001](ADR-001-jdk-21.md) | JDK 21 사용 | 확정 |
| [ADR-002](ADR-002-no-shared-module.md) | 서비스 공유 런타임 모듈을 두지 않음 | 확정 |
| [ADR-003](ADR-003-fixed-jwt-keys.md) | 미리 생성한 고정 JWT 키·토큰 사용 | 대체됨(ADR-005) |
| [ADR-004](ADR-004-mariadb.md) | 서비스별 MariaDB 사용 | 확정 |
| [ADR-005](ADR-005-member-edge-group-internal.md) | 멤버 진입점·그룹 내부 키·두 서비스 명세 제공 | 적용됨. 사용자 인증 제외 항목만 ADR-006으로 대체 |
| [ADR-006](ADR-006-member-jwt-authentication.md) | 멤버 API JWT 인증과 local Swagger 자동 토큰 | 확정·적용(REVIEW-002) |

REVIEW-001 결정표의 JWT·중계 제한 항목은 REVIEW-002에서 사용자가 확정했고 ADR-006으로 적용했다. 결정 경위는 [결정표](../../tasks/REVIEW-001/decisions.md)에 있다.

- 형식: 상태·날짜 → 배경 → 결정 → 검토한 대안 → 결과.
- 결정을 바꿀 때는 기존 문서를 고치지 않고 새 ADR을 쓴 뒤, 기존 문서 상태를 `대체됨(ADR-xxx)`으로 바꾼다.
