# ADR-003 미리 생성한 고정 JWT 키·토큰 사용

| 항목 | 내용 |
| --- | --- |
| 상태 | 확정 (2026-11-04) |
| 범위 | API 인증, 서비스 간 호출 인증, Swagger 사용 |
| 관련 코드 | `build-logic/src/main/kotlin/task/buildlogic/LocalSecretsTask.kt`, `services/*/src/main/resources/application.yaml`(`spring.security.oauth2.resourceserver.jwt`) |

## 배경

- API는 JWT로 인증하기로 했다. 사용자 로그인·토큰 발급 기능은 요구사항에 없다.
- Swagger UI에서 별도 절차 없이 API를 호출할 수 있어야 한다.

## 결정

- 인증키(RSA 키 쌍)와 토큰을 미리 생성해 고정으로 쓴다.
  - 생성: `./gradlew generateLocalSecrets`
  - 교체: `./gradlew rotateLocalJwt`
- 서비스는 공개키로 서명·만료·`aud`만 검증한다. 개인키는 생성 도구만 쓴다.
- 토큰은 용도별로 나눈다: Swagger·로컬 호출용(`aud`: 두 서비스), 서비스 간 호출용(`aud`: 멤버 서비스). 유효 기간은 365일이다.
- local 프로필의 Swagger UI는 Swagger용 토큰으로 자동 인증한다([POL-SEC-005](../security.md#pol-sec-005-swagger-자동-인증-local-프로필)).

## 검토한 대안

| 대안 | 판단 |
| --- | --- |
| 인증 서버(Spring Authorization Server, Keycloak 등) | 발급 서버 운영이 이번 범위를 넘음. 채택하지 않음 |
| HMAC 공유 비밀(HS256) | 검증하는 모든 서비스가 서명 비밀을 가져야 해 위조 위험이 커짐. 채택하지 않음 |
| 인증 없음 | 서비스 간 호출 인증 정책을 검증할 수 없음. 채택하지 않음 |

## 결과

- 키·토큰은 `secrets/`에만 있고 Git·이미지에 들어가지 않는다([POL-SEC-004](../security.md#pol-sec-004-키토큰비밀번호)).
- 운영 환경에서는 그 환경의 키·토큰을 같은 파일 이름으로 주입하거나, 발급 서버 도입을 새 ADR로 정한다.
