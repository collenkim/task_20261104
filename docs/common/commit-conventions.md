# 커밋·브랜치

| 항목 | 내용 |
| --- | --- |
| 분류 | 공통 기준 |
| 상태 | 현재 · 적용 예정 사항은 해당 절에서 구분 |
| 목적 | 커밋 메시지와 브랜치 흐름을 요약한다 |
| 범위 | 이 저장소의 모든 커밋(일반·로그·병합) |
| 관련 코드 | `AGENTS.md`, `config/ai-logging.json`, `.githooks/commit-msg` |

기준 문서는 [에이전트 규칙](../../AGENTS.md)과 [AI 로깅 운영](ai-logging.md)이다. 이 문서는 메시지·브랜치 규칙의 요약이며, 턴 보호와 예약 명령은 운영 문서를 따른다.

## 메시지 형식

```text
[작업ID] 제목

  작업 내용
```

- 본문은 두 칸 들여쓴다. 일반·로그·병합 커밋 모두 같은 형식이고 commit-msg hook이 검사한다.
- 작업 ID는 feature 브랜치에서 정해진다.
  - `feature/SETUP-001` → `[SETUP-001]`
  - `feature/LOG-xxx` → `[AI-LOG-xxx]` (AI 로깅 도구 작업만)
- feature 브랜치에서는 그 브랜치의 작업 ID로만 커밋한다(`task_branch_mismatch`).
- amend·`--no-verify`·서명 우회·force push를 쓰지 않는다.
- AI 에이전트가 만든 커밋은 끝에 `Co-Authored-By` 줄을 붙인다.

## 커밋 단위

- 한 커밋은 한 단계·한 관심사만 담는다. 예: 계획 문서, 공통 문서, 빌드 구성, 서비스별 골격.
- 커밋 전에 `./gradlew build`(포맷 검사·테스트)를 통과시킨다.
- `ai-logs/`는 직접 스테이징하지 않는다. 로그 커밋은 수집기 명령만 만든다.

## 브랜치 흐름

```text
master ──new-feature──> feature/<ID> ──(merge)──> develop 검토·검증
                                       push-develop: develop 로그 전용 커밋만 master에 동기화
                                       merge-feature: 검증된 feature를 master에 --no-ff 병합 → git push origin master
```

- 동시에 진행하는 feature는 하나다.
- develop 전체를 master에 병합하지 않는다. GitHub PR로 master에 병합하지 않는다.
- master 병합·push, `push-develop`, `merge-feature`는 사용자가 실행한다.
