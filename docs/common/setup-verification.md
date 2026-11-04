# SETUP-001 현재 구성 검증

| 항목 | 내용 |
| --- | --- |
| 일자·브랜치 | 2026-11-04, feature/SETUP-001 작업 폴더 |
| 목적 | 사용자 인증 제외·내부 키·멤버 경유 명세·독립 실행을 실제 구현과 대조 |
| 관련 코드 | 서비스 src/main·src/test, 루트/서비스 Compose, scripts/verification/ |
| 범위 | 로컬 빌드·실제 Docker·Edge·문서 검사. 커밋·develop/master 반영은 별도 |

## 현재 적용 상태

- JDK 21, Kotlin 2.3.21, Spring Boot 4.1.2, Gradle 9.3.0, MariaDB 11.8.9, JPA·Flyway 구성 유지.
- 멤버는 사용자 인증 없이 요청을 받으며 그룹은 공유 시크릿 헤더를 확인한다. 사용자 JWT·Swagger 자동 토큰 주입·그룹의 멤버 클라이언트를 제거했다.
- 멤버 Swagger에서 두 명세를 선택한다. 그룹 명세는 멤버가 서버 키로 조회하여 내부 서버 주소·브라우저 인증 요구를 제거한 읽기 전용 사본으로 제공한다.
- 그룹 앱의 호스트 포트는 없고 루트 내부 앱 네트워크에서 숫자 IP로 통신한다. DB 네트워크와 볼륨은 서비스별로 분리한다.
- 오류 enum은 code·status·codeName·description을 유지한다. 서비스 전용 오류 코드 두 개는 각 소유 서비스에 추가했다.
- 내부 키 생성은 기존 DB 비밀번호를 유지하고 파일 이름만 출력한다. 실제 키는 조회하거나 출력하지 않았다.

## 실행 검증 결과

명령은 저장소 루트 기준이다. 테스트 보고서는 services/<서비스>/build/test-results/test/에 있다. 실패·수정·재실행 내역은 AI 원본 기록에 남는다.

| 검사 | 방법 | 결과 |
| --- | --- | --- |
| 포맷·전체 빌드 | ./gradlew.bat spotlessApply 후 build --no-daemon --console=plain | 성공 |
| 자동 테스트 | JUnit·MockMvc·실제 Testcontainers MariaDB·JDK HTTP 서버 | 멤버 20, 그룹 9, 총 29개. 실패·오류·건너뜀 0 |
| 내부 키 | 누락·불일치·중복 거부, 정상 키 명세 조회, 필수 키 누락 기동 실패, 사용자 헤더 덮어쓰기·정규화 경로 예외 검사 | 통과 |
| 주소·클라이언트 | 숫자 IPv4/IPv6·포트 검증, 잘못된 주소·0초 거부, 연결 실패·시간 초과 재시도, 5xx/변경 요청 재시도 금지 | 통과 |
| 명세 전달 | 서버 키 첨부·내부 주소 제거·공개 명세에서 전달 경로 제외·리다이렉트 미추적·명세 오류 변환 | 통과 |
| 장애 응답 | 명세 5xx·시간 초과는 503, 내부 키 거부·잘못된 명세는 500 | HTTP 모의 서버와 실제 서비스 중단으로 구분해 확인 |
| JPA | 실제 MariaDB 저장·flush·clear·재조회 | 한글·이모지·UTC 마이크로초 시각 유지 |
| 소스 이미지 빌드 | docker compose up -d --build --wait | 두 앱 소스 빌드, 앱 2개·DB 2개 healthy |
| Compose 구조·실제 호출 | python scripts/verification/runtime-smoke.py | 앱만 내부 네트워크, DB 격리, 그룹 호스트 8082 미공개, 키 없는/잘못된 그룹 요청 403, 멤버 경유 명세 200 |
| 중단·단독 실행 | 위 스크립트 --independence | 그룹 중단 중 멤버 재기동·readiness·자기 명세 유지, 그룹 명세 503. 두 서비스 각각 단독 Compose 기동 성공 |
| 최종 복구 | 독립성 검증 스크립트 finally | 단독 앱·DB 중단, 루트 통합 모드 복구, 그룹 명세 재조회 200. DB 볼륨 삭제 없음 |
| 실제 브라우저 | node scripts/verification/swagger-browser.mjs, Edge headless | 두 명세 조회·선택, 멤버 실행 버튼, 그룹 읽기 전용, 멤버로 복귀, 외부 주소 직접 요청 없음 |
| 산출물 | 두 운영 bootJar 항목 검사 | 테스트 Probe·TestJwt·SwaggerAutoAuth·이전 방향 클라이언트·OAuth 의존성 없음 |
| 문서 | 공통·서비스 문서 상대 파일 링크·제목 앵커 | 143개, 오류 0 |

## 검증 범위의 한계

- 업무 엔티티·조회 API·업무 QueryHandler·실제 그룹 업무 조회 계약은 아직 없다. 명세 paths가 비어 있는 것은 이 범위에 따른다.
- 브라우저는 실제 두 명세를 조회한다. 업무 API 실행 버튼 여부만 확인하기 위해 브라우저 메모리에 테스트 명세를 넣었으며 실제 업무 API를 호출하지 않았다.
- Windows bootRun·IDE 직접 실행과 외부 호스트 HTTPS 배포는 이번에 기동 검증하지 않았다. 바인딩·환경변수 안내는 실행 문서에 있다.
- 그룹의 정적 내부 키는 키 소유 확인이다. 프로세스 신원·사용자 권한을 검증하는 체계가 아니다.
- 이전 구조의 JWT·양쪽 Swagger·반대 방향 클라이언트 결과를 현재 검증으로 재사용하지 않았다.
- Git 커밋·병합·push와 AI 로그 편집은 하지 않았다. develop 검증·사용자 master 반영은 저장소 절차를 따른다.
