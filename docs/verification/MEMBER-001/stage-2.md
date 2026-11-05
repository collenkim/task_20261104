# MEMBER-001 2단계 소속 공급자 검증

| 항목 | 내용 |
| --- | --- |
| 분류 | 검증 기록 |
| 상태 | 이력 · 당시 요청·계획·검증 내용을 보존. 현재 구현 완료 선언이 아님 |
| 현재 기준 | [문서 색인](../../README.md)의 공통·서비스 정의를 따른다 |

| 항목 | 내용 |
| --- | --- |
| 검증일 | 2026-11-05 KST |
| 브랜치·기준 | `feature/MEMBER-001`, `ef03e16` 위 작업 폴더 |
| 범위 | M-03 HTTP 소비자 어댑터, M-04 local fixture·공급자 선택·계약 검사 |
| 당시 상태 | 구현·검증 완료, 커밋 `e5a8c6b` 확인. 검증본과 35개 경로·내용 일치 |
| 기준 | [통합 계획](../../tasks/MEMBER-001/plan.md), [그룹 계약](../../../services/group-service/docs/api.md), [멤버 정책](../../../services/member-service/docs/policies.md) |

## 이전 커밋 확인

- 문서 커밋 `478026f`: 19개 경로와 검증본 일치.
- 저장소 커밋 `ef03e16`: 12개 경로와 검증본 일치.
- 당시 검증 파일의 SHA-256을 확인하고 커밋 blob과 개행 정규화 비교했다. 0·1단계 예약 작업은 모두 done이며 2단계 시작 시 작업 폴더가 깨끗했다.
- 1단계의 문서 현황 갱신 대기를 해소하여 멤버 테이블·저장소가 미구현이라고 표시되던 현재 상태 문서를 갱신했다.

## 적용 내용

- application의 `GroupMembershipReader`와 `GroupMembership`은 JPA·HTTP·Jackson·공개 DTO에 의존하지 않는다. ID 1~100개의 소속을 일괄 조회하며 반환 값은 각 ID의 0~N개 소속이다.
- HTTP와 fixture는 동일한 JSON 디코더·전송 DTO·순수 계약 검사를 사용한다. 필수 필드의 누락·null·잘못된 타입·ID 중복·동일 멤버의 그룹 중복을 거부한다. 숫자나 불리언을 문자열로 강제 변환하지 않는다.
- 다중 소속, 서로 다른 멤버의 같은 그룹, 새로운 권한/과금 코드와 추가 필드는 허용한다. 선택 필드 부재는 null, 공백뿐인 과금 코드는 null로 정규화한다. 선택 시각도 UTC·실제 날짜·최대 마이크로초 정밀도를 검사한다.
- HTTP는 기존 groupRestClient의 숫자 IP·포트·서버 내부 키·재시도를 사용한다. GET 반복 memberIds, 본문 없음, 응답 200 application/json을 검증한다. 요청과 응답 ID 집합은 정확히 일치해야 한다.
- 연결·시간 초과·5xx는 GroupServiceUnavailableException, 4xx·리다이렉트·본문/계약 오류는 GroupServiceConfigurationException이다. 기존 오류 처리기의 503/500 매핑을 재사용한다. 예외에 원문·주소·키·원인 예외를 담지 않는다.
- fixture 파일은 계획된 다섯 멤버 ID를 모두 포함한다. 명시적 미소속과 데이터 누락을 구분하며, 없는 ID를 빈 소속으로 추정하지 않는다. 반환된 소속 목록으로 저장된 임시 데이터를 변경할 수 없다.
- `task.group-membership.source`는 기본 http, local fixture다. 알 수 없는 값·local 외 fixture·중복 공급자는 기동 실패다. HTTP 장애 후 fixture로 바꾸지 않는다.
- 루트·멤버 단독 Compose에 TASK_GROUP_MEMBERSHIP_SOURCE 전달을 연결했다. 기존 실제 그룹 명세 프록시는 변경하지 않았다.

## 실행과 결과

```powershell
.\gradlew.bat spotlessApply --no-daemon --console=plain
.\gradlew.bat build --no-daemon --console=plain
```

초기 검사에서 Jackson 3의 deepCopy 시그니처 차이로 테스트 컴파일이 실패했다. Jackson 3 API와 문자열 타입 검사에 맞춰 수정한 후 최종 전체 빌드가 통과했다. 실패를 제외한 최종 결과만 최초 시도 성공처럼 기록하지 않는다.

| 검사 | 최종 결과 |
| --- | --- |
| 전체 빌드 | 성공. 35개 작업 중 7개 실행, 28개 UP-TO-DATE |
| 멤버 테스트 | 67개 실제 실행. 실패·오류·건너뜀 0 |
| 이번 추가 | 디코더 9, fixture 4, 실제 HTTP 10, 공급자 설정 6. 총 29개 |
| 기존 회귀 | 멤버 38개 재실행·성공. JPA·페이지·명세 전달·기존 재시도 포함 |
| 그룹 테스트 | 9개 기존 성공 결과 재사용. 이번 실제 재실행으로 세지 않음 |
| 프로필 설정 | 실제 application/application-local 설정으로 기본 HTTP·local fixture·환경변수 HTTP 전환 확인 |
| 기동 거부 | local 외 fixture, 알 수 없는/빈 source, 공급자 중복 |
| HTTP 요청 | 내부 키 한 개, ID 반복 파라미터, GET 본문 없음, 정상 일괄 1회, 100개 경계 |
| 계약 실패 | ID 누락/추가/중복, 필수 필드 부재·타입·중복 JSON 키·후행 JSON·잘못된 날짜/정밀도 거부 |
| 확장 허용 | 다중 소속·새 코드·알 수 없는 필드·선택 메타데이터 부재 |
| 전송 실패 | 연결 실패·응답 전 시간 초과는 이용 불가, 응답 전 시간 초과 기본 최대 2회 전송 |
| 본문 시간 초과 | 헤더·본문 일부 수신 후 지연 → 이용 불가, 실제 전송 1회 |
| 상태 실패 | 4xx·3xx·계약 오류는 설정 오류, 5xx는 이용 불가. 재시도·리다이렉트 추적·fixture 대체 없음 |
| 공급자 교체 | 같은 포트에 fixture/HTTP를 연결해 반환 모델·소속 의미 일치 |
| Compose 해석 | 루트/단독 각각 기본 fixture·환경변수 http 전달과 local 프로필 확인. 컨테이너 신규 기동 검증은 아님 |
| 문서·산출물 | 문서 38개, 로컬 링크·앵커 207개, JSON 예제 5개 오류 0. 운영 jar의 fixture 포함·테스트 클래스 제외 확인 |
| 기존 설정 보존 | pageable 블록과 README의 AI 기록 영역 변경 없음 |

HTTP 검사는 저장소 POL-TEST-004에 따라 JDK HttpServer로 수행했다. 클라이언트 함수를 흉내 낸 것이 아니라 실제 JDK HTTP 전송·헤더·본문·시간 초과를 실행했다. 실제 그룹 서비스 제공자와의 통신은 아니다. RestClient.exchange의 상태 처리는 [Spring REST 클라이언트 문서](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html)를 참고해 콜백에서 명시적으로 수행한다.

JUnit 결과는 `services/member-service/build/test-results/test/`, 요약은 Git 제외 `build/verification/member-001/stage-2-tests.json`에 있다. 새 라이브러리는 추가하지 않았다.

## 2단계 완료 시점의 후속 범위

- 멤버 공개 목록/상세 API, 입력 DTO·입력 포트·QueryHandler, local 멤버 시드는 3단계 대상이다. 현재 fixture 파일만으로 DB 멤버가 생성되거나 공개 API가 제공되지는 않는다.
- M-04 공급자·설정 검증은 완료했지만 공개 API의 실제 200 응답은 M-05·M-11과 함께 확인한다.
- 동일 QueryHandler에 두 공급자를 연결하는 유스케이스 검사·그룹 포트 진입 시 트랜잭션 검사와 정적 계층 검사는 후속이다. 이번 반환 모델 비교를 그 검사의 대체로 보지 않는다.
- 기존 즉시 재시도 정책은 유지했다. 지수 백오프·Full Jitter는 4단계 M-08 대상이다.
- OpenAPI 스냅샷, 실제 Compose/Swagger 업무 실행, 실제 그룹 API·제공자 명세 적합성은 아직 검증하지 않았다.
- 예약 `7e0a650739b3`의 실행 완료를 확인했다. `e5a8c6b3afec5160b8184536bdb40f5137368954`의 35개 경로와 검증 당시 내용이 개행 정규화 비교로 일치한다. develop/master 병합·push는 수행하지 않았다. 후속 공개 조회·시드는 [3단계 기록](stage-3.md)을 따른다.
