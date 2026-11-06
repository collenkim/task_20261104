# REVIEW-002 착수 기준

| 항목 | 내용 |
| --- | --- |
| 분류 | 착수 검증 기록 |
| 상태 | 브랜치·로그 보존·환경 확인 완료, 응답 변경 D2·D3·D15 확인 대기 |
| 확인일 | 2026-11-05 |
| 기준 커밋 | aaf0005990fa6dfd8c4e661935472dbe3aebc18a |
| 계획 | [REVIEW-002](../../tasks/REVIEW-002/plan.md) |

이 문서는 착수 시점의 기록이다. 이후 D2·D3·D15 승인과 코드 반영·실행 결과는 [최종 검증](verification.md)에 기록했으며 아래 착수 관찰을 소급 변경하지 않는다.

## 브랜치와 로그

착수 시 HEAD(feature/REVIEW-002), master, origin/master가 모두 위 커밋으로 같고 작업 폴더가 깨끗했다. REVIEW-001의 master 병합 커밋이 기준이다. develop의 `ai-logs/snapshots/` 60개 파일이 master에 동일 Git 객체·모드로 보존됐으며 누락·변경은 0개다. 최종 세션 원본 끝까지의 완전성 검사를 뜻하지 않는다.

REVIEW-001에서 로그 선택 누락·검증 캐시·진행 표시 수정은 반영됐다. CODE-05에서 이를 다시 구현하지 않는다. 부속 메타데이터 보류와 Codex hook 실제 실행 여부는 별도 확인 대상이다.

## 실행 환경과 기존 검사

- 호스트 Temurin JDK 21.0.12.1, Python 3.12.10, Git 2.55.0.windows.5.
- Docker CLI/Engine 29.8.2, Compose 5.5.1. 샌드박스의 Docker 파이프 접근은 거부됐고, 권한을 허용한 읽기 전용 조회에서 Engine까지 확인했다.
- 저장소 버전: Gradle Wrapper 9.3.0, Kotlin 2.3.21, Spring Boot 4.1.2. 버전 변경 없음.
- 기존 XML 보고서: 멤버 136개, 그룹 29개, 실패·오류·건너뜀 0개. 이전 실행 산출물이며 REVIEW-002에서 새로 실행한 165개라고 표시하지 않는다.
- 공유 OpenAPI 버전: 멤버 0.2.0, 그룹 0.1.0. 승인 전 현재 계약 파일을 바꾸지 않는다.

읽기 전용 검사와 파일별 테스트 시각·명세 해시: `.ai-logging/review-002/baseline-audit.json`.

## 코드에서 재확인한 차이

`MemberResponses.kt`와 `MemberResponseMapper.kt`에 groups 배열, createdAt, createdBy, joinedAt이 실제로 존재하며 직렬화 DTO로 매핑된다. 문서에만 남은 내용이 아니다. 단일 group·감사 필드 제외·그룹 가입일 명칭 변경은 D2·D3 결정 후 구현한다.

`GroupApiProxyClient`는 성공 본문이 JSON 객체인지 검사하지만 성공 응답의 중첩 필드 집합은 제한하지 않는다. 오류 본문은 여섯 키를 검사한다. 성공 응답의 추가 필드 처리 방식은 D15 확인 후 구현한다. JWT·DNS·중복 ID 처리·회로 차단은 별도 승인 전 현행 유지다.

## 추가 요청: 최초 실행

clone에 secrets가 없는 상태에서 Compose만 실행하는 것으로는 기동되지 않는다. README의 기존 두 단계는 생성 후 기동이며, 이번에는 `startLocal` 하나로 묶는다. Git 소스만 내보낸 별도 사본·Compose 프로젝트·포트·네트워크·볼륨에서 실행을 검증한다. 현재 사용자 키와 DB 볼륨은 검증 대상으로 사용하지 않는다. 결과는 [최초 실행 기록](fresh-start.md)에 남긴다.
