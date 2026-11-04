# 공통 문서

| 항목 | 내용 |
| --- | --- |
| 목적 | 멤버·그룹 서비스가 함께 따르는 규칙을 한곳에 둔다 |
| 범위 | 모든 서비스(`services/*`)와 공통 빌드 규칙(`build-logic/`) |
| 관련 문서 | 서비스별 결정은 `services/<서비스>/docs/`에 쓴다 |

## 읽는 순서

| 순서 | 문서 | 정책 ID | 내용 |
| --- | --- | --- | --- |
| 1 | [project-setup-plan](project-setup-plan.md) | - | SETUP-001 범위·확정 사항·완료 기준과 결과 |
| 2 | [architecture](architecture.md) | POL-ARCH | 서비스 경계, 직접 참조 금지, 인프라 소유 |
| 3 | [cqrs](cqrs.md) | POL-CQRS | 서비스 내부 계층과 조회·변경 경로 |
| 4 | [code-conventions](code-conventions.md) | POL-CODE | Kotlin·Spring·JPA 코드 규칙 |
| 5 | [formatting](formatting.md) | POL-FMT | Spotless + ktfmt(Google 스타일) |
| 6 | [api-conventions](api-conventions.md) | POL-API | 버전, 오류 응답, 페이지네이션, API 문서 |
| 7 | [security](security.md) | POL-SEC | JWT 인증, 인증 제외 경로, 키·토큰, Swagger 자동 인증 |
| 8 | [service-communication](service-communication.md) | POL-COMM | 서비스 간 HTTP 호출: 주소·타임아웃·재시도·토큰 |
| 9 | [data-conventions](data-conventions.md) | POL-DATA | 서비스별 DB·스키마·계정, Flyway, 다른 서비스 데이터 |
| 10 | [testing](testing.md) | POL-TEST | 테스트 계층, Testcontainers, 가짜 비밀값 |
| 11 | [technology-stack](technology-stack.md) | - | 버전 표와 확인 근거, Spring Boot 4 변경점 |
| 12 | [local-development](local-development.md) | - | 로컬 실행 방식, 포트·이름 표, 비밀값 준비 |
| 13 | [commit-conventions](commit-conventions.md) | - | 커밋 메시지·브랜치 흐름 요약 |
| - | [decisions/](decisions/) | ADR | 결정 기록(JDK 21, 공유 모듈 미사용, 고정 JWT 키) |

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
- 문서는 한국어로 쓴다. 코드 식별자·설정 키·명령은 원문 그대로 둔다.
