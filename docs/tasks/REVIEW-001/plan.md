# REVIEW-001 문서 정리·계약 확정 계획

| 항목 | 내용 |
| --- | --- |
| 분류 | 작업 계획 |
| 상태 | 문서 커밋·develop 검증 완료. 게시 중 발생한 로그 동기화 누락·반복 검사 지연 긴급 보완 및 재검증 후 master 반영 |
| 작성일 | 2026-11-05 |
| 검토 기준 | master bc3ec02be37ae61d72be897280090e59ea81352d |
| 브랜치 | master → feature/REVIEW-001 |
| 요청 | [문서 정비 요청](request.md) |
| 후속 | [REVIEW-002 코드 작업 계획](../REVIEW-002/plan.md) |
| 규칙 | [에이전트 규칙](../../../AGENTS.md), [커밋·브랜치](../../common/commit-conventions.md) |

## 1. 목적과 작업 분리

기존 통합 REVIEW-001 계획을 문서 작업인 REVIEW-001과 코드 작업인 REVIEW-002로 분리한다. 이 계획은 통합안의 작업 순서와 범위를 대체한다. 코드가 잘못되었는지, 현재 문서가 낡았는지, 새 요구로 계약을 바꾸는 것인지를 구분하여 후속 구현의 기준을 확정한다.

두 작업의 요청서·계획서는 모두 문서이므로 REVIEW-001에서 작성한다. REVIEW-002 계획을 작성한다고 REVIEW-002 브랜치를 동시에 만들거나 코드를 변경하지 않는다. REVIEW-001 계획과 결과의 커밋 ID는 REVIEW-002 시작 시 기록한다.

| REVIEW-001에서 수행 | REVIEW-002에서 수행 |
| --- | --- |
| 현재 코드·테스트·응답의 읽기 전용 조사 | 승인된 계약에 맞춘 코드 변경 |
| README·공통 문서·서비스 문서 정리 | 응답 DTO·매퍼·중계·소비자 변경 |
| 과거 요청·검증 기록의 분류 및 정정 주석 | 테스트 보완·패키지 이동·문서 검사 스크립트 |
| 목표 계약의 필드·타입·null·오류·권한 정의 | 승인된 인증·입력·로그·설정 변경 |
| 성능 가설과 측정 조건 작성 | 격리된 환경의 측정과 근거가 있는 개선 |
| AI 로깅 문제와 제출 기준 문서화 | 승인된 수집·검증 로직 보완과 회귀 검사 |

원래 문서 단계에서는 애플리케이션·테스트·DB·빌드·인프라·로깅 설정을 수정하지 않는다. 문서 경로가 코드·설정 주석에도 있으면 변경 대상을 인계표에 적고 REVIEW-002에서 고친다. 현재 동작을 바꾸지 않는 AGENTS.md·CLAUDE.md의 문서 안내 링크는 이번 문서 작업에 포함할 수 있다.

2026-11-05 추가 요청으로 게시를 막는 로그 수집기 수정만 긴급 범위에 추가한다. 사용자가 반복 검사의 지연 개선과 완료 후 커밋·병합을 요청했다. feature에서 수집기·회귀 테스트·운영 문서를 보완한 뒤 develop 재검증을 수행한다. 변경은 로그 전용 커밋 선택 누락, 내용 기반 검증 캐시, 중복 검사·메모리 사용 감소, 진행 표시다. 앱 API·DB·인프라 변경과 미승인 계약은 여전히 REVIEW-002에서 다룬다. [검증 결과와 한계](../../verification/REVIEW-001/logging-performance.md)를 함께 확인한다.

## 2. 문서의 기준과 승인 상태

우선순위는 최신 사용자 확정 결정 → 승인된 목표 계약 → 현재 적용 중인 소유 정책이다. 계획의 제안이나 작성자의 추정은 사용자 확정 결정과 동등하게 취급하지 않는다.

- 현재 실행 기준: docs/common의 유효 정책과 services/<서비스>/docs/api.md·policies.md·entities.md.
- REVIEW-002 적용 예정 계약: 각 서비스 docs/api-review.md. 계약 버전, 적용 상태, 승인 근거를 머리에 표시한다. 이 파일은 문서 단계에서 작성할 산출물이다.
- 과거 요청·계획: docs/tasks/<ID>. 당시 범위를 보존하며 현재 기준 링크를 제공한다.
- 검증 증거: docs/verification/<ID>. 실행한 커밋·명령·환경·결과를 보존한다.
- JSON 예제의 정본은 서비스 소유 계약에 둔다. 공통 정책이나 작업 요청서에 별도 복사본을 유지하지 않는다.

api-review.md는 승인 전에는 제안, 승인 후에도 코드 적용 전까지는 적용 예정이다. 현행 api.md의 JSON 예제와 openapi.json을 미래 구현으로 미리 바꾸지 않는다. 현재 계약을 검사하는 기존 테스트가 개정안을 현행 응답으로 오인하지 않게 한다. REVIEW-002에서 구현·검증한 후 개정안을 현행 소유 문서에 반영한다.

## 3. 검토로 확인된 사실과 정정

아래는 2026-11-05 master bc3ec02 검토 시점의 증거다. 이후 변경 시 재확인하며 실시간 상태로 표시하지 않는다.

| 항목 | 확인 결과 | 문서 처리 |
| --- | --- | --- |
| 멤버 응답 | 목록·상세에 groups 배열. 상세 최상위 createdAt·createdBy, 소속 내부 joinedAt 실제 반환 | 문서에만 남은 필드라고 쓰지 않음. 목표 응답 변경과 분리 |
| 현행 API 정의 | 위 필드가 api.md·DTO·매퍼·OpenAPI·테스트에 함께 있음 | 현행 명세 위반으로 단정하지 않음 |
| 그룹 상세 | memberId/groupId/groupName/registeredDate/pricing/permission 6필드 | 기존 평면 단일 객체 유지 |
| 단일 소속 | DB UNIQUE(member_id), 조회 시 다건 방어 존재 | 배열 표현과 데이터 정합성을 별도 판정 |
| 추가 필드 | 멤버 계약 테스트가 futureField를 허용. 객체 스키마 12개 모두 추가 속성 금지 미설정 | 정의 외 응답 금지의 구현·테스트 보완 필요 |
| 그룹 중계 | 성공 JSON 객체의 필드 목록을 제한하지 않음. 오류는 6필드 검사 | 성공 응답의 추가 필드 차단을 후속 항목에 추가 |
| 중계 접근 | 사용자 무인증 중계가 실제 가능하며 GROUP-001 요청서에 명시 | 기존 정책과 신규 접근 제한을 구분 |
| 중복 ID | 기존 계약은 400. 실제 요청도 400 | 중복 제거 성공 처리는 변경 제안 |
| 그룹 테스트 | 상세 500·503 HTTP 계약 검사가 이미 있음. 잘못된 상세 ID 400·빈 summary 200은 실제 요청 확인 | 검증 빈틈을 엔드포인트별로 분리. 빈 summary는 정상 사례 |
| 과거 요청 | MEMBER-001에는 후속 대체 배너가 이미 있음 | 당시의 제외 범위·실행 결과를 현재 기능으로 다시 쓰지 않음 |
| AI 스냅샷 | develop의 52개 스냅샷 파일이 master와 동일. 후속 전체본에 SETUP·MEMBER 기록 확인 | 병합 시점 커밋 부재와 로그 원문 누락을 구분 |
| AI 수집 상태 | Claude .meta.json 재작성 감지 보류 3건, Codex hook 실제 실행 미확인 | 코드 수정과 별개로 이슈·제출 판정 기준 기록 |

주요 근거: [멤버 API](../../../services/member-service/docs/api.md), [그룹 API](../../../services/group-service/docs/api.md), [멤버 응답](../../../services/member-service/src/main/kotlin/task/member/presentation/query/response/MemberResponses.kt), [중계 응답 처리](../../../services/member-service/src/main/kotlin/task/member/infrastructure/client/GroupApiProxyClient.kt), [멤버 계약 검사](../../../services/member-service/src/test/kotlin/task/member/contract/MemberOpenApiContractTests.kt).

## 4. 문서 분류와 이동 계획

```text
docs/
  README.md                        분류별 색인과 읽는 순서
  common/                          현재 공통 정책·실행·기술 기준
    ai-logging.md                  README에서 분리한 로깅 운영 기준
    decisions/                     승인·제안·대체 상태를 구분한 ADR
  tasks/
    SETUP-001/                     요청 재구성·구성 계획·구조 변경 계획
    LOG-001/                       요청 재구성·로깅 계획
    MEMBER-001/                     당시 요청·계획
    GROUP-001/                      당시 요청·계획
    REVIEW-001/                    요청·계획·결정표·후속 인계표
    REVIEW-002/                    코드 작업 요청·계획
  verification/
    <작업ID>/                      해당 시점의 검증 증거
services/<서비스>/docs/             현행 API·정책·엔티티·공유 계약
  api-review.md                    승인 상태를 가진 REVIEW-002 개정안
```

| 기존 위치 | 목표 위치 |
| --- | --- |
| docs/common/project-setup-plan.md | docs/tasks/SETUP-001/plan.md |
| docs/common/structure-change-plan.md | docs/tasks/SETUP-001/structure-change-plan.md |
| docs/ai-logging-plan.md | docs/tasks/LOG-001/plan.md |
| docs/common/requests/MEMBER-001.md | docs/tasks/MEMBER-001/request.md |
| docs/common/member-query-plan.md | docs/tasks/MEMBER-001/plan.md |
| docs/common/requests/GROUP-001.md | docs/tasks/GROUP-001/request.md |
| docs/common/group-query-plan.md | docs/tasks/GROUP-001/plan.md |
| docs/common/setup-verification.md | docs/verification/SETUP-001/setup-verification.md |
| docs/common/member-query-stage-1~5-verification.md | docs/verification/MEMBER-001/stage-1~5.md |
| docs/common/group-query-verification.md | docs/verification/GROUP-001/group-query-verification.md |
| README의 로깅 운영 내용 / 과거 검증 내용 | docs/common/ai-logging.md / docs/verification/LOG-001/ai-logging-verification.md |

이동은 삭제·추가를 한 관심사 커밋으로 묶는다. Git 기록을 다시 쓰지 않는다. 문서 링크와 AGENTS.md·CLAUDE.md 안내를 갱신한다. 실행에 사용되는 비문서 파일의 경로 변경이 필요한 경우 호환용 안내 문서를 남기고 REVIEW-002 인계표에 기록하여 중간 상태의 실행을 깨뜨리지 않는다.

SETUP-001·LOG-001 요청서는 기존 계획·커밋·확인 가능한 사용자 세션을 근거로 사후 재구성한다. 사후 작성 표시, 근거, 확인 불가 범위를 남긴다. AI 원본·ai-logs의 내용을 편집하거나 이 문서 정리에 맞춰 경로 문자열을 치환하지 않는다.

README에는 프로젝트 목적, 두 서비스와 포트, 네 API 요약, 실행·빌드·테스트 명령, 문서 색인, AI 도구·자동 로깅 요약과 운영 문서 링크를 남긴다. 로깅 절차를 분리하면서 기존 보호·보류·수동 실행 조건을 삭제하거나 강화된 구현이 이미 있는 것처럼 쓰지 않는다.

프로젝트 소개는 서비스의 역할과 구동 방법을 중심으로 작성한다. 사용자가 삭제를 요청한 과제명 문구는 README에 넣지 않는다.

### 4.1 README 최소 설치 조건 — 추가 확정 요구

README의 실행 명령 앞에 설치 조건을 둔다. 프로그램별 필요 작업, 최소 기능/버전 조건, 프로젝트 검증 버전, 확인 명령을 구분한다. 검증한 버전을 곧바로 역사적인 최소 호환 버전이라고 쓰지 않는다.

- JDK 21과 JAVA_HOME/PATH: 로컬 빌드·IDE뿐 아니라 현재 최초 실행의 generateLocalSecrets에도 필요하다. 현재 절차를 Docker만 설치하면 되는 것으로 안내하지 않는다.
- Docker Desktop(Windows) 또는 Linux 컨테이너·BuildKit을 제공하는 Engine과 Compose: 데몬 실행, Docker API 접근, 지원 OS·가상화/선택 백엔드 조건을 안내한다. CLI/Engine·Compose·Desktop은 별개 버전이다.
- Compose include의 기능 하한은 공식 문서상 2.20.3이다. 프로젝트 전체의 검증 조합(CLI/Engine 29.8.2, Compose 5.5.1)과 구분하고 하한 버전에서 전체 테스트를 수행했다고 표시하지 않는다.
- Git은 소스 취득·개발·브랜치·로그 제출에, Python 3.11 이상은 AI 수집·Git hook·실행 검증에 필요하다. 두 앱의 컨테이너 런타임 의존과 구분한다.
- Gradle은 Wrapper 9.3.0을 사용한다. 전역 Gradle·Kotlin·Spring Boot·호스트 MariaDB 별도 설치를 기본 실행의 필수 조건으로 넣지 않는다.
- IDE는 선택이다. Node.js와 Edge는 기존 브라우저 자동 검증을 실행할 때의 조건이며 두 서비스 실행만을 위한 필수 설치는 아니다.
- 버전 확인 명령, 최초 의존성/이미지 다운로드 접근, 공개 포트 사용 가능 여부를 안내한다. 비밀 파일 내용·환경변수 전체를 출력하는 확인 명령은 쓰지 않는다.

README 초안의 확인 근거: [기술 스택](../../common/technology-stack.md), [현행 준비 명령](../../common/local-development.md), [Compose include 공식 조건](https://docs.docker.com/compose/how-tos/multiple-compose-files/include/), [Windows Docker 설치 조건](https://docs.docker.com/desktop/setup/install/windows-install/).

## 5. 기존 R-01~R-24의 인계와 추가 요구

검토표는 요구 ID, 사용자 근거, 현행 상태, 목표 상태, 문서 소유자, 검증 수단, REVIEW-002 작업 ID, 승인 상태를 갖는다. 판정은 충족/부분 충족/미충족/확인 불가, 변화는 유지/결함 수정/계약 변경/측정 후 결정으로 별도 관리한다.

| 요구 ID | REVIEW-001 문서 작업 | REVIEW-002 검증·변경 대상 |
| --- | --- | --- |
| R-01~R-06 | 서비스·프로세스·DB 분리, 단방향 HTTP 호출, 공개 경계의 현재 사실 정리 | 독립 빌드·기동·계정·네트워크·참조 금지 회귀 |
| R-07 | 0~1 소속과 공개 응답의 객체/배열을 분리 정의 | DB 고유 제약·다건 방어·응답 형태 |
| R-08~R-09 | 과금·권한 code와 내부 메타데이터 보존 | code 저장·변환·표시명 비노출 |
| R-10 | Host 의미와 현행 숫자 IP:port 유지 여부 | 승인된 주소 규칙만 적용 |
| R-11~R-14 | 멤버 경로·응답·페이지·정렬·오류·소비자 영향 | 공개 응답과 조회 결합·장애·트랜잭션 |
| R-15~R-17 | 그룹 계약·호출자·중복·미소속·공유 방안 | 두 그룹 API·멤버 HTTP 소비자·중계 |
| R-18~R-19 | 계층 규칙, 테스트 관점별 패키지 및 빈틈 표 | 구조·테스트 이동·독립성 검사 |
| R-20~R-21 | README·색인·중복·현재/이력 분리 | 문서 검사 자동화, 코드 주석 참조 정리 |
| R-22 | JWT와 Swagger 정책의 승인 여부·ADR | 승인된 경우에만 보안 구현 |
| R-23 | 작업별 커밋 유무와 실제 로그 완전성 분리, 보류 원인 기록 | 승인된 수집 보완·최종 제출 검사 |
| R-24 | 사용 AI 도구·로깅 방식·원본 보존·제출 위치 요약 | 실제 hook 동작과 문서 일치 확인 |
| R-25 추가 | 정의된 응답 외 필드 금지, 중첩 객체·중계 포함 | 정확한 키 집합·추가 필드 거부·DTO 투영 |
| R-26 추가 | README 최소 설치 프로그램·버전·구동 방식별 필요 조건·확인 명령 | 실행 검증 환경이 문서 조건을 충족하는지 확인 |

## 6. 문서 단계에서 관리할 결정표

결정표의 실제 파일은 docs/tasks/REVIEW-001/decisions.md로 작성한다. 아래는 작성 기준이며 미결정 항목을 승인으로 바꾸지 않는다. 각 항목에 결정, 사용자 확인 근거, 영향 계약, 적용 작업, 보류 시 유지할 현재 동작을 기록한다.

| ID | 주제 | 현재 상태와 제안 | 코드 단계 조건 |
| --- | --- | --- | --- |
| D1 | 그룹 summary 중복 ID | 현행 400. 제거 후 성공은 제안. 채택 시 원래 개수 1~100 검사 → 모든 ID 형식 검사 → 중복 제거. 로그는 원문 ID 대신 개수·요청 내 위치 등의 대안도 검토 | 처리와 로그 정책을 함께 확정 |
| D2 | 멤버 그룹 표현 | 현행 groups 배열. 목록 group·permission, 상세 단일 group 및 미소속 null은 제안. API별 필드 표 확정 필요 | 객체/배열·필드명·null 확정 |
| D3 | 감사·소속 시각 노출 | createdAt·createdBy·joinedAt은 현행 응답에 실제 존재. 최종 계약에서 제외한 값은 DTO에서 제외하되 DB 저장과 내부 모델은 유지 가능 | 제거/이름 변경을 응답 표에 명시 |
| D4 | 멤버 JWT | 현행 사용자 인증 제외. Resource Server 도입은 신규 정책. 발급자·대상·알고리즘·필수 claim·scope·오류 정의 | 승인 시 ADR-006 및 구현 목록 확정 |
| D5 | 테스트 토큰 | 실행 중 테스트 키/토큰 생성, 실행 검증은 secrets 파일 참조 제안. 유효기간·재발급·키 교체 정의 | 비밀값 비추적·비출력 유지 |
| D6 | 그룹 Swagger 실행 | 현행 멤버 경유 두 GET 제공. local 제한·JWT 여부·전용 권한은 제안. 토큰의 브라우저 전달 방식과 갱신 절차 필요 | 그룹 직접 포트·서버 키 비노출 유지 |
| D7 | 문서 분류 | common/tasks/verification, 서비스 docs 유지. 두 작업 계획을 이번 문서 브랜치에 작성 | 이동·링크 검증 |
| D8 | 주소 | 현행 숫자 IP:port. DNS 추가는 선택 제안이며 별도 Host의 필수 조건은 아님 | 미승인 시 현행 유지 |
| D9 | 계약 공유 | OpenAPI 산출물 공유 유지. 소비자 계약 비교는 버전·해시 고정 자료 또는 별도 통합 검사 | 다른 서비스 소스·런타임 DTO 참조 금지 |
| D10 | 과거 요청 | 당시 요구 보존, 상단 이력 상태와 대체 문서 명시. 현재 확정 계약을 과거 요청으로 다시 쓰지 않음 | 현재 문서는 최신 기준만 안내 |
| D11 | 요청 재구성 | SETUP·LOG에 사후 작성과 근거 표시 | 확인 안 된 발언·결과를 만들지 않음 |
| D12 | 장애 보호 | 현행 의존 API 503 유지. 회로 차단·동시 호출 한도는 측정 후 제안. 부분 성공과 새 상태 필드는 자동 도입하지 않음 | 미승인 시 타임아웃·기존 재시도 유지 |
| D13 추가 | 로그 지점·수준 | 요청·업무·DB·원격·재시도 INFO와 예외 ERROR가 현재 결정. 4xx 수준 변경이나 지점 축소는 별도 정책 변경 | 식별자·키·본문 비노출과 추적 연결 유지 |
| D14 추가 | 로그 제출 완전성 | 일반 개발 push의 경고 허용과 최종 제출 통과 조건을 분리. meta.json 재작성 원인과 보존 방법 조사 | 원본·기존 스냅샷 수정 금지, 승인된 보완만 수행 |
| D15 추가 | 정의 외 응답 | 추가 필드 비노출은 확정. 중계의 미정의 성공 필드는 계약 위반으로 차단하는 안을 검토. 내부 소비자의 무시 가능한 확장 필드와 공개 응답을 구분 | 모든 출력 경계의 정확한 필드 검사 |

API 계약 표에는 각 필드의 JSON 경로·타입·필수·null·의미·출처·예제·허용 code를 적는다. 그룹 상세의 기존 6필드, 권한 code만 노출, DB UTC TIMESTAMP(6)와 응답 Asia/Seoul yyyy-MM-dd HH:mm, 멤버/그룹 가입일의 독립성을 유지한다. 그룹 목록 및 미소속·오류도 빠뜨리지 않는다. page 설정 블록과 응답 메타데이터는 제거하지 않는다.

## 7. 단계와 산출물

| 단계 | 수행 내용 | 완료 근거 |
| --- | --- | --- |
| DOC-01 | 두 요청서·계획서, 기존 통합안에서의 분리 범위 | 범위·브랜치 순서·읽기 링크 확인 |
| DOC-02 | 추적 문서 목록, 정책-검증·요구-테스트 표, 현재 사실 정리 | docs/verification/REVIEW-001/findings.md, 관찰 시점·파일·줄·명령 |
| DOC-03 | 폴더 이동, 이력 분류, README·색인·현재 정책 정리, SETUP·LOG 사후 요청 | 이동 대응표·링크 검사·사용자 요구 누락 없음 |
| DOC-04 | 서비스별 api-review.md, decisions.md, 승인된 ADR 또는 제안 상태 ADR | 정확한 응답·오류·노출 범위와 소비자 영향, 미결정 표시 |
| DOC-05 | docs/tasks/REVIEW-001/handoff.md 작성, REVIEW-002 계획 갱신 | 확정/유지/보류 결정, 구현 ID·필수 검사·기준 커밋 |
| DOC-06 | 문서 최종 검증과 develop 문서 검토 | docs/verification/REVIEW-001/verification.md, master 인계 가능 여부 |

핵심 계약이 미결정이면 문서 검토 상태로 남긴다. 자동으로 권장안을 채택하지 않는다. 선택 항목은 현행 유지로 명시하여 후속 구현 범위를 잠글 수 있다. 문서 작성 완료와 REVIEW-002 착수 가능을 별도로 판정한다.

2026-11-05 수행 결과: DOC-01~03 문서 작성·재구성, DOC-04 미승인 개정안과 결정표, DOC-05 조건부 인계표를 작성했다. DOC-06의 로컬 문서 검사와 기존 build는 통과했다. 사용자 계약 확인과 develop 문서 검토·master 반영은 남아 있다. 근거는 [검증 기록](../../verification/REVIEW-001/verification.md), 코드 인계는 [handoff](handoff.md)다.

## 8. 검증과 완료 기준

- 파일·앵커 링크, 이동 후 상대 경로, 정책 ID 정의와 참조를 검사한다. 단순 정책 ID 언급을 중복 정의로 판정하지 않는다.
- 과거 이력·부정 예제의 groups/permissionName 같은 단어를 전역 금지어 검사로 삭제하지 않는다. 현재 계약의 구조와 적용 상태를 검사한다.
- 문서 이동 전후 추적 파일과 내용의 누락을 확인한다. 과거 실행 증거는 보존하고 정정은 별도 주석으로 남긴다.
- 소스·테스트·SQL·설정·openapi.json·로깅 스크립트에 작업 변경이 없어야 한다. 수집기가 만드는 로그 전용 변경은 문서 변경과 분리한다.
- 코드 구현, 신규 테스트, 성능 측정, hook 수정 완료를 문서 정리의 성과로 표시하지 않는다.
- 기존 커밋 규칙에 따라 커밋 전 build 검사를 수행하고 결과를 기록한다. 문서 작업을 이유로 공통 필수 검사를 생략하지 않는다. spotlessApply와 OpenAPI 갱신 명령은 실행하지 않는다. 기존 코드의 포맷·빌드 실패는 기록하고 별도 승인 없이 코드 수정으로 확대하지 않는다.
- 문서 검사 스크립트 신규 구현은 REVIEW-002로 넘긴다. 이 단계의 링크·표 검토는 읽기 전용 검사로 수행한다.
- 필수 결정·현행 유지 항목이 확정되고 인계표가 준비되어야 REVIEW-002 착수 가능으로 표시한다.

## 9. 브랜치·커밋·로그 흐름

1. 모든 AI 턴 종료 후 사용자가 new-feature REVIEW-001을 실행한다. master의 로깅 설정과 깨끗한 작업 폴더를 기존 도구로 확인한다.
2. feature/REVIEW-001에서 문서 작업·검증을 한다. 일반 커밋은 [REVIEW-001] 제목, 빈 줄, 두 칸 들여쓴 작업 내용, 공통 기여자 표기를 따른다.
3. AI 턴 중 Git 변경을 직접 실행하지 않는다. 승인된 커밋은 기존 task reserve commit, 단계 로그는 stage-log로 예약한다. 실행된 커밋 SHA를 확인한 뒤 다음 커밋 단계를 진행한다.
4. 사용자 절차로 develop에 병합해 문서 정합성과 기존 동작의 회귀를 검증한다. 보완은 resume으로 같은 feature에서 진행한 뒤 develop에 다시 병합한다.
5. 사용자가 push-develop feature/REVIEW-001로 develop 로그만 master에 동기화하고, 검증한 feature를 merge-feature feature/REVIEW-001로 master에 병합·push한다. develop 전체는 master에 병합하지 않는다.
6. REVIEW-001이 origin/master에 반영되고 인계가 완료된 뒤 new-feature REVIEW-002를 실행한다. REVIEW-001 브랜치를 부모로 REVIEW-002를 만들지 않는다.

커밋 단위 제안: 요청·계획 → 문서 이동·참조 → 현재 정책·README → 목표 계약·결정·인계. 실제 파일 변경과 검증이 끝난 단위만 예약한다. 지금 계획을 작성한 것만으로 커밋·병합을 예약하지 않는다.

AI 원본과 기존 스냅샷은 편집하지 않는다. 보류 해소는 판정·승인된 정책 절차로 처리하고, 보류를 지우기 위해 로그를 제외하거나 과거 커밋을 수정하지 않는다. Codex hook 미확인과 Claude meta.json 보류는 원인·현재 영향·최종 제출 판정에 남긴다.
