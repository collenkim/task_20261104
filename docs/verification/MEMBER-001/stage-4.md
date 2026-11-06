# MEMBER-001 4단계 재시도 검증

| 항목 | 내용 |
| --- | --- |
| 분류 | 검증 기록 |
| 상태 | 이력 · 당시 요청·계획·검증 내용을 보존. 현재 구현 완료 선언이 아님 |
| 현재 기준 | [문서 색인](../../README.md)의 공통·서비스 정의를 따른다 |

| 항목 | 상태 |
| --- | --- |
| 기준 | 2026-11-05, `feature/MEMBER-001`, HEAD `c350e54` + 작업 폴더 |
| 3단계 커밋 | 예약 `a8f79a2714eb` 완료. `c350e541d0309682b5bda34321f6a2b8dc69d277`의 46개 경로·내용이 검증본과 일치 |
| 이번 범위 | M-08 지수 백오프·Full Jitter, 명세 전달의 본문 시간 초과 분류, 관련 문서 |
| 완료 판정 | 4단계 구현·작업 폴더 검증 완료. 전체 빌드 성공, 멤버 118개 실패·오류·건너뜀 0 |
| 커밋 | 예약 `263da9e85203` 완료. `5a0452cd88f2f02d8e5730b56708b6afe37f98b0`의 24개 경로·내용이 검증본과 일치 |

## 구현과 경계

- 기존 마지막 `RetryOnTransientFailureInterceptor`에서만 재시도한다. application·QueryHandler·호출부에는 재시도를 중첩하지 않는다. 소속 HTTP 조회와 기존 명세 전달이 같은 정책을 사용한다.
- 기본 추가 재시도는 1회, base 200ms, cap 2s다. n번째 재시도 상한은 `min(cap, base × 2^(n-1))`, 대기는 0부터 상한까지 정수 밀리초 균등 난수다. 정상 전송 1회, 기본 실패 시 최대 2회다. 전체 API의 고정 최대 소요 시간을 보장하지 않는다.
- `ExponentialRetryBackoff`는 순수 상한 계산, `RetryJitter`·`RetrySleeper`는 교체 가능한 난수·대기 경계다. 기본 구현은 JDK `ThreadLocalRandom`과 `Thread.sleep`이며 추가 의존성은 없다. 알고리즘은 [AWS Full Jitter 설명](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)을 기준으로 했다. JDK 난수의 배타적 상한에 1을 더해 밀리초 상한을 포함한다([JDK 21 문서](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ThreadLocalRandom.html#nextLong(long))).
- 추가 재시도 0~3, 정수 밀리초 지연 1~60000, base <= cap을 기동 시 검증한다. 나노초·소수 밀리초·범위 초과는 절삭하지 않고 거부한다. Duration 변환 전 범위 검사와 cap 도달 시 계산 종료로 overflow를 막는다.
- 횟수는 호출별 지역 변수다. 재사용·동시 요청에서 섞이지 않는다. 대기 인터럽트는 플래그를 복구하고 추가 전송 없이 중단한다. 이미 인터럽트된 스레드는 전송하지 않는다.
- GET/HEAD의 응답 전 연결 실패·시간 초과만 재시도한다. 응답 상태·리다이렉트·계약 오류·변경 요청·본문 수신 실패는 재시도하지 않는다. 난수·대기는 첫 시도 전이나 성공·최종 실패 뒤에 실행하지 않는다.
- 그룹 명세의 전송 바이트 수신과 JSON 해석을 분리했다. 본문 수신 시간 초과는 503 `GROUP_SERVICE_UNAVAILABLE`, 잘못된 JSON·자료형·매체 유형은 500 `INTERNAL_ERROR`다. 기존 JSON 접미사 매체 유형과 브라우저용 내부 주소·호출 설정 제거는 유지한다.
- 재시도 로그에는 방법·경로·예외 종류와 시도 횟수만 남긴다. 쿼리 ID·키·응답 본문·원본 예외 메시지는 넣지 않는다. 오류 응답에도 내부 값이 전파되지 않는다.

정확한 설정·실패 정책은 [서비스 통신](../../common/service-communication.md#pol-comm-003-제한-시간과-재시도)이 소유한다. fixture는 HTTP 재시도를 사용하지 않는다.

## 실행 검사

첫 집중 검사는 테스트 람다 인자의 타입 추론 오류로 컴파일에 실패했다. 테스트 인자를 명시한 뒤 재시도 관련 네 클래스 25개가 통과했고, 당시 전체 빌드도 멤버 115개로 통과했다.

추가로 실제 HTTP 명세 본문을 일부 보낸 뒤 지연시키는 검사를 작성했다. 기대 503에 실제 500을 반환하는 실패를 재현했다. 기존 메시지 변환기가 본문 수신 오류를 감싸던 경계를 분리한 후 `GroupApiDocsTests` 8개가 통과했다. 잘못된 JSON·루트 자료형·매체 유형이 503으로 오분류되지 않는 회귀도 포함한다.

```powershell
.\gradlew.bat :member-service:spotlessApply :member-service:test --tests task.member.ExponentialRetryBackoffTests --tests task.member.RetryOnTransientFailureTests --tests task.member.GroupRetryConfigurationTests --tests task.member.GroupRetryHttpTests --no-daemon --console=plain
.\gradlew.bat :member-service:spotlessApply :member-service:test --tests task.member.GroupApiDocsTests --no-daemon --console=plain
.\gradlew.bat spotlessApply build --no-daemon --console=plain
```

최종 전체 빌드는 성공했다(1분 20초, 41개 작업: 실행 7·최신 상태 34). 멤버 118개가 실제 실행됐으며 실패·오류·건너뜀은 0이다. 그룹 9개는 기존 성공 결과를 재사용했으며 이번 실제 실행으로 세지 않는다.

| 테스트 | 개수 | 검증 내용 |
| --- | ---: | --- |
| ExponentialRetryBackoffTests | 4 | 200/400/800 증가·cap·홀수 상한·큰 번호·설정 정밀도·overflow |
| RetryOnTransientFailureTests | 13 | 재시도 대상·상한·지터 0/중간/최대·대기 순서·본문 경계·인터럽트·동시 요청 |
| GroupRetryConfigurationTests | 3 | 실제 YAML 기본값·마지막 단일 인터셉터·기동 시 설정 경계 거부 |
| GroupRetryHttpTests | 5 | 실제 JDK HTTP 시간 초과·복구·최종 실패·HEAD·매 전송 키 한 개·상태 오류 재시도 없음 |
| GroupApiDocsTests | 8 | 기존 명세 전달 5개 + 본문 시간 초과·JSON/자료형·매체 유형 회귀 3개 |
| 나머지 멤버 검사 | 85 | DB·입력·소속 결합·시드·계층/트랜잭션·공통 pageable·소속 HTTP/fixture 회귀 |

## 4단계 완료 시점의 증거 범위와 후속 작업

- 백오프 단위 검사는 고정 난수·가짜 대기로 순서·범위·횟수를 검증한다. 경과 시간을 측정해 지터 분포를 통과 처리하지 않는다.
- HTTP 검사는 실제 RestClient/JDK 전송과 테스트 `HttpServer`를 사용한다. 서버 지연으로 시간 초과를 만들고, 별도 검사에서 실제 응답 본문 읽기 실패도 확인한다. 그룹 업무 제공자와의 실제 통합 성공은 아니다.
- 기본 pageable 설정과 README AI 기록 영역을 유지한다. 그룹 실행 코드·DB·Compose·비밀 파일·AI 로그 원본/스냅샷은 이번 단계에서 변경하지 않는다.
- 두 운영 jar에서 테스트 지원 코드·Probe·재시도 테스트·ArchUnit이 제외되는지 확인했다. 기본 application.yaml은 재시도 지연 두 항목만 추가됐고 README의 AI 기록 영역은 기준 커밋과 같다.
- M-07 공유 OpenAPI 스냅샷·읽기 전용 비교·응답 스키마 검사, M-11 실제 Compose/Swagger 업무 실행은 다음 단계다. 실제 그룹 업무 API·제공자 명세 적합성은 미검증이다.
- 단계별 예약 실행·SHA 확인은 [계획의 절차](../../tasks/MEMBER-001/plan.md#8-단계와-커밋-절차)를 따른다. API 빌드 성공을 AI 로그 최신화 완료로 간주하지 않는다. 이 단계는 로깅 설정·치환/허용 목록을 바꾸지 않는다.

문서 41개·로컬 링크/앵커 237개·JSON 예제 5개에서 오류 0이며 `git diff --check`도 통과했다. 상세 결과와 검증 대상 파일 해시는 Git 제외 `build/verification/member-001/stage-4.json`에 보관한다.

예약 직전 턴 상태는 Claude `idle (hook_idle)`, Codex `unknown (turn_started_without_hook)`으로 확인했다. 에이전트가 강제로 idle로 바꾸거나 Git 변경을 실행하지 않는다. 사용자는 모든 AI 턴이 끝난 뒤 필요 시 턴 상태를 정리하고 예약을 실행한다([AI 로깅 운영](../../common/ai-logging.md#턴-보호와-예약-작업)).
