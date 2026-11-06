# 비밀 파일 없는 최초 실행 검증

| 항목 | 내용 |
| --- | --- |
| 분류 | 실행 검증 기록 |
| 상태 | 최초 기동·실제 HTTP 연동·생성 재실행 보존 통과 |
| 일자 | 2026-11-05 |
| 소스 기준 | aaf0005990fa6dfd8c4e661935472dbe3aebc18a + REVIEW-002의 startLocal 태스크 변경 |
| 실행 방법 | [README](../../../README.md#실행과-종료), [로컬 실행](../../common/local-development.md) |

## 확인한 문제와 변경

`secrets/`는 Git 제외 대상이므로 clone 직후 Compose만 실행하는 것으로는 기동할 수 없다. 기존 README는 생성과 Compose 실행 두 단계를 안내했다. `startLocal` 루트 태스크는 기존 `generateLocalSecrets`에 의존하고 이어서 `docker compose up -d --build --wait`를 실행한다. 새로운 키 생성기를 만들거나 기존 비밀 파일을 교체하지 않는다. 생성 단계가 실패하면 실행 단계도 수행하지 않는 Gradle 의존 관계다.

```powershell
./gradlew.bat startLocal
```

## 검증 방법

Git 추적 소스만 `git archive`로 새 폴더에 내보내고 아직 커밋하지 않은 startLocal 변경만 적용했다. 네트워크에서 git clone을 실행한 것은 아니며, 기존 secrets·빌드 산출물을 복사하지 않았다. 최초 secrets 폴더 부재를 확인하고 실제 명령을 실행했다. Gradle·Docker의 다운로드 캐시는 사용하므로 완전한 오프라인 또는 최초 다운로드 호환성 검증은 아니다.

프로젝트 이름은 `review002-fresh-20261105170248`이다. 기존 개발 환경과 충돌하지 않게 검증 전용 이미지 태그·임의 호스트 포트·172.31.204.0/24 네트워크·새 DB 볼륨을 사용했다. DB 호스트 포트는 열지 않았다. 이 차이는 격리 목적이며 기본 실행 파일의 고정 포트와 주소를 바꾸지 않았다. 애플리케이션·DB·시드·HTTP 내부 키 방식은 동일하다.

## 결과

| 검사 | 결과 |
| --- | --- |
| secrets 없는 상태에서 startLocal | 성공, 1분 11초 |
| 생성 파일 | DB 관리자 2개, DB 앱 계정 2개, 내부 호출 키 1개. 총 5개, 빈 파일 없음 |
| 두 서비스·두 DB healthcheck | 모두 정상 |
| 멤버 readiness | HTTP 200 |
| GET /api/members | HTTP 200, JSON 파싱 통과 |
| GET /api/member/tu3fgxd45k | HTTP 200, JSON 파싱 통과 |
| 멤버 경유 그룹 summary | HTTP 200, JSON 파싱 통과 |
| 멤버 경유 그룹 detail | HTTP 200, JSON 파싱 통과 |
| generateLocalSecrets 재실행 | 5개 파일의 크기·수정 시각 유지 |
| 종료 | 검증용 Compose 프로젝트만 down 성공. 볼륨 삭제 없음 |

비밀 파일 내용은 읽거나 출력하지 않았다. 실제 그룹 서비스와 연동했으며 모의 서버를 쓰지 않았다. 이 검사는 기동·연결 확인이고 새 응답 계약의 정확한 필드 검증을 대신하지 않는다. 결과 JSON과 빌드 출력은 로컬 `.ai-logging/review-002/fresh-start-result.json`, `fresh-start-build.log`, `fresh-repeat.log`에 보존했다.

원래 작업 폴더에서도 `gradlew.bat build --no-daemon --console=plain`이 26초에 성공했다(39개 태스크 중 10개 실행·29개 up-to-date). 두 서비스 test는 기존 결과를 재사용했고 OpenAPI 대조는 실행됐다. 신규 전체 API 테스트를 165개 재실행했다고 해석하지 않는다. `git diff --check`도 통과했다.

## 제한

호스트 JDK 21, Docker·Compose, 의존성을 받을 네트워크와 사용 가능한 포트가 필요하다. 기존 DB 볼륨만 남고 비밀번호를 잃은 경우에는 원래 비밀번호 복원이 필요하다. 새 비밀번호가 기존 DB의 비밀번호를 바꾸지는 않는다. 이 자동화는 사용자 볼륨을 삭제하거나 DB 초기화를 강제하지 않는다.
