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
| `task.group-client.max-retries` | 1 (0 또는 1) |

- 양수 제한 시간을 요구한다. 위 시간은 각 시도 기준이며 재시도까지 포함한 전체 3초 제한이 아니다.
- GET/HEAD의 연결 실패·시간 초과만 다시 시도한다. 응답을 받은 4xx·5xx와 변경 요청은 재시도하지 않는다.
- 리다이렉트는 따라가지 않는다. 로그에는 방법·경로·예외 종류만 남긴다.

## POL-COMM-004 인증과 실패 처리

- `X-Internal-Service-Key`를 서버 설정값으로 추가한다([보안](security.md#pol-sec-003-내부-호출-키)). 사용자 인증은 없다.
- 명세 조회 연결 실패·시간 초과·그룹 5xx: 503 `GROUP_SERVICE_UNAVAILABLE`.
- 그룹의 401·403, 리다이렉트·잘못된 명세·명세 404: 호출 설정/계약 오류이므로 500 `INTERNAL_ERROR`.
- 그룹 업무 API의 404·업무 오류는 해당 업무 계약에서 매핑한다. 명세 조회의 404 규칙을 일괄 적용하지 않는다.
- 실패를 빈 결과로 숨기지 않는다. 사용자 응답은 공통 ProblemDetail이며 내부 응답·주소·키를 공개하지 않는다.

## POL-COMM-005 계약 소유

그룹 내부 계약은 `services/group-service/docs/api.md`가 소유한다. 멤버는 자기 코드의 모델로 읽는다. 현재 명세 전달 테스트는 실제 HTTP를 사용하지만 업무 조회 계약의 증거는 아니다. 기능 개발 때 그룹 명세와 소비자 테스트로 호환성을 확인한다.
