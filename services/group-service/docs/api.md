# 그룹 서비스 API 정의

| 항목 | 내용 |
| --- | --- |
| 목적 | 현재 HTTP 경로와 후속 업무 계약 소유 |
| 범위 | 내부 그룹 IP:8082. 통합 구성 172.30.104.3:8082 |
| 관련 코드 | infrastructure/configuration/, presentation/error/, application.yaml |

## 현재 제공 경로

| 방법·경로 | 호출 조건 | 응답 |
| --- | --- | --- |
| GET `/actuator/health` | 키 없음 | 상태 JSON |
| GET `/actuator/health/liveness` | 키 없음 | 생존 상태 |
| GET `/actuator/health/readiness` | 키 없음 | 자기 DB 포함 준비 상태 |
| GET `/v3/api-docs` | X-Internal-Service-Key 필요 | 그룹 OpenAPI JSON |

그룹 업무 API와 명세의 내부 키 누락·불일치·중복은 403 INTERNAL_ACCESS_DENIED다. Swagger UI는 제공하지 않는다. 키가 없는 UI 요청은 필터에서 403, 정상 키 요청은 미존재 경로 404다.

## 후속 계약 작성 기준

업무 조회 API는 아직 없다. 테스트 전용 `/test-probe/**`는 운영 API가 아니다. 실제 명세의 업무 paths는 비어 있을 수 있다.

조회별 경로·입력·응답·페이지·빈 결과·404·의존 서비스 장애·예제를 정의한 뒤 구현한다. [공통 API 규칙](../../../docs/common/api-conventions.md)을 따른다. 업무 경로를 가상으로 만들어 완료로 표시하지 않는다.
