@AGENTS.md

## Claude Code 참고

- 세션 hook은 `.claude/settings.json`에 있다: UserPromptSubmit·SessionStart·Stop·SessionEnd → `scripts/ai_logging/cli.py hook claude <이벤트>`.
  - UserPromptSubmit은 턴 보호 상태를 기록하고, 실패하면 프롬프트를 막는다(`onFailure: block`).
  - Stop은 수집 후 예약된 Git 작업을 실행한다.
  - hook을 끄거나 고치지 않는다.
- 같은 파일의 `permissions.deny`가 막는 것:
  - 인증 정보·치환 목록 읽기
  - AI 턴 중 Git 변경 명령
  - 우회하지 않는다(`git -C` 등 다른 형태로 같은 일을 하지 않는다). Git 작업은 `task reserve`로 예약한다.
- 커밋 끝에는 `Co-Authored-By` 공동 작성자 줄을 붙인다(예약 커밋 메시지 파일에 포함).
