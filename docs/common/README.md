# 공통 문서

| 항목 | 내용 |
| --- | --- |
| 분류 | 공통 정책 색인 |
| 상태 | 현재 |
| 목적 | 공통 목적·규칙·운영의 소유 문서를 한곳에서 찾는다 |

| 문서 | 정책 영역 | 내용 |
| --- | --- | --- |
| [기술 스택](technology-stack.md) | 버전 | 고정 위치·프로젝트 검증 조합 |
| [아키텍처](architecture.md) | POL-ARCH | 서비스 독립성·코드/DB 참조 금지·공개 경계 |
| [CQRS](cqrs.md) | POL-CQRS | 조회·변경·포트·트랜잭션 경계 |
| [코드 컨벤션](code-conventions.md) | POL-CODE | Kotlin·Spring·JPA·로그·enum 내부 메타데이터 |
| [포매터](formatting.md) | POL-FMT | Google 스타일·Spotless 검사 |
| [API](api-conventions.md) | POL-API | 오류·페이지·시각·공유 계약·출력 필드 제한 |
| [보안](security.md) | POL-SEC | 현재 내부 호출 키·공개 경로·비밀 파일 |
| [서비스 통신](service-communication.md) | POL-COMM | 단방향 HTTP·타임아웃·재시도·계약 |
| [데이터](data-conventions.md) | POL-DATA | 소유권·Flyway·시각·시드 |
| [테스트](testing.md) | POL-TEST | DB·계층·계약·실행 검증의 구분 |
| [로컬 실행](local-development.md) | 실행 | 일괄·단독·IDE·볼륨 |
| [커밋·브랜치](commit-conventions.md) | Git | feature 단위·develop 검증·사용자 master 반영 |
| [AI 로깅](ai-logging.md) | 운영 | hook·보류·스냅샷·예약·로그 동기화 |
| [결정 기록](decisions/README.md) | ADR | 현재 결정과 대체된 결정의 배경 |

작업 계획·요청은 [작업 색인](../tasks/README.md), 실행 증거는 [검증 색인](../verification/README.md)에 둔다. 서비스 고유 API·정책·엔티티는 [문서 시작점](../README.md)의 서비스 문서가 소유한다.

## 정책 ID와 문서 형식

- 정책 ID는 POL-영역-세 자리 번호다. 번호를 재사용하지 않고 폐기는 명시한다. 정의 제목과 단순 참조를 구분한다.
- ADR은 결정 배경·대안·상태·적용 범위를 기록한다. 신규 제안은 기존 승인을 자동 대체하지 않는다.
- 문서 머리에 분류·상태·목적 또는 범위를 표시한다. 현재/제안/적용 예정/이력은 [문서 상태](../README.md#문서-상태)를 따른다.
- 공통 명령은 저장소 루트 기준이다. 서비스 명령은 해당 서비스 폴더 기준인지 명시한다.
- 한국어로 작성하며 코드 식별자·명령·설정 키는 원문을 사용한다. 예제의 필드와 규칙은 소유 문서를 연결하고 중복 정본을 만들지 않는다.
- 과거 기록의 다중 소속·옛 시각 형식 등은 이력으로 보존한다. 현재 정책의 적용 상태와 혼동하지 않도록 대체 문서를 연결한다.
