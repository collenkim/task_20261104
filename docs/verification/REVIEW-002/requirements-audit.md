# REVIEW-002 전체 요구 재검증 — 3차

| 항목 | 내용 |
| --- | --- |
| 분류 | 검증 기록 |
| 상태 | 코드·문서·계약·실제 Compose/Swagger 검증 완료. AI 최종 제출은 보류 |
| 확인일 | 2026-11-06, Asia/Seoul |
| 대상 | feature/REVIEW-002, f1c17f27dacaac25d972f731a2fa1d8715d55f89 이후 작업 폴더 |
| 기준 | [3차 승인](../../tasks/REVIEW-002/approved-contract.md#3차-승인-공통-미디어-타입과-페이지-번호), [공통 API](../../common/api-conventions.md), [멤버 API](../../../services/member-service/docs/api.md), [그룹 API](../../../services/group-service/docs/api.md) |

이 문서는 3차 시점의 검증 기록이다. 그룹 totalMemberCount의 전체 인원 의미는 후속 [매핑 기준 집계 수정](group-summary-fix.md)으로 대체되었다. 현재는 요청 범위에서 실제 반환된 members 수이며, 매핑 없는 ID는 제외한다.

## 1. 이번 변경과 기존 구현의 구분

`page` 객체 제거 요청은 마지막 사용자 정정에 따라 제외했다. 멤버 목록은 계속 `members`와 `page`를 반환한다. Spring PageImpl의 부수 필드가 노출되는 형태가 아니라 명시적인 네 필드의 페이지 DTO다.

| 구분 | 착수 시 f1c17f2 | 이번 결과 |
| --- | --- | --- |
| 페이지 번호 | 요청 기본 0, 응답 number 0 | 요청 기본·최소 1, 응답 number 1 시작. 저장소 내부만 0 기반 |
| page 객체 | size/number/totalElements/totalPages | 그대로 유지. 빈 목록에서도 요청한 페이지 번호를 유지 |
| 입력 오류 | 형식 400, 의미 422 | page=0도 422. 공백·음수·범위 초과·반복은 400 유지 |
| offset | page × 적용 size | (page - 1) × 적용 size. Long 계산 후 2147483647 이하 검증 |
| 오류 미디어 타입 | application/problem+json | application/json. MVC·보안/메서드/요청 제한 필터·그룹 중계 모두 통일 |
| 상태 확인 | Actuator 전용 JSON 미디어 타입 | application/json으로 통일. HTML·JavaScript 자원은 본래 형식 유지 |
| 오류 본문 | type/title/status/detail/instance/code | 여섯 필드 유지. 상태별 공통 code, 원인별 type |
| 서비스별 문서 | 개별 발생 오류 설명 | 동일한 14개 상태·code 표와 API별 실제 발생 범위를 함께 표시 |
| OpenAPI | 멤버 0.4.0 / 그룹 0.2.0 | 멤버 0.5.0 / 그룹 0.3.0. 검증 후 생성 태스크로 갱신 |
| 서비스 간 공통 검사 | 서비스별 계약 검사 | 루트 api-contract-check.py 추가. 코드·정책·각 문서·OpenAPI 대조 |
| 스냅샷 갱신 빌드 | 갱신과 build 동시 요청 시 Gradle 의존 검증 실패 | 출력 범위를 openapi.json 한 파일로 한정하고 갱신 후 검사 순서 선언. 일반 build는 계속 읽기 전용 |

단일 group·감사 필드 제외·JWT·GET 전용·중복 ID 제거·필드 선별 중계는 착수 시 이미 구현되어 있었다. 이번에 새로 구현했다고 계산하지 않는다. 응답 필드 변경이 문서에만 있었던 문제인지도 [착수 기준](baseline.md)과 기존 코드 이력으로 구별했다. 현재 실제 DTO와 계약 테스트는 단일 group이며 joinedAt/createdAt/createdBy/permissionName이 공개 응답에 없다.

## 2. 요구와 근거 대응

‘충족’은 아래 코드·검사 범위에서 확인한 판정이다. 실제 물리 서버 배포나 운영 부하 성능까지 대신 증명하지 않는다.

| 번호 | 요구 | 판정·근거 |
| --- | --- | --- |
| R-01 | 두 서비스 독립 실행·Host 분리 | 별도 Spring Boot 앱·이미지·컨테이너·IP. 루트 내부 주소는 멤버 172.30.104.2:8081, 그룹 172.30.104.3:8082. 단독 Compose도 별도 소유. **서로 다른 물리 장비 배포는 미검증** |
| R-02 | 사용자 → 멤버 → 그룹 | 멤버 공개 GET 두 개. application 출력 포트 GroupMembershipReader의 HTTP 구현에서 그룹 API 호출. 역방향 그룹 → 멤버 호출 없음 |
| R-03 | 그룹 외부 라우팅 차단 | 그룹 Compose ports 없음, 내부 앱 네트워크, 내부 호출자별 키 검사. 멤버의 local Swagger 중계는 승인된 간접 접근 예외 |
| R-04 | 서비스 간 코드·엔티티 직접 참조 금지 | 두 ArchUnit 검사, 별도 Gradle 프로젝트·런타임 의존. 공유 런타임 DTO 모듈 없음. 루트 계약 대조는 검증 도구일 뿐 앱 의존이 아님 |
| R-05 | 별도 DB·스키마·관리 책임 | member-db/group-db 컨테이너·볼륨·전용 계정·DB 네트워크, 서비스별 Flyway. 그룹 DB에 멤버 테이블을 참조하는 FK 없음 |
| R-06 | 멤버 단일 그룹·그룹 여러 멤버 | group_membership의 UNIQUE(member_id), group_id FK. 동시 중복 입력 거부 테스트. 다건 조회도 임의 선택하지 않고 500 및 ERROR |
| R-07 | 과금·권한 코드 | GroupPricingType Free/Advanced/Premium, GroupPermissionType Viewer/Editor/Admin. 각각 code/status/codeName/description. 응답에는 code만 반환 |
| R-08 | 멤버 목록 | GET /api/members. DB 페이지 → 그룹 summary 일괄 호출 → ID 결합. 목록은 id/name/group/permission. 빈 페이지는 원격 호출 없음 |
| R-09 | 이름 정렬·페이징 | name,asc 또는 name,desc만 허용, 동명이인 ID ASC. 기본 page=1,size=20, 최대 size=100. 요청/응답/문서/OpenAPI 일치 검사 |
| R-10 | 멤버 상세 | GET /api/member/{memberId}. id/name/email/address/registeredDate/group만 응답. 미존재 404, 미소속 group=null, 장애를 미소속으로 숨기지 않음 |
| R-11 | 목록·상세 결합과 두 가입일 | 멤버 registeredDate는 멤버 가입 시각, group.registeredDate는 그룹 소속 시각. 별도 값의 조합·변환 테스트. 감사 필드는 DB·내부 모델에만 유지 |
| R-12 | 호출 영향 최소화 | 출력 포트·별도 HTTP DTO/decoder·설정 주입·타임아웃·제한된 재시도. HTTP 진입 때 DB 트랜잭션 비활성 테스트. 그룹 장애 때도 멤버 기동/health/자체 명세는 유지. **그룹 정보가 필요한 API는 503이므로 장애 영향이 전혀 없다는 뜻은 아님** |
| R-13 | 그룹 summary 계약 | GET /api/groups/summary. memberIds 반복 파라미터 1~100개, 형식 검사 → 원래 입력 개수 검사 → 순서 유지 중복 제거. 요청 멤버만 members에 포함하고 totalMemberCount는 그룹 전체 인원 |
| R-14 | 중복 ID 로그 | 중복을 400으로 거부하지 않고 제거 후 200. 승인된 WARN duplicateIds 기록. 반복 page/size/sort 거부 정책과 구별 |
| R-15 | 그룹 상세 계약·미소속 | GET /api/group/{memberId}. memberId/groupId/groupName/registeredDate/pricing/permission 여섯 필드. 잘못된 ID 400, 미소속 404, 다건 500 |
| R-16 | 정의된 응답만 노출 | 명시적 DTO, OpenAPI additionalProperties=false, 필수/null/타입 검사. 멤버 중계는 모든 깊이에서 허용 필드만 투영. 추가 필드는 제거, JSON/계약/미디어/크기 위반은 원인별 type의 502 |
| R-17 | 공통 오류·상태 코드 | 두 ErrorCode의 14개 상태→code 대응과 각 api.md·공통 정책·OpenAPI를 루트 검사로 대조. 발생하지 않는 409/415 등은 예약으로 표시하며 실제 API 응답으로 허위 선언하지 않음 |
| R-18 | application/json 통일 | 성공·오류·보안 필터·중계·health 검사. 오류의 기존 여섯 필드는 유지. Swagger HTML/JS를 JSON으로 바꾸지 않음 |
| R-19 | CQRS·객체 지향·낮은 결합도 | presentation → 입력 포트 → QueryHandler → 출력 포트. application은 JPA/HTTP/Servlet/Jackson/전송 DTO/트랜잭션을 참조하지 않음. ArchUnit이 의도적인 금지 참조도 검출 |
| R-20 | 테스트 패키지·성공/실패 | application/query, presentation/query·error, infrastructure/client·configuration·persistence·web, architecture, contract, support로 분리. 실제 MariaDB·HTTP 합성 서버·실제 Compose 검사를 구별 |
| R-21 | 확장·시그니처 공유 | 버전·operationId·스키마를 고정한 서비스별 OpenAPI 공유. 다른 서비스는 내부 키의 호출자 설정으로 추가 가능. 언어별 SDK·외부 라우터 배포·IdP 구축은 미구현이며 완성으로 표기하지 않음 |
| R-22 | Swagger JWT·테스트 토큰 | 멤버 RS256 Resource Server, scope별 접근. local에서 실행 중 30분 JWT 발급·자동 주입, 수동 Authorize 가능. 단위/통합 테스트도 실행 중 키·토큰 생성. 완성된 고정 비밀값 저장 방식은 사용하지 않음 |
| R-23 | secrets 없는 최초 실행 | generateLocalSecrets → Compose 기동 → ps 확인. 기존 키 유지. 최초 실행은 [독립 검증](fresh-start.md), 이번은 기존 키 보존·재기동 검증. 기존 DB 비밀번호를 잃은 경우는 별도 복구이며 자동 초기화하지 않음 |
| R-24 | GET만 허용 | 두 서비스의 인증보다 앞선 GetOnlyMethodFilter. HEAD/OPTIONS 포함 405 + Allow: GET, 조회·원격 호출 미실행 검사 |
| R-25 | URI·SQL 입력 안전성 | RFC 3986에 맞게 값 단위 URI 인코딩, ID 패턴·정렬 허용 목록, JPQL 바인딩 파라미터. 임의 경로·SQL·원격 URL을 조립하지 않음. 보안 침투 시험 전체를 수행했다는 뜻은 아님 |
| R-26 | 시각 저장과 표시 | 현재 최종 DB 타입 TIMESTAMP(6), JVM Instant·UTC 저장, 응답만 Asia/Seoul yyyy-MM-dd HH:mm. 멤버 V2의 과거 DATETIME 정의는 V3가 변환하므로 V2만 보고 현행 오류로 판정하지 않음 |
| R-27 | 로그 추적 | 요청 진입/종료, 업무·DB 조회, 원격 호출/재시도·예외에서 request/call/attempt 연계. 일반 흐름 INFO, 처리 예외 ERROR, 중복 입력 WARN. 토큰·키·응답 원문을 기록하지 않음 |
| R-28 | 문서 분류·README | docs/common, docs/tasks/<ID>, docs/verification/<ID>, 서비스 docs. README에 목적·구조·스펙·최소 설치·실행/종료 순서·ps·Swagger·AI 요약. 운영 상세는 별도 문서 |
| R-29 | 전체 문서·MEMBER-001 | 링크/앵커/정책 검사와 현행 계약 수동 대조. 아래 과거 요청의 효력 설명 보완. 옛 검증 수치·원본 로그를 새 결과로 덮어쓰지 않음 |
| R-30 | AI 로그 누락·브랜치 보존 | 기존 master/develop/feature의 스냅샷 파일 60개 동일. 다만 최근 세션 보류·Codex hook 미확인으로 **최종 제출 요건은 미충족**. 자세한 범위는 5절 |

## 3. 테스트와 실행 증거

| 검사 | 결과 |
| --- | --- |
| spotlessApply build | 성공, 1분 53초. 멤버 214개·그룹 69개, 실패/오류/건너뜀 0. 이번 변경으로 두 서비스 일반·계약 테스트 재실행 |
| 갱신 태스크와 build 동시 실행 | 순서 보완 후 성공. 일반 build에서도 공유 스냅샷 일치 |
| 공통 API 검사 | 두 서비스·정책·API 문서·공유 명세 대조 오류 0 |
| 공통 API 검사기 회귀 | 7개 성공. page=0, page 제거, 미디어 타입 변경, 오류 추가 필드, 서비스 간 code 변경, 문서 code 변경을 실제 검출 |
| 문서 검사기 | 최종 문서 검사 결과는 [검증 기록](verification.md#3차-검증-2026-11-06)을 따른다 |
| 실제 Compose·독립 실행 | runtime-smoke.py --independence 성공. JSON·페이지·인증·중복 ID·네트워크·그룹 중단·개별 앱/DB 기동·통합 복구·추적 검증. 종료 후 4개 컨테이너 healthy |
| 실제 Edge Swagger | swagger-browser.mjs 성공. 네 API Try it out 200, Bearer 전송, 1 기반 페이지·단일 소속·내부 키 비노출 |

기존 검사도 다음 의미를 유지한다. 정상·무소속·빈 목록, 반복/공백/범위/정렬 오류, 인증·권한, DB 오류, 원격 연결/시간 초과/5xx/손상 JSON/크기 초과, 다건 방어, UTC 저장·KST 표시, local 시드 재실행, 트랜잭션 경계, 백오프+Full Jitter, OpenAPI 예제·실제 JSON·추가 필드 거부다. MockMvc만 통과한 것을 실제 브라우저 검증이라고 하지 않는다.

이번에 검출하고 수정한 실패는 (1) 문서의 page.number=0, (2) 스냅샷 갱신 Copy 태스크의 전체 docs 출력 범위·검사 순서, (3) 실제 health 응답의 Actuator 전용 미디어 타입이다. 모두 원인을 보완하고 해당 검사를 다시 수행한다.

## 4. 문서 검토와 남은 운용 범위

- 기술 스펙의 ‘JWT 의존성 제거’, 계약 공유 문서의 ‘페이지 유지’, 개정안의 2차/3차 버전 혼동을 바로잡았다. current 문서의 예제·설명·OpenAPI는 1 기반 페이지를 사용한다. page=0 문자열이 남은 것은 오류 예제나 이전 소비자 전환 안내다.
- MEMBER-001 요청은 이력이다. 당시 감사 필드·RFC 3339·임시 /internal/group-memberships·fixture 지시를 현재 구현으로 적용하면 안 된다. 상단에서 후속 변경으로 대체되었음을 명시하고 현행 서비스 API로 연결했다. 현행 응답을 뜻하는 groups[]·permissionName 예제는 없다. 금지 설명이나 과거 변경 설명의 단어까지 삭제하지 않는다.
- 현재 스냅샷 버전·추가 필드 금지·상태표는 자동 검사한다. 자연어의 의미, 외부 링크 접속, 모든 운영 설정 조합은 자동 검사 범위 밖이다.
- 그룹 중계는 **설정 스위치**로 등록된다. 기본 false, local에서 true다. @Profile로 local 외 활성화를 강제 금지하는 구현은 아니다. 운영에서 스위치를 켜면 JWT group.read를 가진 요청이 중계에 접근하므로 운영 설정은 false로 유지해야 한다. local 자동 토큰은 개발 PC 접근자를 위한 편의 기능이며 운영 사용자 보호 증거로 보지 않는다.
- API별 HTTP 경로는 유지했다. RFC 3986은 URI 문법 기준이며 복수형 경로·REST 설계 전체 적합성을 인증하는 규격은 아니다.

## 5. AI 로그의 보존과 제출 누락 판정

읽기 전용 Git tree의 경로·파일 모드·blob ID를 비교했다. master aaf0005, develop 78e57d8, feature HEAD f1c17f2 모두 **파일 60개, manifest 12개**다. develop에만 있거나 master와 내용이 다른 파일은 0개다. 파일 60개를 세션 60개 또는 스냅샷 60개라고 세지 않는다.

현재 master에서 세션별 가장 큰 전체본의 manifest 범위(UTC):

| 도구 | 최초 timestamp | 마지막 timestamp | 주 JSONL 줄 수 | 원본 총 바이트(부속 포함) |
| --- | --- | --- | --- | --- |
| Claude 29f7d66a… | 2026-11-03T23:04:29.655Z | 2026-11-04T23:28:07.806Z | 6809 | 25766741 |
| Codex 01a1a3aa… | 2026-11-03T23:18:53.931Z | 2026-11-05T07:51:46.214Z | 7220 | 39984712 |

SETUP-001·MEMBER-001의 master 반영 직전 전용 스냅샷 커밋은 없고 뒤의 GROUP-001·REVIEW-001에서 전체본이 추가됐다. 따라서 ‘해당 단계 로그 커밋 없음’과 ‘세션 내용이 영구 소실됨’은 다르다. 이번에는 전체 원본과 모든 과거 스냅샷을 다시 마스킹·바이트 대조한 것이 아니므로 당시 모든 프롬프트가 완전히 제출됐다고 단정하지 않는다.

상태 명령이 보고한 마지막 수집 시각은 2026-11-06T00:53:07.403Z다. Claude 두 세션의 보류 합계는 124건(11+113), Codex 마지막 수집본은 7960줄/43545492바이트·마지막 시각 2026-11-05T23:15:26.116Z다. 이것도 커밋된 master 전체본과는 다르며 현재 응답의 꼬리가 제출된 증거가 아니다. Claude c8b4102f… 세션은 master manifest에 없다.

남은 보류는 사용자 판정이 필요한 치환·허용 후보와 original_rewritten 메타데이터 3건이다. Codex trusted_hash 기록이 없고 실제 hook 실행도 확인되지 않았다. 원본·스냅샷·치환 목록을 임의로 수정하거나 후보를 일괄 허용하지 않았다. turns resolve는 턴 상태 복구이지 비밀 후보 보류 해소 명령이 아니다.

최종 제출에는 보류 처리와 hook 확인 후 모든 턴 종료 상태에서 sync·스냅샷 제출·원본 꼬리 대조가 필요하다. 이번 코드 검증 통과는 이 제출 보류를 해제하지 않는다. AI 턴 중 커밋·병합·push는 수행하지 않았다.

## 6. 성능 검토

자세한 판단은 [성능 기록](performance.md)을 따른다. 현재 가장 먼저 측정할 후보는 원격 응답 대기/재시도 중 요청 스레드 점유, 큰 offset·count·이름 DESC/ID ASC 혼합 정렬, 큰 그룹의 전체 인원 집계다. JWT RSA 검증과 subject별 요청 제한도 부하에 포함한다.

원격 본문 크기 상한과 조회 트랜잭션 종료는 적용됐으나 회로 차단·동시 호출 제한·캐시·키셋 페이징은 적용하지 않았다. 요청 제한 map의 max-clients는 모두 활성인 경우 초과할 수 있어 엄격한 메모리 상한이 아니다. p50/p95·처리량·대규모 EXPLAIN·분산 제한은 미측정이다. 실제 기능 실행의 성공을 성능 개선 수치로 제시하지 않는다.

## 7. 참고한 규격

- [Spring MVC 오류 응답](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html): ProblemDetail의 기본 처리와 프로젝트 미디어 타입 재정의 경계를 확인했다.
- [Spring Boot EndpointMediaTypes](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/actuate/endpoint/web/EndpointMediaTypes.html): health의 기본 응답 미디어 타입을 application/json으로 명시했다.
- [RFC 3986](https://www.rfc-editor.org/rfc/rfc3986.html): URI 값 인코딩과 경로·쿼리 구분 기준으로 확인했다.
