# GROUP-001 구현·검증 기록

| 항목 | 내용 |
| --- | --- |
| 분류 | 검증 기록 |
| 상태 | 이력 · 당시 요청·계획·검증 내용을 보존. 현재 구현 완료 선언이 아님 |
| 현재 기준 | [문서 색인](../../README.md)의 공통·서비스 정의를 따른다 |

현재 계약은 [멤버 API](../../../services/member-service/docs/api.md)와 [그룹 API](../../../services/group-service/docs/api.md)가 소유한다. 소속은 멤버당 0~1개다. 당시 Swagger 실행 기준은 마지막의 **Swagger 중계 보완 검증** 절과 [구현 요청서](../../tasks/GROUP-001/request.md)다. 초기 159건 검증과 당시 읽기 전용 화면 결과는 이력으로 보존한다.

| 항목 | 결과 |
| --- | --- |
| 일자·브랜치 | 2026-11-05, feature/GROUP-001 |
| 기준 | master c1da7ce에서 시작, 사용자 확정 단일 소속·그룹 상세 6필드 유지 |
| 코드 상태 | 구현·검증 완료. 커밋·develop/master 병합은 수행하지 않음 |
| 소유 계약 | [그룹 API](../../../services/group-service/docs/api.md), [멤버 API](../../../services/member-service/docs/api.md), [계획](../../tasks/GROUP-001/plan.md) |

## 반영 내용

- 그룹 GET /api/groups/summary와 GET /api/group/{memberId}를 CQRS 입력/출력 포트·Handler·JPA Reader·응답 mapper로 분리했다. 서비스 사이 구현·DTO·enum·DB 참조는 없다.
- 상세는 memberId/groupId/groupName/registeredDate/pricing/permission의 단일 객체다. permission·pricing은 code이며 표시명은 내부 enum에만 있다.
- NOT NULL + UNIQUE(member_id), UNIQUE(group_id,member_id)로 중복 입력을 차단한다. 조회 다건은 명시적 예외와 500 GROUP_MEMBERSHIP_AMBIGUOUS·ERROR 로그다.
- summary의 요청 멤버 목록과 전체 그룹 인원을 분리하고 REPEATABLE READ에서 최대 두 쿼리를 사용한다. 같은 그룹의 서로 다른 멤버는 허용한다.
- 멤버 목록은 summary, 상세는 단건 그룹 API를 각각 한 번 호출한다. 정상 sparse summary만 미소속으로 채우며 단건의 전용 404만 미소속(group null)으로 변환한다. 5xx는 멤버 503, 다른 4xx·계약 오류는 500이다.
- 멤버의 페이징 설정 블록·엄격한 입력·정렬·offset 검증은 유지한다. 소속은 멤버당 최대 1개다. permissionName 삭제와 시각 문자열 변경은 멤버 계약 0.2.0의 소비자 변경 사항이다.
- 기존 멤버 V2를 수정하지 않고 V3로 TIMESTAMP(6)을 적용한다. UTC·마이크로초 보존, 범위 밖 값의 DDL 전 중단을 검증했다. 업무 응답에서만 Asia/Seoul yyyy-MM-dd HH:mm을 사용한다.
- 호출자 ID + 호출자별 키, 선택 CIDR, 기존 local 키 파일 매핑을 적용했다. 사용자 인증은 도입하지 않았다.
- 요청·업무·조회·원격 호출·재시도를 INFO로, 예외를 ERROR로 남긴다. requestId/callId/attempt를 전파하고 실제 식별자·본문·키·예외 메시지는 출력하지 않는다.
- 루트 Compose는 http, 멤버 단독 local은 fixture다. HTTP 실패를 fixture로 대체하지 않는다.

## 자동 검사

~~~powershell
.\gradlew.bat spotlessApply build --no-daemon --console=plain
~~~

최종 BUILD SUCCESSFUL. 테스트 결과 XML을 합산했다.

| 범위 | 건수 | 실패·오류·건너뜀 |
| --- | ---: | --- |
| 멤버 일반 테스트 | 122 | 모두 0 |
| 멤버 OpenAPI 계약 | 8 | 모두 0 |
| 그룹 일반 테스트 | 28 | 모두 0 |
| 그룹 OpenAPI 계약 | 1 | 모두 0 |
| 합계 | 159 | 모두 0 |

검증 경계:

- 실제 MariaDB: V2→V3 값·마이크로초·타입 보존, 범위 밖 기존 값 중단, 그룹 제약·동시 입력 경합, DB 중단 503, 알 수 없는 저장 코드 500, summary 두 쿼리 사이 동시 변경의 일관 읽기.
- local 시드: 기본 경로 격리, 최초·체크섬 무변경·변경 재실행, 감사 정보·비시드 행 보존, 고정 ID·소속 충돌 시 중단. 기존 DB를 초기화하지 않는다.
- 계층: ArchUnit 바이트코드 검사와 의도적 위반 fixture 검출. 멤버 저장소 안 readOnly 트랜잭션과 같은 호출 스레드의 원격 포트 진입 전 트랜잭션 종료.
- HTTP 대역: 반복 memberIds·100명·페이지 1명 경로, 단일 상세와 날짜 해석, 전용 404 구별, 오류 본문 비노출, 지수 백오프+Full Jitter·연결/읽기 실패·본문 수신 실패 분류. 대역 검사는 실제 제공자 증거와 구분한다.
- OpenAPI: 공식 메타스키마, 실제 Controller/DB 성공·오류 응답, 문서 예제, 상세 6필드, 두 헤더 AND 보안 요구, 시간 string/pattern, 결정적 스냅샷 생성·읽기 전용 비교.
- 인증: ID/키 조합·미등록/누락/중복·다른 호출자 키, IPv4/IPv6 CIDR, X-Forwarded-For 우회 거부, 빈 설정의 기동 실패.
- 로그: 다건 예외 ERROR·정제된 오류 정보·키/ID 비노출·MDC 정리. 실제 동시 요청 추적은 아래 실행 검증으로 보완한다.

## 실제 실행

~~~powershell
docker compose up -d --build --wait
python scripts/verification/runtime-smoke.py --independence
python scripts/verification/runtime-smoke.py
node scripts/verification/swagger-browser.mjs
~~~

- 루트 task-local의 멤버·그룹 앱과 MariaDB 2개가 모두 healthy다.
- 실제 목록·상세·미소속·이름 정렬·페이징·입력 오류·시간 문자열·code 응답을 확인했다.
- 실제 두 서비스 OpenAPI와 공유 스냅샷이 일치한다. 브라우저용 그룹 사본에서는 호출 설정 제거와 참고용 설명 변경만 반영된다.
- 그룹 중단 중 멤버 재기동·자기 readiness/명세는 정상, 그룹 필요한 목록/상세와 그룹 명세는 503이다.
- task-group·task-member 단독 Compose 기동 후 루트 통합 모드로 복구했다. 단독 멤버 fixture 조회도 정상이다.
- 동시 요청 4건의 requestId가 두 서비스에 이어지고 callId·attempt가 맞는다. 각 서비스의 진입/종료는 요청별 한 번이며 DB/업무/원격 경계가 연결된다. 로그에 합성 멤버 ID·이메일을 출력하지 않는다.
- 실제 Edge Swagger에서 멤버 목록·상세 Try it out 200, 그룹 두 API 명세 읽기 전용, 멤버 명세 복귀, 브라우저 내부 키 미전달·그룹 직접 요청 없음이 확인됐다.
- 현재 기동은 최신 이미지의 루트 통합 http 모드다.

## 기존 데이터와 백업

두 앱과 DB를 정지한 뒤 기존 task-local 볼륨을 build/backups/group-001/member-before-v3.tar.gz와 group-before-v2.tar.gz에 물리 백업했다. Git 제외 폴더이며 백업 내용·비밀 파일을 출력하지 않았다. 기존 볼륨에 신규 Flyway를 적용했고 볼륨 삭제는 하지 않았다. 신규 검증 DB와 기존 V2 전환은 테스트로 검증했다.

이 백업으로 별도 볼륨을 복원하는 리허설은 수행하지 않았다. 실 데이터 운영 배포의 백업 복원 보장이나 부하 성능 검증으로 해석하지 않는다. 실제 IDE GUI·bootRun을 새로 실행하지는 않았으며 해당 경로는 기존 configtree 설정과 local 호출자 매핑을 확인한 범위다.

## 문서·Git·AI 기록

- 현재 정책·엔티티·API·계약 공유·실행·공통 문서의 기준을 GROUP-001로 맞췄다. 과거 MEMBER-001 검증 결과와 AI 원본 스냅샷은 편집하지 않았다.
- 공유 명세는 [그룹](../../../services/group-service/docs/openapi.json)과 [멤버](../../../services/member-service/docs/openapi.json)가 각각 소유한다. 생성·비교 작업만 공통 빌드 규칙으로 관리한다.
- 커밋·병합·push를 수행하지 않았다. develop에서의 통합 검증과 사용자 feature → master 병합은 후속 단계다.
- AI 로그는 기존 수집기를 통해 동기화한다. 수집 보류와 Codex hook 실행 확인 여부는 기능 테스트 성공과 별개다. 현재 수집기 정책에서는 보류 세션을 경고와 함께 스냅샷에서 제외하고 코드 커밋·병합은 진행할 수 있다. 보류가 해소되기 전에는 전체 로그 제출 완료로 표시하지 않는다. active/unknown 턴 상태는 별도로 Git 변경을 차단한다.

로그 수집 확인: 2026-11-05 02:33:48 UTC 수동 sync 결과 hold, Claude 82건 + Codex 238건 = 320건이다. 기존 상태 표시 221건보다 최신 원본에서 늘었다. 값은 출력하지 않았으며 치환·허용 규칙을 넓히거나 원본을 편집하지 않았다. Codex hook 실행·신뢰는 status에서 확인되지 않았으므로 자동 스냅샷 반영 완료로 표시하지 않는다.


## Swagger 중계 보완 검증 (2026-11-05)

사용자 요청으로 그룹 Swagger의 읽기 전용 제한을 해제했다. /internal-docs/group의 서버 주소는 /internal-api/group이며, 멤버의 고정 GET 경로 두 개가 그룹 원본 API로 전달한다. 그룹 포트·원본 명세·상세 6필드·호출자 인증은 유지한다. 정상 JSON과 표준 ProblemDetail의 상태/본문을 유지하므로 그룹 404/500도 Swagger에서 그대로 확인한다. 멤버 업무 조합 API의 오류 변환과는 별도다.

누락됐던 [docs/common/requests/GROUP-001.md](../../tasks/GROUP-001/request.md)를 생성했다. 범위·계약·단일 소속·시각·멤버 소비자·CQRS·인증·로그·Swagger 중계·테스트·Git 절차를 통합했다. MEMBER-001 요청서는 당시 요구를 보존하고 후속 대체 항목을 연결했다. 공통 정책·실행 안내·서비스 문서·문서 목록의 읽기 전용 규칙도 현재 기준으로 맞췄다.

실행 결과:

- 멤버 전체 check 성공: 포맷·회귀 테스트·공개 OpenAPI 비교 통과.
- 특수문자 전달 보완 후 집중 재검증: GroupApiDocsTests 14건, MemberOpenApiContractTests 8건 모두 실패·건너뜀 0.
- 반복 memberIds·특수문자 인코딩·상세 응답·그룹 400/403/404/500/503 보존·서버 키/ID 주입·내부 헤더 비전달·고정 경로/GET 제한·리다이렉트/잘못된 JSON/1MiB 초과 응답·시간 초과 재시도를 검사했다.
- 최종 docker compose up -d --build --wait 성공. 실제 runtime-smoke.py에서 그룹 중계 200·400·404와 명세·기존 멤버 조회·추적 로그를 확인했다.
- 최종 Edge swagger-browser.mjs에서 멤버와 그룹의 목록·상세를 실제 Try it out / Execute로 실행해 모두 200을 확인했다. 그룹 요약에는 요청 멤버만 있고 totalMemberCount는 전체 인원 2, 상세는 기존 6필드와 시간/권한 코드다.
- 브라우저의 그룹 직접 요청·내부 키 헤더는 없고, 명세 선택 후 멤버 화면 복귀도 통과했다.
- 문서 링크·앵커와 git diff --check 오류 없음. 이 보완에서는 DB 마이그레이션·데이터 초기화·커밋·병합을 수행하지 않았다.

재실행 명령:

~~~powershell
.\gradlew.bat :member-service:check --no-daemon --console=plain
.\gradlew.bat :member-service:test --tests task.member.GroupApiDocsTests :member-service:checkOpenApiSnapshot --no-daemon --console=plain
docker compose up -d --build --wait
python scripts/verification/runtime-smoke.py
node scripts/verification/swagger-browser.mjs
~~~

## 커밋 전 최종 확인 (2026-11-05 12:38 KST)

- 최종 코드에서 `./gradlew.bat build --no-daemon --console=plain`이 성공했다(1분 37초). 포맷 검사와 두 서비스 OpenAPI 스냅샷 비교를 통과했다.
- XML 결과 합계는 멤버 일반 128건·계약 8건, 그룹 일반 28건·계약 1건으로 총 165건이며 실패·오류·건너뜀은 모두 0이다. 변경 없는 일부 Gradle 작업은 UP-TO-DATE로 기존 성공 결과를 재사용했다.
- 현재 `feature/GROUP-001`에서 그룹 조회·멤버 연동, Swagger 실행 중계, 문서 정리를 구분해 커밋한다. 실제 커밋은 모든 AI 턴이 끝난 뒤 예약 작업으로 실행한다.
- develop 병합 후 빌드·실제 Compose·Swagger 재검증은 별도 단계다. 이 절의 성공 결과를 develop 검증 완료로 간주하지 않는다. master 병합과 원격 push도 아직 수행하지 않았다.

## develop 병합 후 개행 보완 (2026-11-05)

- 최초 develop 병합 bdcf06e 이후 전체 재실행에서 테스트 165건은 통과했으나 그룹 OpenAPI 바이트 비교가 실패했다.
- Windows core.autocrlf=true와 그룹 명세의 LF 속성 누락으로 CRLF 302개가 생겼다. 생성 결과와 JSON 내용 및 LF 정규화 결과는 동일했다.
- feature/GROUP-001에서 공유 OpenAPI의 LF 속성을 모든 서비스에 적용하고 개행만 복구했다. API 계약은 변경하지 않았다.
- 보완 후 feature 전체 build를 통과한 뒤 커밋한다. develop 재병합 후 전체 빌드·Compose·Swagger 검증은 인계 스크립트의 후속 단계다.

## master 반영 전 AI 로그 검토 (2026-11-05)

- 최초 최신 수집 보류 331건을 검토했다. 같은 값의 반복을 묶고 기존 검증 구간까지 포함하면 51종이다.
- 일반 이메일 7종은 로컬 치환 목록에 등록했다. 원본은 유지하고 제출 사본에서만 마스킹한다.
- 코드 타입·수신자 표현식·테스트 예시·설정 참조·공개 체크섬·문서 및 클래스 경로 오탐은 사유를 적어 정확한 값만 허용한다. 넓은 도메인·키 이름 면제는 추가하지 않았다.
- 진단 출력의 대체 표식도 정확한 값으로만 허용했다. 검토하지 않은 값과 변형된 문자열은 계속 보류한다.
- YDB 공개 PDF 문서 식별자는 사용자가 마스킹 불필요로 확인했다. 해당 값만 정확히 허용하며, 다른 식별자는 계속 보류한다.
- 기존 정책 검사 15건·추가 회귀 검사 7건과 미검토 값 차단 대조군을 통과했다. 정책 반영 뒤 수집·최종 live 검증·수집기 전체 테스트·Gradle build를 재실행했다.
- 기존 커밋 스냅샷 검사에서 새 마스킹 대상이나 검증 위반은 발견되지 않았다. master 병합은 사용자 실행 단계다.
