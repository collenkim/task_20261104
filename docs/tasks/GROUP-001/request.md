# GROUP-001 구현 요청서

| 항목 | 내용 |
| --- | --- |
| 분류 | 작업 기록 |
| 상태 | 이력 · 당시 요청·계획·검증 내용을 보존. 현재 구현 완료 선언이 아님 |
| 현재 기준 | [문서 색인](../../README.md)의 공통·서비스 정의를 따른다 |

| 항목 | 내용 |
| --- | --- |
| 목적 | 그룹 조회 API와 멤버의 실제 연동, 단일 소속 정합성, 멤버 경유 Swagger 실행 구현 |
| 당시 상태 | 2026-11-05 사용자 확정 요구. 실제 완료 여부는 검증 기록에서 관리 |
| 범위 | 그룹·멤버 서비스 코드, 소유 문서, 공통 정책·검증·실행 구성 |
| 정본 | 저장소의 이 파일. Temp 계획·구두 변경 사항을 통합 |
| 전체 계획 | [그룹 조회 계획](plan.md) |
| 실제 결과 | [구현·검증 기록](../../verification/GROUP-001/group-query-verification.md) |

## 0. 시작 조건과 문서 우선순위

- master에서 생성한 feature/GROUP-001에서 그룹 구현과 멤버 연동을 함께 변경한다. 기존 MEMBER-001 구현과 사용자 변경을 유지한다.
- [AGENTS.md](../../../AGENTS.md)를 따른다. AI 턴 중 Git 변경을 직접 실행하지 않고 커밋은 예약한다. develop은 통합 검증용, master에는 검증된 feature만 사용자가 병합한다.
- secrets/·.env·인증 파일을 읽지 않으며 AI 원본·스냅샷을 직접 편집하거나 스테이징하지 않는다.
- 최신 사용자 결정 → 이 요청서·[계획](plan.md) → 소유 API/정책 문서 순서다. 승인된 변경은 코드와 기준 문서를 함께 맞춘다.
- [MEMBER-001 요청서](../MEMBER-001/request.md)의 다중 소속 허용·permissionName·UTC 응답·이전 그룹 경로는 이 요청서로 대체한다. 과거 실행 증거는 고쳐 쓰지 않는다.

## 1. 포함·제외와 책임

- 포함: 그룹 조회 2개, 자기 DB/JPA/시드, 멤버 HTTP 소비자, 공통 시각 규칙, 최소 내부 인증, 로그 추적, OpenAPI와 Swagger 중계 실행.
- 제외: 사용자 인증·인가, 쓰기 API, 다중 소속 공개 계약, 메시지 브로커, 서비스 간 공유 DTO/엔티티/enum 런타임 모듈.
- 그룹은 멤버 서비스·DB를 역조회하지 않는다. 두 서비스는 각자의 앱·DB·스키마·계정·Compose·볼륨을 소유한다.

## 2. 그룹 API 계약

정확한 요청/응답 예제·타입·필수/null·오류 표는 [그룹 API 정의서](../../../services/group-service/docs/api.md)가 소유한다. 요청서에 별도의 JSON 복사본을 관리하지 않는다.

| API | 입력 | 성공 응답 |
| --- | --- | --- |
| GET /api/groups/summary | 반복 memberIds, 서로 다른 ID 1~100개 | group 배열. 그룹별 groupId/groupName/totalMemberCount/members |
| GET /api/group/{memberId} | 멤버 ID 1개 | 기존 6개 필드의 평면 단일 객체 |

- ID는 소문자 영숫자 10자리다. 누락·공백·중복·형식 위반·101개 이상·콤마 결합은 400이다.
- summary의 members는 요청 ID 중 실제 소속 멤버만 담고 totalMemberCount는 그룹 전체 인원이다. 그룹·멤버 ID 순으로 정렬한다. 모두 미소속이면 group은 빈 배열이다.
- 상세 필드는 memberId, groupId, groupName, registeredDate, pricing, permission만 유지한다. 배열이나 감싸는 객체로 바꾸지 않는다.
- permission은 Viewer/Editor/Admin, pricing은 Free/Advanced/Premium의 code다. codeName/status/description은 enum 내부 관리용이며 응답에 추가하지 않는다.
- 미소속 상세는 404 GROUP_MEMBERSHIP_NOT_FOUND, 다건 조회는 GroupMembershipAmbiguousException + 500 GROUP_MEMBERSHIP_AMBIGUOUS + ERROR 로그다.
- 그룹 DB 가용성 장애는 503 SERVICE_UNAVAILABLE, 알 수 없는 저장 코드 등 데이터/내부 오류는 500이다.

## 3. 단일 소속과 향후 변경

- 현재 멤버당 0~1개 소속만 허용한다. group_membership.member_id에 NOT NULL + UNIQUE, (group_id, member_id)에도 UNIQUE를 둔다.
- 동시 입력도 DB가 차단해야 한다. 조회 방어 로직은 UNIQUE와 별도로 유지한다. 상세/summary의 다건을 첫 행 선택이나 대표 그룹으로 숨기지 않는다.
- 향후 다중 소속은 제약 제거, 상세 API·멤버 소비자·문서·테스트를 함께 변경하는 별도 작업이다.

## 4. 멤버 연동과 오류

- [멤버 API](../../../services/member-service/docs/api.md)의 경로·페이징·이름 정렬·groups 배열은 유지하며 현재 최대 소속 수만 1로 제한한다.
- 목록은 DB 페이지의 ID를 summary에 한 번 전달한다. 페이지 크기가 1이어도 summary를 사용한다.
- 상세는 멤버 존재를 확인한 뒤 그룹 detail을 한 번 호출한다. 표시명 획득을 위한 추가 호출은 없다.
- 정상 sparse summary에 없는 요청 ID와 상세의 정확한 404 GROUP_MEMBERSHIP_NOT_FOUND만 미소속으로 변환한다.
- 다른 그룹 4xx·계약 오류·리다이렉트는 멤버 500, 그룹 5xx와 연결 실패·시간 초과는 멤버 503이다. HTTP 실패를 fixture나 빈 결과로 대체하지 않는다.
- 표시명 permissionName을 제거하고 권한 code만 제공한다. 소비자는 비어 있지 않은 새 code와 추가 필드를 수용하며 그룹 enum을 복제하지 않는다.
- 기존 spring.data.web.pageable 설정 블록과 입력·반복 파라미터·Int/offset 제한을 제거하지 않는다.

## 5. 데이터·시각·시드

- [그룹 엔티티](../../../services/group-service/docs/entities.md), [멤버 엔티티](../../../services/member-service/docs/entities.md), [데이터 규칙](../../common/data-conventions.md)을 따른다.
- DB 시각은 UTC TIMESTAMP(6)이며 마이크로초를 보존한다. 응답 mapper에서만 Asia/Seoul yyyy-MM-dd HH:mm으로 변환한다. OpenAPI는 string/pattern으로 정의한다.
- 멤버 가입일과 그룹 가입일은 독립적으로 저장한다. HTTP 헤더·실행 로그·AI 원본 시각의 정밀도는 변경하지 않는다.
- 기존 멤버 V2를 수정하지 않고 V3에서 타입을 전환한다. 백업과 범위·zero date·NULL 확인 후 적용하며 잘못된 값을 보정하거나 삭제하지 않는다.
- local repeatable 시드는 최초·체크섬 변경 때만 실행한다. 기본 프로필에는 적용하지 않는다.
- seed 소유 행의 허용 업무 필드만 변경하고 생성 감사 정보·비시드 행을 보존한다. 그룹의 고정 ID/소속 충돌은 중단·보고하며 볼륨 초기화로 해결하지 않는다.
- 실제 그룹 시드와 local fixture는 단일 소속으로 맞춘다. 루트 Compose는 http, 멤버 단독 local은 fixture다.

## 6. 낮은 결합도·트랜잭션·재시도

- Controller → 입력 포트 → QueryHandler → 출력 포트 → 저장소/HTTP 어댑터로 분리한다. 엔티티·원격 DTO·공개 Response를 구분한다.
- application에는 JPA/Spring Data/Servlet/HTTP/RestClient/Jackson/OpenAPI와 구체 인프라 참조를 넣지 않는다.
- 그룹 summary는 REPEATABLE READ 스냅샷에서 projection과 전체 인원 집계를 최대 2쿼리로 수행한다.
- 멤버 DB 읽기 트랜잭션은 저장소 반환 시 종료한다. 원격 조회·재시도 동안 DB 커넥션을 잡지 않는다.
- 기존 연결 2초·읽기 3초, 추가 재시도 기본 1회, capped exponential backoff + Full Jitter를 유지한다.
- GET/HEAD 응답 전 연결 실패·시간 초과만 재시도한다. 4xx/5xx·계약 오류·응답 본문 수신 실패에 재시도를 새로 추가하지 않는다.

## 7. 내부 호출과 로그

- 그룹은 X-Internal-Service-Id + 해당 호출자의 X-Internal-Service-Key를 함께 검사한다. ID 단독·다른 호출자 키·누락/중복은 허용하지 않는다.
- 기존 local 키 파일을 호출자 등록 설정에 연결한다. 키·원문 요청/응답·개인정보는 로그로 전달하지 않으며 내부 키는 브라우저에도 전달하지 않는다. CIDR은 선택이며 실제 remoteAddr로 판단한다.
- 그룹 앱의 호스트 포트는 공개하지 않고 숫자 IP:포트를 통한 서버 간 호출을 유지한다.
- 요청 진입/종료·업무·DB 조회·원격 요청/재시도는 INFO, 예외는 ERROR다. requestId/callId/attempt를 연결하고 MDC를 정리한다.
- 예외 메시지·SQL 값·스택을 계층마다 중복 출력하지 않는다.

## 8. Swagger 그룹 실행 — 추가 확정 요구

- 멤버 Swagger에서 그룹 API의 Try it out / Execute를 활성화한다. 브라우저는 멤버 서비스의 고정 GET 중계 경로만 호출한다.
- 그룹 원본 경로와 응답 스키마는 변경하지 않는다. 전달 명세의 서버 주소를 /internal-api/group으로 바꾸고 내부 주소·보안 스킴을 제거한다.
- 중계는 /internal-api/group/api/groups/summary와 /internal-api/group/api/group/{memberId} 두 조회만 제공한다. 사용자 URL·임의 경로·변경 메서드를 전달하는 범용 프록시는 만들지 않는다.
- 멤버 서버가 호출자 ID·키·추적 정보를 붙인다. 사용자 헤더로 내부 키를 덮어쓰지 못하며 브라우저에 키 입력을 요구하지 않는다.
- 그룹 API 검증 목적이므로 이 중계는 그룹의 정상 JSON과 표준 ProblemDetail 상태/본문을 그대로 돌려준다. 멤버 업무 API의 404→빈 groups 또는 5xx→503 변환과 구분한다.
- 연결 장애는 멤버의 503 GROUP_SERVICE_UNAVAILABLE, 손상된 JSON/비JSON/리다이렉트/비표준 오류 본문은 500 INTERNAL_ERROR다. 내부 응답 헤더는 복사하지 않고 no-store를 적용한다.
- 기술 중계 컨트롤러는 @Hidden으로 멤버 업무 OpenAPI와 분리한다. 그룹 Swagger UI나 그룹 호스트 포트를 별도로 공개하지 않는다.
- 사용자 인증을 두지 않은 현재 범위에서 두 그룹 조회는 멤버 주소를 통한 HTTP 요청으로도 실행 가능하다. Swagger 버튼 자체를 접근 제어로 취급하지 않는다.

## 9. 필수 검증

- 실제 MariaDB의 단일/동시 중복 입력 거부, 다건 방어 예외·로그, 시각 타입·정밀도·범위 실패, 시드 재실행·충돌·감사 정보 보존.
- summary 최대 2쿼리와 두 쿼리 사이 동시 변경의 일관 읽기, 실제 저장소 트랜잭션 경계.
- ArchUnit 계층·서비스 간 참조 검사와 의도적 위반 대조군.
- 멤버 목록/상세 경로·호출 횟수·오류 분류·기존 페이징·정렬·백오프 회귀.
- 제공자 OpenAPI·문서 예제·실제 성공/오류 응답·소비자 계약 검사.
- Swagger 중계의 반복 memberIds, 상세 6필드, 400/404/500/503 보존, 키 서버 주입, 미등록 경로/메서드 거부, 잘못된 응답 비노출.
- 실제 Edge에서 멤버와 그룹 두 API의 입력·Execute·200 응답, 명세 전환, 내부 키/그룹 직접 요청 없음.
- 실제 Compose의 http 연동·그룹 중단·독립 기동·복구, 두 서비스 동시 요청 로그 연결.
- 대역·실제 제공자·IDE/bootRun 미검증 범위를 구분한다. 기능 통과를 AI 로그 제출 준비 완료로 해석하지 않는다.

## 10. 작업 절차와 완료 보고

1. 브랜치/작업 상태·현재 계약을 확인하고 이번 요청을 공통·서비스 문서에 반영한다.
2. DB/enum/Reader, 그룹 조회, 멤버 소비자, 인증/로그, Swagger 중계 순서로 구현하고 단계마다 검증한다.
3. 공유 명세는 생성 도구로 갱신하고 일반 build에서 읽기 전용 비교한다. Google 포맷과 관련 테스트를 통과시킨다.
4. 실제 Compose·Swagger를 검증한 후 [검증 기록](../../verification/GROUP-001/group-query-verification.md)에 명령·결과·한계를 기록한다.
5. 커밋은 [GROUP-001] 제목 + 빈 줄 + 두 칸 들여쓴 본문 규칙과 예약 절차를 따른다.
6. feature → develop 검증, 보완 시 같은 feature 수정·재병합, 정상 시 사용자 feature → master 병합 순서를 유지한다.

완료 보고에는 변경한 API·계약 영향, 실행한 테스트·환경, 문서 경로, 남은 Git·AI 로그 상태를 명시한다.
