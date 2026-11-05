# AI 세션 기록 운영

| 항목 | 내용 |
| --- | --- |
| 분류 | 공통 운영 |
| 상태 | 현재 · 구현된 수집기 기준. 실행 검증 범위는 별도 기록 |
| 명령 기준 | 저장소 루트 |


이 저장소는 과제 수행에 쓴 AI 에이전트 세션을 자동으로 수집해 `ai-logs/snapshots/`에 커밋한다. 수집 범위는 입력한 프롬프트, AI 응답, 에이전트가 실행한 명령과 결과, 파일 수정 내역이다.

- 각 도구가 스스로 남기는 원본 트랜스크립트를 사용한다.
- **마스킹(`****` 치환) 외에는 고치지 않고**, 원래 줄 순서 그대로 담는다.
- 수집 대상은 이 저장소에서 실행한 로컬 세션이다. 범위 밖은 아래 "주의 사항과 한계"에 정리했다.

### 사용 도구와 원본 위치

| 도구 | 버전(구성 시점) | 원본 트랜스크립트(도구가 자동 저장) | hook 설정 |
| --- | --- | --- | --- |
| Claude Code | 2.1.318 | `~/.claude/projects/<프로젝트 폴더>/<세션 ID>.jsonl`, 같은 이름 폴더의 `subagents/`, `tool-results/` | `.claude/settings.json` |
| Codex (VS Code 확장, CLI) | CLI 0.166.0 | `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`, `~/.codex/archived_sessions/` | `.codex/hooks.json` |

원본에 담기는 내용

- **Claude Code**
  - 사용자 메시지와 응답
  - `tool_use`: Bash 명령, Edit/Write의 수정 전후 내용
  - `tool_result`: 명령 출력
  - 서브에이전트 대화
- **Codex**
  - 사용자·응답 메시지
  - 명령 실행(`exec_command`), 파일 수정(`apply_patch`)과 그 출력
  - 턴 시작·종료 이벤트
  - 추론(`reasoning`)은 Codex가 암호화한 형태로만 남기며, 그대로 둔다.

### 구성 파일

| 파일 | 역할 |
| --- | --- |
| `.claude/settings.json` | Claude hook 설정(UserPromptSubmit·SessionStart·Stop·SessionEnd), 인증 파일 읽기 금지, AI 턴 중 Git 변경 명령 금지(deny) |
| `.codex/hooks.json` | Codex hook 설정(같은 이벤트, Windows는 `commandWindows`) |
| `scripts/ai_logging/` | 수집기. Python 3.11+ 표준 라이브러리만 사용하며 `python -I`로 실행한다. 상주 프로세스는 없다. |
| `.githooks/commit-msg`, `.githooks/pre-push` | 읽기 전용 Git hook 원본. `install`이 `.git/ai-logging/runtime/`에 복사하고 `core.hooksPath`를 그곳으로 지정한다. |
| `config/ai-logging.json` | 작업 ID 규칙(`service`, `task_prefixes`), main·develop·원격 이름, feature 접두어, 최초 도입 feature |
| `config/ai-logs-allowlist.json` | 비밀값이 아닌 값의 좁은 허용 규칙(사유 포함) |
| `.gitattributes` | `ai-logs/snapshots/** -text -merge`: 개행 변환과 텍스트 병합을 막는다. |
| `.gitignore` | `.ai-logging/`(로컬 상태·치환 목록), `ai-logs/live/`(로컬 확인용 사본) |
| `tests/` | 회귀(a~j)·스냅샷·턴 보호·예약 작업·Git hook·브랜치 흐름 통합 테스트 |

### 동작 개요

```text
도구가 원본 JSONL 기록 ──hook──> python -I scripts/ai_logging/cli.py hook <도구> <이벤트>
  UserPromptSubmit : 턴 보호 잠금 안에서 세션 상태를 active로 기록 (기록 실패 시 프롬프트 차단, 출력 없음)
  SessionStart     : 세션 등록 + live 수집
  Stop             : 이 턴의 Stop임이 확인되면 idle → live 수집 → 예약된 Git 작업 실행
  SessionEnd       : 세션 등록(+Claude는 수집). idle 판단에는 쓰지 않음
```

- **세션 판별**
  - 폴더 이름이 아니라 원본 기록 안의 작업 폴더(`cwd`)로 판별한다.
  - hook이 넘겨준 `transcript_path`·`cwd`, 과거에 발견한 세션, manifest, 커밋된 스냅샷의 합집합을 대상으로 유지한다.
  - 원본이 사라진 대상은 0개 정상으로 처리하지 않고, 실패·경고로 보고한다.
- **live와 스냅샷**
  - `ai-logs/live/`는 hook이 매 턴 갱신하는 로컬 확인용 사본이다(커밋하지 않음).
  - 제출본은 단계 완료 때만 만드는 **불변 스냅샷**이다. 턴마다 커밋하지 않는다.
- **수집 오류**
  - 수집 hook은 오류가 나도 exit 0으로 끝난다. 모델을 다시 실행시키는 출력은 내지 않는다(Codex Stop은 `{}`).
  - 오류는 값 없이 종류·위치만 `.ai-logging/errors.jsonl`에 남는다.

### 제출 로그 구조 (스냅샷)

```text
ai-logs/snapshots/<claude|codex>/<세션 ID>/<정책 버전 12자>/L<원본 바이트 20자리>-<sha256 앞 16자>/
  manifest.json            도구·세션 ID·정책 버전·snapshot_id·snapshot_sha256(전체 64자)·첫/마지막 시각(UTC)·줄 수,
                           파일별 원본/사본 바이트·sha256·마스킹 건수·개행 없는 마지막 기록 여부, 경고(50MB 초과)
  files/<원본 상대 경로>    세션의 모든 파일 전체 사본(마스킹만 적용, 원본과 같은 줄 수·순서·개행)
```

- **ID 규칙**
  - 같은 원본·같은 정책이면 같은 ID가 나온다. 생성 시각 같은 가변 값은 넣지 않는다.
  - 폴더 이름의 해시는 앞 16자만 쓰고, 전체 해시는 manifest에 기록한다.
  - 이미 있는 폴더는 고치지 않는다. 공통으로 고쳐 쓰는 목록 파일은 없다.
- **최신본**
  - 현재 정책 폴더에서 `L` 값(원본 바이트)이 가장 큰 스냅샷이 최신 전체본이다.
  - 길이가 같은데 해시가 다르면 최신으로 추정하지 않고 보류한다.
- **원본 불변성**
  - 파일마다 이전 스냅샷·live·상태 기록과 원본 앞부분을 대조한다.
  - 원본이 잘리거나 다시 쓰이거나 부속 파일이 삭제되면 보류한다.
- **정책 버전**
  - 검출 규칙, 허용 목록 내용, 치환 목록을 반영한다. 치환 목록이 바뀌면 새 정책 폴더에 전체 사본이 다시 생긴다.
- **큰 파일**: 50,000,000바이트를 넘으면 경고만 하고 자르지 않는다.
- **개행 없는 마지막 JSONL 기록**
  - 세션이 idle로 확인되고 그 줄이 JSON으로 파싱될 때만 '종료된 유효 기록'으로 포함한다(`ends_without_newline: true`).
  - 그 밖에는 마지막 개행까지만 담는다. 이 경우 완전성 검사(`completeness`)는 실패한다.
- **시간과 순서**
  - 시간은 원본에 기록된 UTC 그대로다.
  - 순서의 기준은 원본의 줄 순서이며, 시각으로 다시 정렬하지 않는다.

### 마스킹과 검증

- **치환만 한다.** 값 자리를 `****`로 바꿀 뿐 줄·공백·개행·JSON 구조는 그대로다.
- **치환 대상**
  - 치환 목록(`.ai-logging/mask-values.jsonl`, Git 제외): 계정·조직 UUID, 이메일과 그 아이디 부분, Git 사용자 이메일, 검토 후 추가한 값
  - Windows 사용자 이름(홈 폴더 이름, `USERNAME`): 독립된 단어일 때 대소문자 구분 없이 치환한다.
  - 알려진 토큰 형식(Anthropic·OpenAI·GitHub·AWS·Slack·Google 키, JWT, PEM 개인키, Bearer)
- **검출(마스킹보다 넓음)**
  - 대상: 이메일, 토큰 형식, `password=`·`secret:`·`token=` 같은 대입과 플래그 값, 무작위 문자열, 바이너리
  - 일부만 `****`인 값도 다시 검사한다.
  - 무작위 문자열 면제는 키 이름만으로 하지 않는다. 검토한 URL 문맥(호스트·경로·쿼리 조건)으로만 허용한다.
- **보류**
  - 후보가 하나라도 있으면 그 세션은 보류한다(사본·스냅샷 갱신 없음).
  - 보류는 게시를 막지 않는다. 로그 커밋(`push-develop`·`merge-feature`·`submit`·`stage-log`)은 보류 세션만 빼고 진행하고, 결과에 `logs:snapshot_hold <세션> N건` 경고와 커밋 본문의 제외 표시를 남긴다. 해소하면 그 세션의 다음 스냅샷(전체본)이 이어서 쌓인다.
  - JSON 이스케이프(`
` 등)의 글자는 뒤 문자열에 붙여 검사하지 않는다.
  - 진단에는 값 없이 규칙·줄 번호·길이·형태만 남긴다.
  - 보류 해소:
    - `holds`로 형태를 확인한다.
    - 치환할 값은 `mask-held <목록지문> <번호>...`로 치환 목록에 추가한다.
    - 비밀이 아닌 값은 `config/ai-logs-allowlist.json`에 사유와 함께 좁은 규칙으로 추가한다.
- **이미 커밋된 값**
  - 새 마스킹 커밋으로 지울 수 없다.
  - `push-develop`·`merge-feature`·pre-push가 커밋 트리의 모든 스냅샷을 현재 정책으로 다시 검사한다(마스킹 실패는 게시 중단).
  - 발견되면 게시를 멈추고 보고한다. 이력 정리 여부는 사용자가 결정한다.

### 턴 보호와 예약 작업

- **세션별 턴 상태**: `active` / `idle` / `unknown`. `turns` 명령으로 확인한다.
  - Codex Stop은 `turn_id`가 같아야 idle로 바꾼다.
  - Claude Stop은 `last_assistant_message`가 이 턴 시작 이후의 원본에 있어야 idle로 바꾼다. 늦게 온 이전 턴 Stop은 무시한다.
  - 원본 증거로 상태를 보완한다(Claude `turn_duration`·중단 기록, Codex `task_complete`·`turn_aborted`). 단, 이 턴 시작보다 뒤의 증거만 인정한다.
  - SessionEnd·경과 시간·파일 크기 정지로는 idle을 추정하지 않는다.
  - 판단할 수 없으면 `unknown`이다. 비정상 종료 등으로 멈춘 세션은 사람이 확인한 뒤 `turns resolve <tool/sid> idle`로 지정한다(사용자 실행).
- **Git 변경 차단**: 어느 세션이든 active/unknown이면 막는다.
  - commit-msg hook이 커밋을 막는다.
  - 자동화 명령도 실행하지 않는다.
  - 프롬프트 시작과 자동화 Git 작업은 같은 잠금(`.ai-logging/guard.lock`)을 쓴다.
- **AI 턴 중의 Git 요청은 예약한다.** 턴이 끝나 모든 세션이 idle이 된 Stop 시점에 순서대로 실행한다.
  - 예: `task reserve commit --message-file F -- <경로>...`, `task reserve stage-log SETUP-001 1단계`
  - 예약할 때와 실행할 때 모두, feature 브랜치와 작업 ID가 다르면 거부한다(`task_branch_mismatch`).
  - Claude는 `.claude/settings.json` deny 규칙으로 `git commit/merge/push/reset/checkout/switch/add/...` 실행이 막혀 있다.
  - 실행 조건: 스테이징된 변경 없음, 진행 중인 Git 작업 없음, 예약 때와 같은 브랜치·HEAD.
  - 실패하면 뒤 작업을 멈추고 `task list`에 사유를 남긴다. 재시도는 `task retry`, 취소는 `task cancel`.
  - 자동 stash·강제 checkout/reset·작업 폴더 정리는 하지 않는다.
  - 커밋은 임시 index와 비교 후 교체(CAS)로 만들어 사용자 index의 다른 항목을 바꾸지 않는다.

### 브랜치 흐름

```text
master ──new-feature──> feature/<ID> ──(사용자 git merge)──> develop 검토·검증
   ^                        ^   │                                │
   │                        │   └─ resume: 최신 master(로그만)를 feature에 일반 merge 후 보완
   │                        │                                    │
   ├── push-develop: develop 로그 전용 커밋만 master에 cherry-pick -x + atomic push
   └── merge-feature: 검증된 feature를 로컬 master에 --no-ff 병합 → 사용자가 git push origin master
```

1. **신규 작업**: `new-feature <ID>`
   - master에서 `feature/<ID>`를 만든다.
   - master에 로깅 설정이 있어야 하고, 병합되지 않은 다른 feature가 없어야 한다(동시에 하나).
2. **develop 반영**: 구현·테스트 뒤 사용자가 feature를 develop에 `git merge`한다(commit-msg가 형식·턴 상태 검사).
   - develop에서는 기능 검토·검증만 한다. 검증은 자동으로 하지 않는다.
3. **develop push**: `push-develop <feature>` (사용자 실행). develop은 push할 때만 동기화한다.
   - develop에 전체 스냅샷 로그 커밋을 만든다. 검토 대화와 실패 기록도 보존한다.
   - develop에서 도달 가능한 모든 부모 경로의 로그 전용 커밋 중 master에 없는 스냅샷만 고른다. feature에서 만들어져 develop 병합의 두 번째 부모로 들어온 로그도 포함한다. 부모 하나·스냅샷 추가 전용 조건을 통과한 커밋만 선택하며 기능 코드는 옮기지 않는다.
   - 고른 커밋을 master 위에 `cherry-pick -x`와 같은 커밋으로 만든다(원 작성자·메시지, `(cherry picked from commit …)`).
   - 작업 폴더를 전환하지 않는다.
   - master의 로그 밖 트리가 그대로인지, 로그가 원본 끝까지 담겼는지, 커밋된 비밀값이 없는지 확인한다.
   - 확인이 끝나면 `git push --atomic origin develop master` 후 원격 ref를 확인한다.
   - 같은 스냅샷은 다시 적용하지 않는다. 실패하면 같은 명령으로 이어서 진행한다(`.ai-logging/runs/`).
4. **보완**: `resume <feature>`
   - 최신 master를 feature에 일반 merge한다. master의 새 변경이 로그 전용일 때만 진행한다.
   - 보완한 뒤 다시 develop에 병합하고 `push-develop`을 실행한다.
5. **최종 병합**: `merge-feature <feature>` (사용자 실행)
   - feature의 마지막 코드 커밋이 develop에 들어 있는지 확인한다(develop 최종 테스트를 거친 코드만 master로 간다. 테스트 통과 자체는 자동 검증하지 않는다).
   - 꼬리 로그 스냅샷 커밋을 만든다.
   - 로컬 master에 `--no-ff` 병합한다.
   - 코드(feature와 같은 로그 밖 트리)와 커밋된 비밀값을 검증한다. **push는 하지 않는다.**
   - git 명령으로 직접 병합해도 된다: `submit`(꼬리 스냅샷) → `git switch master` → `git merge --no-ff -m "[작업ID] 제목" -m "  내용" <feature>`. 단 develop 포함 확인은 `merge-feature`만 한다.
6. **master push**: 사용자가 `git push origin master`(또는 IDE push)를 실행한다. pre-push가 보내는 커밋을 읽기 전용으로 검사한다.
   - **막는 것**(쌓인 이력을 깨거나 게시하면 안 되는 경우만)
     - 기존 스냅샷 수정·삭제, 금지 경로(`ai-logs/live/`, `.ai-logging/` 등)
     - 커밋 메시지 형식
     - 커밋된 비밀값(마스킹 실패), master 삭제
   - **경고만 하고 push는 진행**
     - 스냅샷이 원본보다 뒤처짐·없음: 담기지 않은 대화는 다음 스냅샷 커밋·push에 이어서 쌓인다.
     - 민감정보 후보로 보류된 세션: 그 세션은 커밋되지 않고, 해소 뒤 이어서 쌓인다.
     - 병합 커밋 없이 master에 들어온 소스 커밋(fast-forward, `master_direct_source_commit`)
     - AI 턴 진행 중
   - 출력은 한국어 UTF-8이다(IDE Git 콘솔에서 읽을 수 있음).

**금지 사항**

- develop 전체를 master에 병합하지 않는다.
- `develop:master`로 push하지 않는다.
- GitHub PR 등 로컬 명령 밖에서 master에 병합하지 않는다.
- master 병합은 `--no-ff`를 권장한다(IDE Merge 대화상자에서도 `--no-ff` 옵션 선택). fast-forward도 push는 되지만 병합 커밋이 남지 않아 feature 단위 구분이 어려워진다.
- force push, `--no-verify`, amend를 쓰지 않는다.

#### 최초 도입(LOG-001) 절차

도입 전 master(`26b4ccd`)에는 로깅 설정이 없다. 그래서 `new-feature`와 `push-develop`은 거부되고, 아래 절차가 유일한 예외다.

1. `feature/LOG-001`에서 구성하고 feature에서 검증한다.
2. **develop 검증은 생략한다.** 도입 전에는 develop 검증 절차 자체가 없기 때문이다.
   - `merge-feature`는 원격 master에 로깅 설정이 없고 feature가 `feature/LOG-001`일 때만 develop 확인을 건너뛴다.
   - 병합 커밋 본문과 결과(`develop_check: skipped_bootstrap`)에 "develop 검증 생략(최초 도입: master에 검증 절차 없음)"을 남긴다.
   - develop에 병합해 두었다면 일반 확인(`included`)을 한다.
3. `merge-feature feature/LOG-001`(또는 `submit` 후 `git merge --no-ff`)로 꼬리 로그를 포함해 로컬 master에 병합한다.
4. `git push origin master`를 실행한다. 병합 뒤 대화가 더 있었다면 경고만 나오고, 그 대화는 다음 push에 이어서 담긴다.
5. 이후 master에 설정이 생기면 일반 흐름을 쓴다. 일반 feature의 `merge-feature`는 develop 확인을 생략할 수 없다(`feature_code_not_in_develop`).

### 설치

```powershell
python -I scripts/ai_logging/cli.py install   # 치환 목록(값 출력 없음)·로컬 제외·Git hook 런타임 설치·실행 확인·첫 수집
python -I scripts/ai_logging/cli.py status    # hook 설정·실행 기록·Git hook·턴 상태·예약 작업·수집 결과
```

- **치환 목록 확정**
  - 원본과 Git 설정에서 계정 UUID·이메일·사용자 이름을 찾아 `.ai-logging/mask-values.jsonl`에 합집합으로 저장한다. 기존 항목은 지우지 않는다.
- **로컬 제외**
  - `.git/info/exclude`에 `/ai-logs/live/`, `/ai-logs/snapshots/`, `/.ai-logging/`을 등록한다.
  - 로깅 설정이 없는 브랜치로 바꿔도 치환 목록이 `git add -A`에 섞이지 않는다.
- **Git hook 런타임**
  - hook 원본과 수집기 사본을 `.git/ai-logging/runtime/`에 두고 `core.hooksPath`를 지정한다.
  - `git hook run`으로 실제 실행을 확인한다: 잘못된 메시지 거부, pre-push 실행, master 삭제 거부.
  - 브랜치를 바꿔도 hook이 사라지지 않는다. 상태(`.ai-logging/`)는 브랜치와 무관하다.
- **Claude Code**: `.claude/settings.json`을 자동으로 읽는다(`/hooks`에서 확인). UserPromptSubmit은 `"onFailure": "block"`(v2.1.295+)으로 실패 시 프롬프트를 막는다.
- **Codex**
  - hook을 사람이 신뢰해야 실행된다: CLI `/hooks`, VS Code 확장은 Settings > Hooks.
  - `.codex/hooks.json`이 바뀌면 다시 검토한다.
  - `status`의 `trusted_hash` 기록만으로 신뢰·실행을 판단하지 않는다. "Codex hook 실제 실행(설정 변경 이후)" 항목으로 확인한다.

### 명령 요약 (`python -I scripts/ai_logging/cli.py <명령>`)

| 명령 | 설명 |
| --- | --- |
| `install`, `status` | 설치·상태 |
| `sync`, `verify [--final]` | live 수집 / live 읽기 전용 검사(`--final`: 원본 끝까지·누락 세션·manifest 밖 파일까지 실패) |
| `snapshot`, `completeness [REV]`, `live-covered` | 스냅샷 생성(커밋 안 함) / 원본 끝까지 담았는지 / live ⊆ snapshot |
| `submit [작업ID]` | 모든 AI 응답이 끝난 뒤 현재 브랜치에 로그 전용 커밋(일반 터미널) |
| `turns`, `turns resolve` | 턴 상태 / 멈춘 세션 수동 지정 |
| `task reserve/list/run/cancel/retry` | AI 턴 중 Git 작업 예약·관리 |
| `new-feature`, `push-develop`, `resume`, `merge-feature` | 브랜치 흐름 |
| `holds`, `mask-held`, `repair-live`, `index` | 보류 확인·치환 / 손상 manifest 보관 후 재수집 / 세션 목록 |

테스트: `python -I -m unittest discover -s tests -v`

### 검증 기록

과거 실행 증거는 [LOG-001 검증 기록](../verification/LOG-001/ai-logging-verification.md)에 보존한다. 현재 이슈는 [REVIEW-001 검토](../verification/REVIEW-001/findings.md)를 확인한다.

### 증분 검증과 진행 표시

- `.ai-logging/verification-cache/validation.sqlite3`에 통과한 검사 기록을 저장한다. Git 제외 대상이며 원문·개인정보·검출값은 저장하지 않는다.
- 스냅샷 blob은 Git 객체 ID, 줄 검사는 SHA-256을 키로 사용한다. 치환 목록·허용 목록·검사 코드의 해시가 같은 경우에만 결과를 재사용한다. 변경 시 새 기준으로 다시 검사한다.
- 같은 파일이 여러 스냅샷에 반복돼도 한 번 검사한다. 새 전체본에서는 이미 통과한 동일 줄을 재사용하고, 추가·수정된 줄은 검사한다. 검출된 문제는 통과 캐시에 기록하지 않는다.
- 원본 앞부분의 불변성·절단·누락, 최신 원본과 스냅샷의 완전성, manifest·사본 해시는 계속 검증한다. 앞부분 해시는 원본을 한 번 순회하면서 이전 검증 길이마다 비교한다. 전체 사본 저장 정책은 유지한다.
- 치환값 잔류 검사는 줄 경계를 가로지를 수 있으므로 전체 바이트를 검사한다. 파일 전체의 통과 기록은 동일 blob·동일 정책일 때만 재사용한다.
- 캐시 손상·잠금·접근 오류가 나면 해당 검사를 전수 검사한다. 캐시는 삭제해도 재생성되는 로컬 성능 자료이며 외부에서 받은 캐시를 신뢰하거나 공유하지 않는다. 악의적인 로컬 캐시 위조를 막는 서명 장치는 아니다.
- Git hook의 읽기 전용은 원본·스냅샷·index·ref를 바꾸지 않는다는 의미다. 통과 해시를 Git 제외 캐시에 기록할 수 있으며 커밋 중 sync는 실행하지 않는다.
- `push-develop`은 원격 확인·스냅샷·로그 동기화·완전성 검사·비밀 검사·push 단계를 표시한다. 오래 걸리는 단계는 15초마다 경과 시간을 표시한다. 출력은 값 없이 단계·건수·시간만 stderr로 보낸다.
- Git blob은 합계 32MiB 이내 배치로 읽는다. 큰 파일 하나는 단독 배치로 읽으므로 메모리 상한이 파일 하나보다 작다는 보장은 없다.
- 최초 검사와 정책 변경 직후에는 전체 검사가 필요하다. 원본 읽기·해시·마스킹·Git 전송 시간은 남는다. [개선 검증 기록](../verification/REVIEW-001/logging-performance.md)은 비밀 검사 구간과 전체 게시 시간을 구분한다.

### 주의 사항과 한계

- **세션 저장을 끄는 실행 방식 금지**: `codex exec --ephemeral`, `claude -p --no-session-persistence`, `--dangerously-bypass-hook-trust`.
  - 원본 세션 파일 삭제·이동, `ai-logs/` 수동 편집, `git clean -fdx`·`-fdX`도 금지한다.
- **클라우드·웹 세션은 로컬 원본이 없어 수집되지 않는다**(claude.ai 웹, Codex Cloud 작업 등). 이 과제에는 쓰지 않는다.
  - 부득이 썼다면 해당 서비스의 대화 내보내기 기능으로 원문을 받는다.
  - 받은 원문은 `ai-logs/`가 아닌 별도 경로에 두고, 커밋 전에 사람이 마스킹을 검토한다. 이 수집기의 자동 검증 대상이 아니다.
- **마지막 push 이후의 대화**(그 push를 지시한 응답 등)는 그 push에 담길 수 없다. 다음 게시나 병합 때 포함된다.
  - 단계 로그 커밋은 Stop hook 안에서 만들어진다. 그래서 그 턴의 종료 기록(`turn_duration` 등)은 다음 스냅샷에 들어간다.
- **도구가 원본 형식을 바꾸면** 턴 판정과 세션 판별이 `unknown`·보류로 바뀔 수 있다(안전한 쪽으로 멈춤).
- **"누락이 절대 없다"고 보장하지 않는다.** 검사 결과(`completeness`, pre-push 경고)로 확인된 범위만 완전하다고 본다. push 시점에 뒤처진 대화는 다음 스냅샷에 담긴다.
- 공개 GitHub 저장소 계정명(`collenkim`)은 저장소 주소 자체이므로 마스킹하지 않는다.

### 커밋·브랜치 규칙

`AGENTS.md` 참고. 모든 일반·로그·병합 커밋은 `[작업ID] 제목` + 빈 줄 + 두 칸 들여쓴 작업 내용 형식을 따른다. master 병합·push는 사용자가 직접 한다.

- **작업 ID**: feature 브랜치 이름에서 정한다(`config/ai-logging.json`).

  | 브랜치 | 작업 ID | 근거 |
  | --- | --- | --- |
  | `feature/SETUP-001` | `SETUP-001` | `service`가 비어 있으면 feature ID 그대로 |
  | `feature/LOG-xxx` | `AI-LOG-xxx` | `task_prefixes`의 `"LOG-": "AI"` (AI 로깅 도구 작업) |

- 직접 커밋, 예약 커밋, 로그 스냅샷 커밋(`stage-log`·`submit`·꼬리 로그), `resume`·`merge-feature`·`push-develop`이 만드는 커밋 모두 같은 작업 ID를 쓴다.
- feature 브랜치에서 다른 작업 ID로 커밋하면 commit-msg hook·예약 작업·로그 커밋이 거부한다(`task_branch_mismatch`). develop·master는 여러 feature의 병합이 들어오므로 검사하지 않는다.
- 이미 기록된 이력(`[AI-LOG-001]`)은 그대로 둔다.
