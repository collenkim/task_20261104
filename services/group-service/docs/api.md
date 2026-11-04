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

## 멤버 소속 일괄 조회 계약 (제공자 미구현)

2026-11-05 MEMBER-001 소비자 선행 개발의 기준 계약이다. 그룹의 업무 Controller·DB·시드·원본 OpenAPI paths는 아직 없다. 멤버는 local fixture 또는 테스트 HTTP 서버로 소비자 동작을 검증한다. 이 문서는 그룹이 소유하며 실제 제공자 구현 때 아래 계약을 검증해야 한다.

`GET /internal/group-memberships?memberIds=tu3fgxd45k&memberIds=k2m9x0p1q3`

- 내부 키 `X-Internal-Service-Key`가 필요하다. GET 본문은 사용하지 않는다.
- `memberIds`는 반복 파라미터(form/explode)로 1~100개다. 각 ID는 `^[a-z0-9]{10}$`, 중복·빈 값·형식 위반은 400이다. 공개 멤버 API의 page/size 반복 금지와 구분한다.
- 소속 관계만 조회하므로 멤버 DB의 존재 여부를 확인하지 않는다. 조회한 소속이 없으면 해당 ID의 `memberships: []`다.

```json
{
  "members": [
    {
      "memberId": "tu3fgxd45k",
      "memberships": [
        {
          "groupId": "arutjf9348",
          "groupName": "개발팀",
          "permission": "Editor",
          "permissionName": "편집자",
          "joinedAt": "2026-08-15T04:00:00Z",
          "pricing": "Premium"
        },
        {
          "groupId": "demo000002",
          "groupName": "프로젝트팀",
          "permission": "Viewer",
          "permissionName": "조회자"
        }
      ]
    },
    { "memberId": "k2m9x0p1q3", "memberships": [] }
  ]
}
```

| 필드 | 계약 |
| --- | --- |
| members | 필수·비null 배열. 요청 ID마다 정확히 한 항목. 순서는 보장하지 않음 |
| memberId | 필수·비null. 요청한 ID 집합과 정확히 일치 |
| memberships | 필수·비null 배열, 0~N개. 같은 멤버에서 groupId 중복 불가 |
| groupId | 필수·비어 있지 않은 10자리 소문자 영숫자 문자열 |
| groupName | 필수·공백만으로 구성할 수 없음 |
| permission / permissionName | 필수·비어 있지 않은 코드/표시명. 소비자는 새로운 코드를 허용 |
| joinedAt | 선택·null 허용. 있으면 UTC RFC 3339, 최대 마이크로초 정밀도 |
| pricing | 선택·null 허용 코드 문자열. 소속 여부·기본 권한 조회의 전제 조건이 아님 |

알 수 없는 추가 필드는 소비자가 무시한다. 선택 정보 부재는 핵심 소속 계약 위반이 아니다. `joinedAt`이 존재하지만 형식이 잘못된 경우는 계약 오류다. `pricing`의 빈 문자열은 소비자에서 null로 정규화한다. 필수 필드 부재를 Kotlin 기본값 `emptyList()` 등으로 숨기지 않는다.

정상은 200, 요청 위반 400, 내부 키 거부 403, 서버 장애 5xx다. 권한·상태 코드의 정의는 그룹 정책이 소유하며 멤버에 enum 클래스를 배포하지 않는다. 멤버의 HTTP 어댑터는 그룹 4xx/리다이렉트/잘못된 본문을 500, 통신 실패·그룹 5xx를 503으로 매핑한다.

실제 그룹 API 구현 후 원본 OpenAPI와 멤버 소비자 예제·모의 HTTP 테스트를 대조하고 실제 두 서비스 조회를 검증한다. 현재 문서·fixture만으로 제공자 적합성 검사를 완료했다고 기록하지 않는다.
