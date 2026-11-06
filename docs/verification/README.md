# 검증 기록 색인

| 항목 | 내용 |
| --- | --- |
| 분류 | 검증 색인 |
| 상태 | 현재 |
| 목적 | 실행 시점·환경·커밋과 확인한 범위를 구분한다 |

| 작업 | 기록 | 해석 |
| --- | --- | --- |
| SETUP-001 | [구성 검증](SETUP-001/setup-verification.md) | 당시 구성·미구현 항목을 보존 |
| LOG-001 | [로깅 검증](LOG-001/ai-logging-verification.md) | 실제 원본·가짜 원본·미검증을 구분한 당시 기록 |
| MEMBER-001 | [1단계](MEMBER-001/stage-1.md)·[2단계](MEMBER-001/stage-2.md)·[3단계](MEMBER-001/stage-3.md)·[4단계](MEMBER-001/stage-4.md)·[5단계](MEMBER-001/stage-5.md) | 당시 다중 소속·시각·임시 공급자 검증을 현재 계약으로 오인하지 않음 |
| GROUP-001 | [그룹·멤버 연동 검증](GROUP-001/group-query-verification.md) | 실제 그룹 제공자·단일 소속·시각 변경·Swagger 중계 증거 |
| REVIEW-001 | [발견 사항](REVIEW-001/findings.md)·[요구·테스트 대응](REVIEW-001/requirements.md)·[정책 대응](REVIEW-001/policies.md)·[문서 검증](REVIEW-001/verification.md) | 문서 작업과 후속 구현 필요 사항 |
| REVIEW-002 | [착수 기준](REVIEW-002/baseline.md)·[최초 실행](REVIEW-002/fresh-start.md)·[최종 검증](REVIEW-002/verification.md)·[요구 재검증](REVIEW-002/requirements-audit.md)·[그룹 집계 수정](REVIEW-002/group-summary-fix.md)·[성능 판단](REVIEW-002/performance.md) | 1차 응답·중계, 2차 JWT·오류·GET 전용·중복 ID, 3차 페이지·JSON, 4차 매핑 기준 집계·미매핑 제외. 로그 최종 제출 보류 구분 |

검증 기록에는 날짜·명령·대상 SHA 또는 작업 폴더 상태·성공/실패/건너뜀·한계를 적는다. 과거 실패·미검증을 나중에 성공한 것처럼 바꾸지 않는다. 변경 후 검증은 새 기록으로 연결한다.
