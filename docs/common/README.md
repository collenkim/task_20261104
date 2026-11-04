# 공통 문서

| 항목 | 내용 |
| --- | --- |
| 목적 | 멤버·그룹 서비스가 함께 따르는 규칙을 한곳에 둔다 |
| 범위 | 모든 서비스(`services/*`)와 공통 빌드 규칙(`build-logic/`) |
| 관련 문서 | 서비스별 결정은 `services/<서비스>/docs/`에 쓴다 |

최상위 요약과 문서 책임은 [프로젝트 문서 시작점](../README.md), 실행은 [로컬 실행](local-development.md)을 먼저 확인한다.

## 읽는 순서

| 순서 | 문서 | 정책 ID | 내용 |
| --- | --- | --- | --- |
| 1 | [project-setup-plan](project-setup-plan.md) | - | SETUP-001 범위·확정 사항·완료 기준과 결과 |
| 1-1 | [structure-change-plan](structure-change-plan.md) | G-xx | 멤버 공개 진입점·그룹 내부 전용 전환의 보완 항목과 완료 기준 |
| 2 | [architecture](architecture.md) | POL-ARCH | 서비스 경계, 직접 참조 금지, 인프라 소유, 공개 범위와 호출 방향 |
| 3 | [cqrs](cqrs.md) | POL-CQRS | 서비스 내부 계층과 조회·변경 경로 |
| 4 | [code-conventions](code-conventions.md) | POL-CODE | Kotlin·Spring·JPA·enum 코드명/설명 규칙 |
| 5 | [formatting](formatting.md) | POL-FMT | Spotless + ktfmt(Google 스타일) |
| 6 | [api-conventions](api-conventions.md) | POL-API | 버전, 오류 응답, 페이지네이션, API 문서 |
| 7 | [security](security.md) | POL-SEC | 내부 키, 검사 제외 경로, 키·비밀번호, 그룹 접근 통제 |
| 8 | [service-communication](service-communication.md) | POL-COMM | 멤버 → 그룹 HTTP 호출: 방향·주소·타임아웃·재시도·내부 키·계약 소유 |
| 9 | [data-conventions](data-conventions.md) | POL-DATA | 서비스별 DB·스키마·계정, Flyway, 다른 서비스 데이터 |
| 10 | [testing](testing.md) | POL-TEST | 테스트 계층, Testcontainers, 가짜 비밀값 |
| 11 | [technology-stack](technology-stack.md) | - | 버전 표와 확인 근거, Spring Boot 4 변경점 |
| 12 | [local-development](local-development.md) | - | Compose 일괄/단독 실행, DB 전용 실행, 모드별 볼륨 |
| 13 | [commit-conventions](commit-conventions.md) | - | 커밋 메시지·브랜치 흐름 요약 |
| - | [decisions/](decisions/) | ADR | 결정 기록(JDK 21, 공유 모듈 미사용, 내부 키, MariaDB, 멤버 공개·그룹 내부 전용) |

AI 세션 로깅 도구는 [../ai-logging-plan.md](../ai-logging-plan.md)와 저장소 `README.md`를 본다.

## 정책 ID

- 형식: `POL-<영역>-<번호 3자리>`. 예: `POL-API-002`.
- 코드 주석·테스트 이름·커밋 본문에서 이 ID로 정책을 가리킨다.
- 번호는 다시 쓰지 않는다. 폐기한 정책은 문서에 `폐기`로 남긴다.
- 결정 기록은 `ADR-<번호 3자리>`이고, 결정 배경과 대안을 남긴다.

## 문서 머리말 형식

공통·서비스 문서는 제목 아래에 다음 표를 둔다.

| 항목 | 내용 |
| --- | --- |
| 목적 | 이 문서가 정하는 것 |
| 범위 | 적용 대상(서비스·계층·환경) |
| 관련 코드 | 정책을 구현·검사하는 경로 |

- 공통 문서에는 규칙만 쓰고, 서비스 문서에는 그 서비스의 구체적인 결정을 쓴다.
- 명령의 기준 폴더를 적는다. 공통 문서의 명령은 저장소 루트 기준이고, 서비스 문서의 명령은 그 서비스 폴더 기준이다.
- 정책 문서는 확정된 목표 규칙을 쓰고, 아직 코드에 없는 규칙은 `미적용(G-xx)`로 표시한다. 실행 안내·현재 상태 문서는 지금 동작을 쓰고 바뀔 점을 따로 적는다.
- 문서는 한국어로 쓴다. 코드 식별자·설정 키·명령은 원문 그대로 둔다.

## 현재 구현과 검증

- [요구사항 재검증](setup-verification.md): 충족·미구현·과거 검증을 구분한다.
- [멤버 서비스](../../services/member-service/docs/README.md)·[그룹 서비스](../../services/group-service/docs/README.md)의 정책·엔티티·API 정의서를 함께 읽는다.
