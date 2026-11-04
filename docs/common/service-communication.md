# 서비스 간 통신

| 항목 | 내용 |
| --- | --- |
| 목적 | 멤버 → 그룹 호출의 주소·키·제한 시간·실패 처리 |
| 범위 | 유일한 호출 방향은 멤버 → 그룹. 그룹은 다른 서비스를 호출하지 않음 |
| 관련 코드 | 멤버 `infrastructure/client/`, `application/exception/` |

## POL-COMM-001 호출 방식과 방향

- HTTP API와 Spring RestClient(JDK HttpClient)만 사용한다. 다른 서비스 코드·DTO·DB를 직접 참조하지 않는다.
- 빈 이름은 `groupRestClient`다. 업무별 HTTP 인터페이스와 application port는 기능 개발 때 추가한다.
- 현재 실제 연동은 그룹 OpenAPI 명세 조회다. 업무 조회 연동은 아직 없다.

## POL-COMM-002 주소와 기동

| 실행 방식 | 그룹 주소 |
| --- | --- |
| 루트 일괄 Compose | `http://172.30.104.3:8082` (루트 덮어쓰기에서 지정) |
| 멤버 단독 Compose | 기본 `http://127.0.0.1:9` (그룹 없음). 별도 그룹이 있으면 `TASK_GROUP_CLIENT_BASE_URL` 지정 |
| IDE·bootRun local | `http://127.0.0.1:8082` |
| 기본 프로필·별도 배포 | `TASK_GROUP_CLIENT_BASE_URL` 필수 |

- 주소는 http(s)://숫자IP:명시적포트다. 도메인·사용자 정보·쿼리·프래그먼트·추가 경로는 거부한다. IPv4·IPv6를 지원한다.
- 기동 시 그룹을 호출하지 않는다. readiness는 자기 DB만 확인한다.
- 내부 주소·키·그룹 오류 본문을 사용자 오류 응답에 넣지 않는다. 브라우저용 그룹 명세에서도 내부 서버 주소를 제거한다.

## POL-COMM-003 제한 시간과 재시도

| 설정 | 기본값 |
| --- | --- |
| `task.group-client.connect-timeout` | 2초 |
| `task.group-client.read-timeout` | 3초 |
| `task.group-client.max-retries` | 현재 1 (0 또는 1); M-08 목표 기본 1, 허용 0~3 |
| `task.group-client.retry-base-delay` | M-08 목표 200ms (현재 미구현) |
| `task.group-client.retry-max-delay` | M-08 목표 2s (현재 미구현) |

- 양수 제한 시간을 요구한다. 위 시간은 각 시도 기준이며 재시도까지 포함한 전체 3초 제한이 아니다.
- GET/HEAD의 연결 실패·시간 초과만 다시 시도한다. 응답을 받은 4xx·5xx와 변경 요청은 재시도하지 않는다.
- 리다이렉트는 따라가지 않는다. 로그에는 방법·경로·예외 종류만 남긴다.

### 지수 백오프와 Full Jitter (미적용 M-08)

현재 코드는 지연 없이 최대 1회 재시도한다. MEMBER-001에서는 기존 전송 경계의 재시도 구현을 확장하고, 다른 계층에 재시도를 중첩하지 않는다. `RetryOnTransientFailureInterceptor`는 마지막 인터셉터다. 기존 명세 조회의 재시도도 같은 정책으로 회귀 검증한다.

```text
n = 재시도 번호(1부터, 최초 시도는 대기 없음)
upper(n) = min(maxDelay, baseDelay * 2^(n - 1))
delay(n) = UniformInteger(0, upper(n) 밀리초)
최대 전송 시도 수 = 1 + maxRetries
```

- 기본값은 추가 재시도 1회이므로 정상 1회·실패 시 최대 2회 전송, 대기는 0~200ms다. 설정/단위 테스트에서는 재시도 2·3회로 200·400·800ms 상한 증가와 cap을 검증한다. maxRetries 0은 즉시 실패이며 대기하지 않는다.
- baseDelay/maxDelay는 1~60000ms, baseDelay <= maxDelay, maxRetries는 0~3으로 검증한다. 상한 적용 후 안전한 산술을 사용한다. 설정 범위 밖 값은 기동 실패다.
- 요청별로 난수를 추출한다. 지연 계산은 순수 정책, 난수원·대기 기능은 주입 가능한 작은 경계로 분리한다. 테스트는 고정 난수·가짜 대기로 실행하며 실제 sleep 시간에 의존하지 않는다. 알고리즘은 [AWS Full Jitter 설명](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)을 참고한다.
- 대상은 GET/HEAD의 응답 수신 전 연결 실패·시간 초과다. 응답 상태 4xx/5xx, JSON·계약 오류, 변경 요청은 재시도하지 않는다. 응답 본문 수신 중 시간 초과는 503으로 분류하되 이미 응답을 받은 시도의 재시도 범위를 임의로 넓히지 않는다. 읽기 실패 위치별 테스트로 실제 구현 경계를 확인한다.
- 성공·재시도 대상 아닌 실패·마지막 실패 뒤에는 대기하지 않는다. 대기 중 인터럽트는 플래그를 복구하고 즉시 중단하며 추가 요청을 보내지 않는다.
- 연결·읽기 제한은 시도별 설정이다. DB 조회·대기·응답 처리까지 포함한 전체 API의 최대 시간을 `약 10초` 등으로 약속하지 않는다. 재시도 횟수·지연 상한과 실제 측정은 구분해 명세에 기록한다.
- application/QueryHandler는 재시도 설정·난수·sleep을 알지 않는다. 새로운 라이브러리는 필요성과 현재 JDK/스택 호환성을 확인하기 전 추가하지 않는다.

## POL-COMM-004 인증과 실패 처리

- `X-Internal-Service-Key`를 서버 설정값으로 추가한다([보안](security.md#pol-sec-003-내부-호출-키)). 사용자 인증은 없다.
- 명세 조회 연결 실패·시간 초과·그룹 5xx: 503 `GROUP_SERVICE_UNAVAILABLE`.
- 그룹의 401·403, 리다이렉트·잘못된 명세·명세 404: 호출 설정/계약 오류이므로 500 `INTERNAL_ERROR`.
- 그룹 업무 API의 404·업무 오류는 해당 업무 계약에서 매핑한다. 명세 조회의 404 규칙을 일괄 적용하지 않는다.
- 실패를 빈 결과로 숨기지 않는다. 사용자 응답은 공통 ProblemDetail이며 내부 응답·주소·키를 공개하지 않는다.

## POL-COMM-005 계약 소유

그룹 내부 계약은 `services/group-service/docs/api.md`가 소유한다. 멤버는 자기 코드의 모델로 읽는다. 현재 명세 전달 테스트는 실제 HTTP를 사용하지만 업무 조회 계약의 증거는 아니다. 기능 개발 때 그룹 명세와 소비자 테스트로 호환성을 확인한다.

## POL-COMM-006 선행 개발용 공급자 교체 (미적용 M-03·M-04)

- 실제 서비스 간 통신은 계속 HTTP다. local fixture는 다른 서비스 통신을 대체 검증하는 멤버 내부 개발용 어댑터이며 실제 그룹 데이터 연동이 아니다.
- `GroupMembershipReader`가 application 계약을 소유하고 fixture/HTTP 구현이 이를 따른다. 구체 선택·프로필 격리는 [멤버 정책](../../services/member-service/docs/policies.md#pol-member-002-임시-그룹-공급자-미적용-m-04)에 따른다.
- Controller·QueryHandler는 공급자 종류를 모르며 둘의 반환 모델과 의미는 같다. 순수 소속 계약 검증을 재사용한다. 원격 전송 DTO와 공개 DTO는 분리한다.
- HTTP 모드는 키를 서버에서 추가하고 현재 숫자 IP:포트 설정을 재사용한다. 키·쿼리 ID 목록·응답 본문을 로그에 넣지 않는다.
- source 설정만 바꿔 공급자를 교체하고 실제 그룹 구현 후 제공자 계약·통합 실행을 별도로 검증한다. HTTP 실패를 fixture/빈 결과로 대체하지 않는다. 명세 전달은 계속 실제 HTTP를 사용한다.
