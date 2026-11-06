# REVIEW-002 코드·계약 검증

| 항목 | 내용 |
| --- | --- |
| 분류 | 구현·검증 기록 |
| 상태 | 1~4차 승인 코드·빌드·실제 Compose·Swagger 검증 완료. 로그 제출 보류는 별도 유지 |
| 검증일 | 2026-11-05~2026-11-06 (Asia/Seoul) |
| 대상 | feature/REVIEW-002, 기준 aaf0005990fa6dfd8c4e661935472dbe3aebc18a 이후 미커밋 작업 폴더 |
| 계약 | [승인 결정](../../tasks/REVIEW-002/approved-contract.md)([2차](../../tasks/REVIEW-002/approved-contract.md#2차-승인-2026-11-06)), [멤버 API](../../../services/member-service/docs/api.md), [그룹 API](../../../services/group-service/docs/api.md) |

## 반영 범위

- 멤버 목록은 id/name/group/permission, 상세는 id/name/email/address/registeredDate/group만 반환한다. 미소속은 group=null이며 목록 permission도 null이다. 감사 정보는 내부 모델·DB에 남고 공개 응답에서만 제외한다.
- 그룹 상세의 여섯 필드는 유지한다. 멤버 가입일과 그룹 가입일을 독립적으로 매핑한다. 표시명 대신 permission/pricing code만 노출한다.
- 그룹 실행 중계는 중첩된 항목까지 정의 필드만 새 객체에 복사한다. 추가 필드는 제거하며 필수 누락·null·타입·잘못된 ID/시각·중복 소속은 502 계약 오류다. JSON·매체·크기·원격 서버 오류도 코드로 구분한다. 연결 불가 503, 시간 초과 504, 멤버 자체 오류 500을 분리했다.
- Swagger 명세도 중계 공개 필드와 실패 상태를 표시한다. 그룹 소스·DTO·enum을 멤버가 직접 참조하지 않는다. 멤버 공개 OpenAPI는 0.3.0이며 그룹 원본은 0.1.0이다.
- `startLocal`은 비밀 파일 생성과 Compose 기동을 묶는다. [비밀 파일 없는 최초 실행](fresh-start.md)은 별도 소스 사본·포트·네트워크·볼륨에서 확인했다.
- 테스트 29개 파일을 책임별 패키지로 옮겼다. 기술 중계 Controller는 infrastructure.web으로 옮기고, 모든 RestController의 위치와 기술 중계의 DB·업무 저장소 의존 금지를 ArchUnit에 추가했다.
- [문서 검사기](../../../scripts/verification/docs-check.py)를 추가하고 현재 API·정책·결정표·README를 맞췄다. 과거 작업의 원래 검증 결과와 AI 원본 로그는 고쳐 쓰지 않았다.

## 실행 결과

최종 빌드는 2026-11-06에 1분 48초로 성공했다(47개 태스크 중 13개 실행·34개 재사용). 멤버 테스트는 마지막 코드 변경으로 다시 실행됐으며 그룹 일반 테스트는 이 작업에서 이미 통과한 결과를 재사용했다.

| 검사 | 명령·근거 | 결과 |
| --- | --- | --- |
| 전체 빌드·포맷·DB·계약 | `./gradlew.bat spotlessApply build startLocal --no-daemon --console=plain` | 성공. 멤버 143개·그룹 30개, 실패/오류/건너뜀 0 |
| 실제 Compose·독립 실행 | `python -X utf8 -I scripts/verification/runtime-smoke.py --independence` | 성공. 실제 HTTP·입력·정렬·페이지·키·네트워크·그룹 중단·두 서비스 단독 기동·통합 복구·동시 요청 추적 |
| 실제 Swagger | `node scripts/verification/swagger-browser.mjs` | 성공. Edge에서 네 API Try it out 200, 멤버 중계만 사용·내부 키 비노출 |
| 문서 검사기 회귀 | `python -X utf8 -I -m unittest discover -s tests -p test_docs_check.py -v` | 6개 성공 |
| 문서 링크·정책 | `python -X utf8 -I scripts/verification/docs-check.py` | 최종 문서 기준 오류 0 |
| 변경 공백 | `git diff --check` | 통과 |

기존 165개에 8개를 추가해 173개다. 추가 검사는 중계 필드 선별·필수/값/다건 오류·본문 시간 초과·재시도 뒤 본문 제한·명세 크기/자료형, 업무 응답 크기 경계, 기술 Controller 계층, 그룹의 잘못된 상세 ID/빈 summary다. 기존 메서드 이름을 새 보고서와 대조한 결과 누락 0개이며, 오류 상태와 단일 소속 표현에 맞춰 바꾼 이름 7개도 대응 확인했다. 로컬 비교 자료는 `.ai-logging/review-002/test-moves.json`, `test-inventory.json`이다.

첫 전체 검사에서 OpenAPI 0.2.0 기대값과 인터셉터 순서의 기존 검사가 실패했다. 버전 기대값을 0.3.0으로 바꾸고, 재시도 인터셉터를 마지막으로 유지하면서 각 시도 안에서 본문 기한을 적용해 해결했다. 이후 본문 시간 초과·추가 재시도 후 본문 기한을 실제 JDK HTTP 전송으로 검사했다.

실제 그룹 컨테이너 중단 시 내부 IP 연결이 시간 초과되어 중계가 504를 반환했다. 기존 실행 스크립트가 이를 일괄 503으로 기대해 실패했으며, 계약대로 연결 거부 503과 시간 초과 504의 코드 쌍을 각각 검사하도록 수정했다. 멤버 업무 API의 기존 503은 그대로 검사한다.

## 검증 범위와 회귀

공식 OpenAPI 메타스키마·실제 MockMvc 응답을 검사하며, 정의 외 필드를 의도적으로 넣었을 때 공개 스키마가 거부하는지 확인한다. HTTP 합성 제공자 검사에서는 추가 필드 제거·필수 누락·타입·미디어·손상 JSON·크기·5xx·리다이렉트·시간 초과를 주입한다. 이는 실제 그룹의 정상 연동 증거와 구분한다.

실제 MariaDB Testcontainers 검사에서 저장소 프록시 내부 읽기 트랜잭션과 종료 후 같은 멤버 호출 스레드의 HTTP 비트랜잭션, UNIQUE(member_id), 다건 방어, 두 쿼리의 일관성, local 시드·UTC TIMESTAMP(6)를 유지한다. 페이지 설정 블록·반복 입력/overflow/offset 검증·이름/ID 정렬·지수 백오프·Full Jitter 회귀도 유지한다.

테스트 이동 당시 package 선언을 제외한 본문 동일성을 비교했다. 최종적으로 기준 커밋의 테스트 메서드와 새 JUnit 보고서를 대조하며, 승인된 응답·오류 변경에 따른 이름 변경은 별도 대응시킨다. 단순 개수 증가만으로 누락 없음을 판단하지 않는다. 태그·생성 태스크·리소스 경로는 바꾸지 않았다. 캐시로 재사용한 성공 결과는 재실행이라고 표시하지 않는다.

1차 당시 CODE-02의 JWT/local 중계 제한, DNS 허용, 중복 ID 제거는 승인되지 않아 기존 동작을 유지했다. 이 중 JWT·local 중계 제한·중복 ID 제거는 아래 2차에서 적용했고 DNS 허용은 미적용이다. CODE-06의 응답 크기 제한과 성능 미측정 항목은 [성능 판단](performance.md)에 구분했다.

## 2차 검증 (2026-11-06)

[2차 승인](../../tasks/REVIEW-002/approved-contract.md#2차-승인-2026-11-06)에 따라 JWT 인증, 상태별 오류 코드표, 형식 400·의미 422 구분, 세부 원인 `type` URN, GET 전용 405, summary 중복 ID 제외와 원문 로그, MEMBER-001·GROUP-001 문서의 `groups[]` 제거를 적용한 뒤 다시 검증했다. 대상은 같은 기준 커밋 이후 미커밋 작업 폴더다.

### 반영 범위

- 멤버: OAuth2 Resource Server(RS256, iss·aud·exp·nbf·sub 검증, 허용 오차 30초). `/api/**`는 `member.read`, 그룹 중계는 `group.read`를 요구한다. 401·403은 ProblemDetail과 `WWW-Authenticate`(RFC 9728 `resource_metadata`)를 돌려주고 `/.well-known/oauth-protected-resource`를 공개한다. subject별 토큰 버킷으로 429 + `Retry-After`를 돌려준다.
- local 프로필: `generateLocalSecrets`가 init 때 RSA 키 쌍을 만들고, Swagger 초기화 스크립트 요청마다 30분 만료 토큰을 서명해 자동 인증한다. 그룹 중계·그룹 명세 전달은 `task.group-proxy.enabled=true`인 local에서만 등록한다.
- 두 서비스: GET 외 메서드(HEAD·OPTIONS·POST·PUT·PATCH·DELETE·TRACE)는 인증·조회보다 먼저 405 + `Allow: GET`이다. 오류 `code`는 상태별 코드표이고 원인은 `type`으로 구분한다. OpenAPI에 상태별 ProblemDetail 예제를 선언했다(멤버 0.4.0, 그룹 0.2.0).
- 그룹 summary: 형식 검사(400) → 원래 입력 수 100 초과(422) → 처음 나온 순서로 중복 제거 → WARN `event=input.duplicate_ids duplicateIds=[...]`.
- 검증 스크립트: [실행 검사](../../../scripts/verification/runtime-smoke.py)는 local 토큰을 초기화 스크립트에서 받아 헤더로만 보내며 값을 출력하지 않는다. [Swagger 검사](../../../scripts/verification/swagger-browser.mjs)는 Authorization 헤더가 Bearer JWT 형식인지만 기록한다.

### 실행 결과

| 검사 | 명령·근거 | 결과 |
| --- | --- | --- |
| 전체 빌드·포맷 | `./gradlew.bat spotlessApply build --console=plain` | 성공 |
| 테스트 강제 재실행 | `./gradlew.bat :member-service:test --rerun :group-service:test --rerun :member-service:generateOpenApiSnapshot --rerun :group-service:generateOpenApiSnapshot --rerun build --console=plain` | 성공(1분 33초). 멤버 214개(test 205 + 계약 9), 그룹 69개(test 68 + 계약 1), 실패/오류/건너뜀 0. JUnit 보고서 시각 2026-11-06T00:43~00:44Z |
| 실제 Compose 기동 | `./gradlew.bat startLocal --console=plain` | 성공(1분 15초). 없던 JWT 키 쌍 생성(값 미출력), 네 컨테이너 healthy |
| 실제 Compose·인증·독립 실행 | `python -X utf8 -I scripts/verification/runtime-smoke.py`, 이어서 `--independence` | 둘 다 성공. 목록·상세·정렬·페이지, 400/422 구분, 토큰 없음·무효·Basic 401, 메타데이터, 5개 경로 × 6개 메서드 405 + Allow, 그룹 중계 400/404/422, 중복 ID 200 + WARN 1줄(원문·개수), 그룹 직접 호출 403·405, 그룹 중단 시 업무 503 `SERVICE_UNAVAILABLE`·중계 503/504, 두 서비스 단독 기동·복구, 동시 요청 추적·ID/토큰 로그 비노출 |
| 실제 Swagger | `node scripts/verification/swagger-browser.mjs` | 성공. Edge에서 local 토큰 안내 표시, 두 명세 `bearerAuth` 자동 인증, Try it out 4건 200·모두 Bearer 전송, 내부 키 헤더 없음 |
| 문서 링크·정책 | `python -X utf8 -I scripts/verification/docs-check.py` | 문서 72개, 링크 590개, 정책 55개, 오류 0 |
| 문서 검사기 회귀 | `python -X utf8 -I -m unittest discover -s tests -p test_docs_check.py` | 6개 성공 |
| 변경 공백 | `git diff --check` | 통과 |

1차 173개에서 110개가 늘었다. 주요 추가 검사는 JWT 실패 10종(다른 키·iss/aud 불일치·누락·sub/exp 없음·만료·nbf 미래·형식 오류)과 scope 부족 403, 허용 오차 안 통과, 7개 메서드 × 업무·상태·명세·Swagger·테스트 경로 405, 토큰 버킷 단위·HTTP 429, 400/422 경계, 중복 ID 제거·WARN 로그, 모든 오류 상태의 계약 예제다.

처음 실행한 `cleanTest … build`는 빌드 캐시가 테스트 결과를 복원해 11초에 끝났다. 이 결과는 재실행으로 계산하지 않고 위 `--rerun` 결과만 기록한다.

### 남은 한계

- 409 `RESOURCE_CONFLICT`·415 `UNSUPPORTED_MEDIA_TYPE`은 코드표에 있으나 GET 조회만 제공해 발생 경로가 없으므로 OpenAPI에 선언하지 않았다. 406 `NOT_ACCEPTABLE`은 같은 규칙으로 추가한 코드다.
- 그룹 내부 호출 키 실패는 사용자 인증이 아니므로 401이 아닌 403 `FORBIDDEN`(type `internal-access-denied`)을 유지한다.
- 멤버 업무 조회는 그룹 시간 초과도 503으로 응답하고, 기술 중계만 503/504를 구분한다.
- 운영용 토큰 발급 서버 연동은 범위 밖이다. local 토큰만 제공하며 기본 프로필에는 발급·중계 경로가 없다. 요청 제한은 인스턴스 메모리 상태다([성능 판단](performance.md#2차-변경-2026-11-06)).
- DNS 허용·회로 차단·부하 성능은 미적용·미측정이다.

## AI 로그와 병합 상태

2026-11-06 후속 사용자 승인에 따른 29종 정확한 값 허용·2종 마스킹, 검증된 종료 메타데이터 이력 보존과 재수집은 [로그 처리 기록](log-holds.md)에 별도로 기록한다. 아래 수치는 이전 검증 시점의 이력이다. 현재 제출 상태는 후속 기록을 기준으로 한다.

2026-11-06 2차 검증 뒤 `python -I scripts/ai_logging/cli.py status`(읽기 전용) 결과는 수집 결과 hold, 보류 124건(이 세션 113건, 세션 29f7d66a 11건: original_rewritten 3·random_string 8)이다. 보류는 값 없이 규칙·위치만 확인했다. 내용 보류는 사용자 판단 후 치환·허용 목록 또는 원본 변경 보존 정책으로 처리한다. `turns resolve`는 종료된 턴의 상태를 정리하는 명령이며 내용 보류를 해소하지 않는다. Codex hook 실제 실행은 여전히 확인되지 않았다.

CODE-05에서는 읽기 전용 상태만 확인했다. 마지막 수집 상태에는 Claude 부속 `.meta.json`의 original_rewritten 보류 3건이 남아 있다. 정상 갱신 메타데이터의 버전 보존 정책(D14)은 미승인이므로 검사 우회·원본 변경·과거 사본 덮어쓰기를 하지 않았다. Codex hook 실제 실행도 아직 확인되지 않아 자동 수집 완전성을 통과로 표시하지 않는다. 상태 명령은 새 sync를 수행하지 않으므로 최종 원본 꼬리까지 검증했다는 뜻이 아니다.

착수 때 develop의 스냅샷 파일 60개가 master에 동일하게 포함된 것은 [기준 기록](baseline.md)에서 확인했다. 3차 재검토에서 manifest는 12개이며, 60은 부속 파일을 포함한 파일 수임을 구분했다. 이번 미커밋 코드의 develop/master 반영, 모든 턴 종료 후 최신 세션의 최종 제출 검사는 아직 수행하지 않았다. 기존 보류 해소와 hook 확인은 최종 로그 제출 전 필요한 별도 항목이다.

## 3차 검증 (2026-11-06)

기준은 f1c17f2 이후 feature/REVIEW-002 작업 폴더다. [전체 요구 재검증](requirements-audit.md)에 기존 구현과 이번 변경, 30개 요구의 코드·검사 근거, 문서와 실제 동작의 차이, AI 로그 범위를 정리했다.

- page 객체를 유지하고 요청 기본값·최솟값 및 응답 번호를 1로 맞췄다. offset은 (page-1)×size이며 저장소 내부는 0 기반이다. pageable 설정 블록을 유지하고 one-indexed-parameters를 추가했다.
- 업무 성공·오류·중계·필터·health 응답은 application/json이다. 여섯 필드 오류 규격과 상태별 code는 유지한다. Swagger HTML/JS는 본래 형식이다.
- 두 api.md에 같은 공통 코드표와 실제 발생 범위를 명시했다. OpenAPI 멤버 0.5.0·그룹 0.3.0을 생성했다. 새 루트 검사기가 서비스별 ErrorCode·공통 정책·API 문서·OpenAPI의 차이를 검출한다.
- 갱신과 build를 함께 요청할 때 Gradle이 감지한 암묵 의존 문제를 수정했다. 갱신 태스크의 출력은 openapi.json 하나이며 일반 build는 갱신하지 않는다.

| 검사 | 결과 |
| --- | --- |
| 전체 빌드 | `./gradlew.bat spotlessApply build --no-daemon --console=plain` 성공(1분 53초). 멤버 214개·그룹 69개, 실패/오류/건너뜀 0 |
| 계약 갱신+빌드 | 두 updateOpenApiSnapshot + build 함께 실행 성공(39초). 이후 일반 build 읽기 전용 대조도 성공 |
| Docker 재기동 | startLocal 성공(53초). 기존 비밀 파일 유지, 네 컨테이너 healthy |
| 공통 API 검사기 | 오류 0, 검사기 회귀 7개 통과 |
| 문서 검사기 회귀 | 6개 통과 |
| 실제 Compose·독립 실행 | runtime-smoke.py --independence 성공. 정상·오류·health JSON, 1 기반 페이지, 실제 그룹 호출, 그룹 중단 시 업무 503·중계 503/504, 두 앱/DB 단독 기동 후 통합 복구·동시 요청 추적 |
| Edge Swagger | swagger-browser.mjs 성공. 실제 네 API Try it out 200, page.number=1, Bearer 자동 인증, 내부 키·직접 그룹 주소 비노출 |
| 최종 문서·공백 검사 | 문서 73개·로컬 링크 610개·정책 55개, 오류 0. git diff --check 통과 |

최초 실제 실행 검사에서 Actuator가 기본 전용 미디어 타입을 반환해 실패했다. 두 서비스에 EndpointMediaTypes 설정과 health/readiness/liveness Content-Type 회귀 검사를 추가한 뒤 전체 빌드를 통과했다. 앞선 문서 page.number=0과 Gradle 순서 오류도 수정 전 실패로 구분한다.

이번 재검토는 AI 로그의 원본·치환 목록·커밋을 변경하지 않았다. develop과 master의 기존 로그 파일은 같지만 최신 수집 보류 124건과 Codex hook 미확인이 남아 있다. 최종 제출은 완료되지 않았다.

## 4차 검증: 그룹 summary 집계 (2026-11-06)

사용자 최종 결정으로 매핑 없는 ID는 제외하고 permission:null 항목을 생성하지 않는다. totalMemberCount는 각 그룹의 실제 반환 members 수이며, 전체 그룹 count 쿼리를 제거했다. 상세 필드와 멤버 API의 미소속 표현은 유지한다. [상세 수정·재현 기록](group-summary-fix.md)을 따른다.

전체 빌드 성공(1분 52초), 멤버 216개·그룹 70개 테스트 통과(실패/오류/건너뜀 0). 실제 MariaDB 조회 1회·집계 일치, 합성 HTTP count 불일치 거부, Docker 재기동 후 실제 API·Edge Swagger를 확인했다. 그룹 OpenAPI는 0.4.0이다. 매핑 있는 ID+없는 ID는 count 1/members 1, 전부 매핑이 없으면 group 빈 배열이다. 커밋·병합은 수행하지 않았다.
