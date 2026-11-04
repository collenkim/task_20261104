# 에이전트 공통 규칙 (Codex, Claude Code)

## 커밋 메시지

```text
[작업ID] 제목

  작업 내용
```

- 본문은 두 칸 들여쓰기. 예: `[SETUP-001] 프로젝트 구성`.
- 작업 ID는 feature 브랜치에서 정해진다(`config/ai-logging.json`).
  - `feature/SETUP-001` → `[SETUP-001]` (일반 작업은 ID 그대로)
  - `feature/LOG-xxx` → `[AI-LOG-xxx]` (AI 로깅 도구 작업만 `AI-` 접두어)
- feature 브랜치에서는 그 브랜치의 작업 ID로만 커밋한다. 다른 ID는 commit-msg hook·예약 작업이 거부한다(`task_branch_mismatch`).
- 일반·로그·병합 커밋 모두 같은 형식이다. commit-msg hook이 검사한다.
- amend·`--no-verify`·서명 우회는 쓰지 않는다.

## 브랜치

- 동시에 진행하는 feature는 하나다. 신규 작업은 master에서 `feature/작업ID`를 만든다(`new-feature`).
- 흐름: feature에서 구현·테스트 → develop에 병합 → develop에서 검토·검증.
- 보완: `resume`으로 최신 master를 같은 feature에 일반 merge한 뒤 수정하고, develop에 다시 병합한다.
- develop의 로그 전용 커밋만 master에 동기화한다(`push-develop`).
- 검증된 feature만 master에 `--no-ff` 병합한다(`merge-feature`). staging 브랜치는 없다.
- 하지 않는 것:
  - develop 전체를 master에 병합
  - `develop:master` push
  - GitHub PR로 master 병합
  - force push

## 에이전트의 Git 사용 (README "턴 보호와 예약 작업")

- AI 턴이 진행 중이면 Git 변경(커밋·병합·push·checkout·reset·add 등)을 직접 실행하지 않는다.
  - Claude는 deny 규칙으로 막혀 있다.
  - Codex는 commit-msg hook이 막는다.
- 필요한 Git 작업은 예약한다. 턴이 끝나 모든 세션이 idle이 되면 Stop hook이 실행한다.
  - `python -I scripts/ai_logging/cli.py task reserve commit --message-file <파일> -- <경로>...`
  - `python -I scripts/ai_logging/cli.py task reserve stage-log <작업ID> <단계 이름>` (단계 완료 때만, 턴마다 하지 않음)
- 다음은 사용자가 직접 실행한다. 에이전트는 사용자 요청 없이 실행하거나 예약하지 않는다.
  - master 병합·push
  - `push-develop`, `merge-feature`
  - `turns resolve`
- 자동 stash·강제 checkout/reset·사용자 변경 정리·이력 재작성은 사용자 승인 없이 하지 않는다.

## AI 세션 로그 보존 (README "AI 세션 기록")

- 도구 원본 세션 파일(`~/.claude/projects/`, `~/.codex/sessions/`, `~/.codex/archived_sessions/`)을 수정·삭제·이동하지 않는다.
- `ai-logs/`를 직접 편집하거나 스테이징하지 않는다. 스냅샷 로그 커밋은 수집기 명령만 만든다.
- 세션 저장을 끄는 옵션 금지: `codex exec --ephemeral`, `claude -p --no-session-persistence`, `--dangerously-bypass-hook-trust`.
- 로그를 지울 수 있는 명령 금지: `git clean -fdx`·`-fdX`, `ai-logs/`에 대한 강제 checkout·reset·stash.
- 클라우드·웹 세션(claude.ai 웹, Codex Cloud)은 이 과제에 쓰지 않는다(로컬 원본이 없어 수집되지 않음).
- 상태 확인: `python -I scripts/ai_logging/cli.py status`. 보류가 있으면 `holds`로 형태만 확인한다.

## 민감정보

- 비밀값·개인정보를 명령줄 인수, 출력, 응답에 쓰지 않는다. 진단은 규칙·줄 번호·길이·형태만 출력한다.
- 읽지 않는 파일: `.ai-logging/mask-values.jsonl`, `~/.codex/auth.json`, `~/.claude/.credentials.json`, `~/.claude.json`, `.env`, `.env.*`, `secrets/`(로컬 키·토큰·DB 비밀번호).
  - `secrets/`는 `./gradlew generateLocalSecrets`가 만든다. 존재·파일 이름만 확인하고 내용은 열지 않는다(`docs/common/security.md`).
- 테스트의 가짜 비밀값은 실행 중에 조각을 이어 붙여 만든다(완성된 형태를 소스에 쓰지 않는다).
