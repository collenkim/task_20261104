# 멤버 서비스 API 정의

| 항목 | 내용 |
| --- | --- |
| 목적 | 현재 HTTP 경로와 후속 업무 계약 소유 |
| 범위 | 사용자 진입점 http://127.0.0.1:8081 |
| 관련 코드 | infrastructure/configuration/, presentation/error/, application.yaml |

## 현재 제공 경로

| 방법·경로 | 호출 조건 | 응답 |
| --- | --- | --- |
| GET `/actuator/health` | 키 없음 | 상태 JSON |
| GET `/actuator/health/liveness` | 키 없음 | 생존 상태 |
| GET `/actuator/health/readiness` | 키 없음 | 자기 DB 포함 준비 상태 |
| GET `/v3/api-docs` | 사용자 인증 없음 | 멤버 OpenAPI JSON |
| GET `/swagger-ui.html`, `/swagger-ui/index.html` | 사용자 인증 없음 | 두 명세 선택 화면 |
| GET `/internal-docs/group` | 사용자 인증 없음 | 서버 키로 받은 그룹 참고 명세. no-store |

그룹 명세 전달은 모든 프로필에 제공한다. 내부 서버 주소·브라우저 인증 요구를 제거하고 공개 업무 명세에서는 전달 컨트롤러를 제외한다. 그룹 연결 실패·시간 초과·5xx는 503 GROUP_SERVICE_UNAVAILABLE, 내부 키 거부·명세 계약 오류는 500 INTERNAL_ERROR다.

## 후속 계약 작성 기준

업무 조회 API는 아직 없다. 테스트 전용 `/test-probe/**`는 운영 API가 아니다. 실제 명세의 업무 paths는 비어 있을 수 있다.

조회별 경로·입력·응답·페이지·빈 결과·404·의존 서비스 장애·예제를 정의한 뒤 구현한다. [공통 API 규칙](../../../docs/common/api-conventions.md)을 따른다. 업무 경로를 가상으로 만들어 완료로 표시하지 않는다.
