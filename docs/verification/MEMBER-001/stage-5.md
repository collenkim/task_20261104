# MEMBER-001 5단계 계약·실행 검증

| 항목 | 내용 |
| --- | --- |
| 분류 | 검증 기록 |
| 상태 | 이력 · 당시 요청·계획·검증 내용을 보존. 현재 구현 완료 선언이 아님 |
| 현재 기준 | [문서 색인](../../README.md)의 공통·서비스 정의를 따른다 |

| 항목 | 상태 |
| --- | --- |
| 기준 | 2026-11-05, `feature/MEMBER-001`, HEAD `5a0452c` + 작업 폴더 |
| 4단계 커밋 | 예약 `263da9e85203` 완료. `5a0452cd88f2f02d8e5730b56708b6afe37f98b0`의 24개 경로·내용이 검증본과 일치 |
| 이번 범위 | M-07 OpenAPI 공유·검사, M-10 문서, M-11 Compose·Swagger, M-12 인계. M-09 계층·트랜잭션 회귀 유지 |
| 당시 상태 | 기능 구현·계약·실행 검증 완료. 5단계 커밋은 예약 실행 후 SHA 확인 필요 |
| 브랜치 | feature에서 검증. develop 통합 검증·사용자의 feature → master 병합은 아직 수행하지 않음 |

## 계약 산출물과 구현

- [공유 OpenAPI](../../../services/member-service/docs/openapi.json)와 [소비자 안내](../../../services/member-service/docs/contracts.md)를 추가했다. 운영 Controller·DTO가 명세 원천이며 Kotlin/JPA 모델을 서비스 간 공유하지 않는다. operationId는 `getMemberSummaries`, `getMemberDetail`이다.
- `generateOpenApiSnapshot`은 실제 운영 패키지만 스캔하는 별도 Spring 문맥에서 계약 8개를 검사하고 build 디렉터리에 JSON을 생성한다. 테스트 Probe·그룹 명세 전달 경로는 제외하고 공개 두 경로만 허용한다.
- `updateOpenApiSnapshot`은 검증 성공 후 명세를 docs에 복사한다. `checkOpenApiSnapshot`은 저장소 파일을 읽기 전용으로 비교하며 일반 check/build에 연결했다. 객체 키 정렬·서버 URL `/` 정규화·UTF-8/LF만 적용하고 배열·설명·필수/null 의미를 보존한다.
- 공식 OpenAPI 3.1 메타스키마 네 파일과 라이선스·출처·해시를 테스트 자원에 보관했다. URI를 클래스패스로 매핑하므로 메타스키마를 검사할 때 인터넷에 의존하지 않는다. Git 개행 변환도 막는다.
- NetworkNT 3.0.6을 테스트 전용으로 고정했다. JDK 21·Kotlin 2.3.21·Boot 4.1.2에서 실행했고 테스트와 운영의 Jackson이 모두 3.1.7임을 확인했다. 라이브러리의 [공식 POM](https://github.com/networknt/json-schema-validator/blob/3.0.6/pom.xml)과 [OpenAPI 검사 방식](https://github.com/networknt/json-schema-validator/blob/3.0.6/doc/openapi.md)을 확인했다.
- 발송 불가 합성 데이터의 `example.invalid` 도메인만 테스트 이메일 검사에서 추가 허용한다. 기존 로컬 부분 검사·잘못된 이메일 검출·날짜 형식 검사는 유지한다. 실제 시드·공개 DTO의 이메일을 바꾸지 않았다.
- 실제 오류 응답에서 명세상 필수 `type`이 빠지는 것을 발견했다. Spring의 기본 생략 동작을 멤버의 `MemberProblemDetail`에서 `about:blank`로 명시했다. 상태·코드·기존 확장 속성은 유지하며 그룹 코드에는 적용하지 않았다.

## 자동 검사

```powershell
.\gradlew.bat :member-service:updateOpenApiSnapshot --no-daemon --console=plain
.\gradlew.bat :member-service:generateOpenApiSnapshot --rerun :member-service:checkOpenApiSnapshot --no-daemon --console=plain
.\gradlew.bat spotlessApply build --no-daemon --console=plain
```

- 최종 전체 빌드 성공: 1분 22초, 43개 작업 중 실행 4·최신 상태 39. 이번 단계에서 멤버 기존 회귀 118개와 별도 계약 검사 8개를 실제 실행했고 실패·오류·건너뜀은 0이다. 그룹 9개는 기존 성공 결과를 재사용했다.
- 계약 검사: 공식 명세 유효성, 정확한 경로·operationId·입력 범위, 필수/null·열린 권한 코드, OpenAPI 오류 예제 7개와 서비스 문서 JSON 예제 3개, 실제 목록·상세·400/404/500/503 응답을 대조했다. 오류 상황은 테스트 공급자에서 명시적으로 발생시키며 실제 그룹 장애로 보고하지 않는다.
- 필수 필드 누락·잘못된 타입·비null 위반·잘못된 날짜/이메일을 검사기가 검출하는 대조 시험을 포함했다. 새 권한 코드·추가 응답 필드는 허용한다.
- 별도 프로세스에서 명세를 다시 생성한 결과가 바이트 동일했다. 파일 SHA-256은 `01b1fae95e36988776ae5a78d881e442845391384a0b5e8fadf1921c57048023`이다.
- 생성한 저장소 명세에 임시 개행 차이를 넣어 비교 작업이 실패하고 파일을 수정하지 않는지 확인했다. 검사 후 원래 바이트로 복구했다. API 로그나 사용자 파일을 편집한 실험이 아니다.
- 운영 jar에는 테스트 Probe·계약 테스트·공식 메타스키마·NetworkNT·ArchUnit이 없다. 기존 pageable 설정과 README AI 기록 영역은 기준 커밋과 동일하다.
- 문서 43개·로컬 링크와 앵커 258개·JSON 예제 5개를 검사했고 오류는 0이다. 공식 메타스키마 원본 해시, Python·JavaScript 구문, Git 공백 검사도 통과했다.

## 실제 실행 검사

```powershell
docker compose up -d --build --wait
docker compose up -d --build --wait member-service
python -X utf8 scripts/verification/runtime-smoke.py --independence
node scripts/verification/swagger-browser.mjs
```

첫 명령으로 두 서비스 이미지를 만들었고, 오류 type 보완 후 두 번째 명령으로 최종 멤버 이미지를 반영했다.

| 검사 | 실제 결과 |
| --- | --- |
| 통합 Compose | 앱 2개·DB 2개 준비 완료. 실제 멤버 명세와 공유 스냅샷 일치 |
| 업무 API | 시드 5명 목록·상세·0/N 소속·이름 정렬·ID 보조 정렬·페이지·빈 페이지·크기 보정·입력 오류·미존재·UTC 마이크로초·no-store 통과 |
| 내부 격리 | 그룹 호스트 포트 미공개, 앱 통신 네트워크·서비스별 DB 소유권, 키 없는/잘못된 그룹 명세 요청 거부 통과 |
| 그룹 중단 | 그룹 앱·DB 중단 후 멤버 재기동·readiness·fixture 업무 조회 성공. 실제 그룹 명세 조회는 503 |
| 단독 실행 | 그룹 소유 Compose, 멤버 소유 Compose 각각 기동. 멤버 단독 상태에서 같은 업무 API 검사 통과 |
| 복구 | 단독 앱·DB 정지 후 루트 통합 모드 복구, 그룹 명세와 fixture 업무 재조회 성공. 볼륨 삭제 없음 |
| 실제 Edge Swagger | 명세 변경/주입 없이 목록·상세 Try it out으로 200과 다중 소속 확인. 그룹 명세 선택·읽기 전용·멤버 복귀 확인 |
| 브라우저 경계 | 외부 origin 직접 요청·내부 키 헤더 전송 없음. 그룹 업무 API 실행은 없음 |

실행 스크립트는 고정 local 시드를 기대한다. 데이터가 다르면 실패하고 임의 초기화·삭제·값 출력을 하지 않는다. 실제 그룹 업무 제공자는 아직 구현되지 않았으므로 업무 데이터는 멤버 fixture다. 현재 그룹 명세 전달은 실제 그룹 서비스에 대한 HTTP다.

## 수정·재검증 이력

- Jackson 3의 노드 map API와 Kotlin 컬렉션 API 충돌을 명시적인 목록 변환으로 보완했다.
- 생성 문맥에 테스트 Probe가 섞이는 실패를 검출해 운영 계층 스캔과 전용 프로필로 격리했다. 별도 문맥의 JPA 탐색 기준도 운영 루트에 맞췄다.
- 최초 라이브러리 3.0.8이 테스트 Jackson을 3.2로 올리는 것을 의존성 검사에서 확인해 3.1 계열 기반인 3.0.6으로 고정했다.
- 오류 type 누락은 실제 Compose에서도 재현했다. 설정 적용 순서에 의존하는 mix-in 시도를 제거하고 오류 타입에서 기본값을 명시한 뒤 계약·전체 회귀를 통과했다.
- Swagger 상세 검사의 DOM 입력만으로 상태 반영을 가정하지 않는다. 실제 브라우저 입력과 Swagger의 지연된 파라미터 상태 반영을 기다린 뒤 Execute를 누르게 보완했고 두 업무 API 전송을 확인했다.

## 완료 기준과 남은 절차

| 계획 기준 | 근거 |
| --- | --- |
| fixture 실제 목록·상세 200 | Compose·Edge 업무 호출 |
| 입력·정렬·페이지·다중 소속 | 기존 3단계 검사 + 5단계 스키마·실행 검사 |
| HTTP 소비자·재시도 | 2·4단계 검사 + 전체 회귀 |
| 낮은 결합·실제 트랜잭션 종료 | ArchUnit·동일 호출 스레드 검사 회귀 |
| local 시드·기존 데이터 보호 | 실제 MariaDB 프로필·재실행·충돌 검사 회귀, 실행 DB 볼륨 유지 |
| 공유 명세·실제 응답 검사 | 결정적 생성·읽기 전용 비교·오류 검출 대조 시험 |
| 그룹 실행 코드·DB 변경 없음 | 이번 단계는 멤버·문서·검증 스크립트 범위 |
| 실제 그룹 업무 통합 미검증 명시 | fixture·테스트 HTTP·실제 명세 HTTP의 증거를 구분 |

5단계 커밋 실행과 SHA/내용 확인 뒤 feature를 develop에 병합해 통합 검증한다. 문제가 있으면 같은 feature에서 보완하고 develop에서 다시 검증한다. master에는 사용자가 검증된 feature를 병합한다. 이 단계에서 develop/master 병합·push를 실행하지 않았다.

AI 로그 원본·스냅샷·마스킹 설정은 직접 수정하지 않았다. 로그 제출본의 최신화와 보류 해소는 기존 수집기 상태에 따르며 API 검증 성공으로 대신하지 않는다. 상세 결과·검증 파일 해시는 Git 제외 `build/verification/member-001/stage-5.json`에 보관한다.

읽기 전용 로깅 상태 확인에서 Codex hook 실행 증거는 없고 턴은 `unknown (turn_started_without_hook)`이었다. 마지막 수집 결과는 2026-11-04 23:24:27 UTC의 보류 221건이며 최신 대화의 제출 사본 반영 완료를 뜻하지 않는다. 이 기록은 원본 유실 판정이 아니며, 로그 제출 전 기존 수집기로 보류를 해소하고 최신 원본 포함 여부를 확인해야 한다.
