# LOG-001 검증 기록

| 항목 | 내용 |
| --- | --- |
| 분류 | 검증 기록 |
| 상태 | 이력 · 2026-11-04 기록을 README에서 이동 |
| 현재 운영 | [AI 로깅 운영](../../common/ai-logging.md) |

### 검증 기록 (AI-LOG-001, 2026-11-04)

**실제 환경에서 확인함** (Windows 11, Git 2.55, Python 3.12, 이 저장소, 실제 Claude·Codex 원본)

- `install`
  - 이 저장소에 hook 런타임을 설치했다.
  - `git hook run`으로 확인: commit-msg 잘못된 메시지 거부, pre-push 실행, master 삭제 입력 거부.
- 실제 두 세션(Claude 1, Codex 1)
  - live 수집과 검출 결과를 검토했다.
  - 오탐 2종은 허용 목록에 사유와 함께 추가했다: 검사 결과 이름, Claude `tool-results` 경로의 도구 호출 ID.
  - 보류 0건이 됐다.
- 실제 두 세션의 스냅샷을 만들고 `live-covered`로 live ⊆ snapshot(파일 11개)을 확인했다. 이후 `ai-logs/live/`를 `.gitignore`로 전환했다.
- 실제 원본의 턴 종료 증거를 판정했다.
  - Codex 세션: `task_complete`로 idle
  - 진행 중인 Claude 턴: 열린 턴으로 판정
- 기존 Claude SessionStart·Stop hook 실행 기록(`.ai-logging/registry.jsonl`)을 확인했다.
- 실제 pre-push 차단 사례: IDE fast-forward 병합 후, 그리고 `git merge --no-ff` 후 GitHub push가 `snapshot_behind_original`로 막혔다(원격 master 변경 없음).
  - 이때 한글 안내가 깨졌다(Python이 파이프에 CP949로 출력). 수집기 출력(stdout·stderr)을 UTF-8로 고정했다.
  - 스냅샷은 계속 쌓이는 구조이므로 뒤처짐으로 push를 막는 것은 설계 오류로 판단해 경고로 바꿨다.
  - 실제 저장소 master(`e2f753e`)로 pre-push 검사를 읽기 전용으로 돌려 막는 문제 0건(경고 3건)을 확인했다.
- 작업 ID 매핑: 이 저장소 설정으로 `feature/LOG-001` → `AI-LOG-001`, `SETUP-001` → `SETUP-001`, 다른 브랜치 ID 거부, develop 제외를 확인했다.

**가짜 원본·임시 저장소로만 확인함** (`tests/`, 90개 통과)

- 회귀 a~j(13개): 수정 전 실패(11 failures, 2 errors), 수정 후 통과
- 스냅샷 규칙
  - 결정적 ID, 정책 폴더 분리
  - 같은 길이 재작성 보류, 꼬리 처리
  - 50MB 경고, 부속 파일 삭제 보류
  - autocrlf에서 CRLF 바이트 보존
  - 보류 세션만 빼고 로그 커밋(본문에 제외 표시, 값 미포함), 보류만 남으면 실패가 아니라 커밋할 것 없음
  - `\n` 뒤 스냅샷 부속 파일 경로는 허용, 다른 위치는 후보 유지, 역슬래시(`\`) 뒤 `n`은 실제 글자로 검사
- 턴 보호
  - 늦은 Codex Stop, Claude Stop 일치 확인
  - 중단 기록, SessionEnd 무시
  - 손상 상태·잠금 대기에서 CLI가 exit 2로 프롬프트 차단, Stop 오류 시 exit 0
- 예약 작업
  - idle 대기, 순서 실행, squash 백업 ref
  - 스테이징 변경 보존, HEAD 변경 거부
- 임시 저장소의 실제 Git hook
  - 형식·턴·금지 경로 검사
  - 읽기 전용 확인, 수집기 없는 브랜치에서도 hook 실행
  - pre-push: 스냅샷 없음은 경고로 통과, 커밋된 비밀값은 거부
- 로컬 bare 원격으로 전체 흐름
  - master → feature → develop(검토 실패) → push-develop → resume → 보완 → develop → push-develop(중복 제외) → merge-feature → master push
  - atomic push 거부 후 이어서 진행
  - master 로그 밖 변경 시 resume 거부
  - 다른 feature 미병합, 최초 도입 예외
  - 최초 도입: develop 병합 없이 `merge-feature` 성공(생략 문구 기록, 경고 없이 push)
  - `feature/SETUP-001` 전체 흐름(직접 커밋 → develop → push-develop → merge-feature → push)의 모든 커밋이 `[SETUP-001]`, `[AI-SETUP-001]`·`[AI-LOG-001]`은 commit-msg·예약·로그 커밋에서 거부
  - fast-forward·스냅샷 뒤 대화가 있어도 push 진행(UTF-8 경고), 다음 스냅샷이 더 긴 전체본으로 추가되고 기존 스냅샷은 유지, `git merge --no-ff` 후 push 통과, 형식이 틀린 병합 메시지는 거부
  - 한 세션이 보류여도 `push-develop` 진행(`logs:snapshot_hold` 경고, 그 세션 제외), 치환 목록 추가 뒤 다음 `push-develop`에 그 세션이 이어서 반영, 커밋 트리에 비밀값 없음

**확인하지 못함** (사용자 확인 필요)

- Codex UserPromptSubmit hook의 실제 실행과 신뢰 등록. 이번 추가분이라 Settings > Hooks에서 검토해야 한다.
- Codex hook이 실행 자체에 실패할 때 프롬프트가 막히는지(fail-closed 여부는 문서에 없다).
- Claude 안에서 UserPromptSubmit hook이 시간 초과·비정상 종료될 때 `onFailure: block`이 동작하는지. exit 2 경로만 하위 프로세스로 시험했다.
- GitHub 원격의 `--atomic` push. 로컬 bare 저장소로만 시험했다.
- 실제 저장소에서의 develop 병합·`push-develop`. 이번 작업 범위에서 제외했다.
- 실제 저장소에서의 `merge-feature`·master push 성공. 사용자가 최초 도입 절차로 실행한다.
- PowerShell 도구용 deny 규칙(`PowerShell(git commit *)`)이 실제로 막는지.
- `git -C <경로> <명령>` 같은 다른 형태는 deny 규칙이 모두 막지 못할 수 있다. 최종 방어는 commit-msg·pre-push hook이다.

