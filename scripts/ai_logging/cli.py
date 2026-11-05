#!/usr/bin/env python3
"""AI 세션 로그 수집기 명령줄 (AI-LOG-001).

사용법 (저장소 루트에서, python -I scripts/ai_logging/cli.py <명령>):
  수집·검증
    install                 치환 목록(값 출력 없음)·로컬 제외·Git hook 런타임 설치 후 수집
    sync                    두 도구 원본 → 마스킹·검증 → ai-logs/live/ (로컬 확인용, 커밋 안 함)
    verify [--final]        live 사본 검사(읽기 전용). --final: 원본 끝까지·누락 세션까지 실패 처리
    status                  수집·턴·hook·예약 작업·제출 완전성 요약
    index                   세션을 첫 시각(UTC) 순으로 나열
    holds / mask-held <목록지문> <번호>...   보류 후보(형태만) / 고른 값 치환 목록 추가
    repair-live <tool/sid>  손상된 live manifest를 보관 이름으로 옮겨 다음 수집이 다시 쓰게 함(사용자 실행)
  스냅샷·제출
    snapshot                전체 세션 불변 스냅샷 생성(커밋 안 함)
    completeness [REV]      REV(기본: 작업 폴더) 스냅샷이 원본 끝까지 담았는지 검사
    live-covered            live 사본 ⊆ 스냅샷 확인(live 제외 정책 전환 전)
    submit [작업ID]          모든 AI 응답이 끝난 뒤: 전체 스냅샷 → 현재 브랜치에 로그 전용 커밋
  턴 보호·예약 작업
    turns                   세션별 턴 상태(active/idle/unknown)와 근거
    turns resolve <tool/sid> <idle|unknown>   멈춘 세션을 사람이 확인한 뒤 지정(사용자 실행)
    task reserve commit --message-file F -- <경로>...
    task reserve squash --onto <REV> --message-file F
    task reserve stage-log <작업ID> [단계 이름]
    task reserve <push-develop|resume|merge-feature> <feature>
    task list | task run | task cancel <ID> | task retry <ID>
  브랜치 흐름 (사용자 실행)
    new-feature <ID>        master에서 feature/<ID> 생성(로깅 설정 확인, 동시 feature 1개)
    push-develop <feature> [--no-push]   develop 로그 커밋 → 로그 전용 커밋만 master 반영 → atomic push
    resume <feature>        최신 master(로그 전용 변경)를 feature에 일반 병합
    merge-feature <feature> 꼬리 로그 커밋 → feature를 로컬 master에 --no-ff 병합·검증(push 안 함)
  hook 진입점 (설정에서만 호출)
    hook <claude|codex> <Event>
    githook commit-msg <파일> | githook pre-push <remote> <url>

종료 코드: 0 정상, 1 보류·실패, 2 사용법 오류. hook 명령은 항상 0(UserPromptSubmit 보호 실패만 2).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from ailog import engine  # noqa: E402
from ailog.common import default_context  # noqa: E402


def _utf8_stdout():
    """파이프·파일로 나가는 출력은 UTF-8로 고정한다.

    Windows의 Python은 파이프 출력에 시스템 코드 페이지(CP949)를 쓴다. 그러면 Git·IDE·Claude/Codex처럼
    UTF-8로 읽는 쪽에서 한글이 깨진다(Git hook·프롬프트 차단 사유 포함). 콘솔(tty)은 Python이 유니코드로
    직접 쓰므로 그대로 둔다.
    """
    for stream in (sys.stdout, sys.stderr):
        try:
            if stream is not None and not stream.isatty():
                stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError, OSError):
            pass


def _print_problems(items, limit=30):
    for p in items[:limit]:
        loc = p.get("file", "")
        if "line" in p:
            loc += f":{p['line']}"
        print(f"    - [{p['rule']}] {p.get('session', '')} {loc} {p.get('detail', '')}".rstrip())
    if len(items) > limit:
        print(f"    ... 외 {len(items) - limit}건")


def _print_sync(r):
    print(f"수집 결과: {r['result']}  (정책 {r['policy_version']}, {r['time']})")
    for s in r["sessions"]:
        if s.get("status") == "original_missing":
            print(f"  {s['key']}: 원본을 찾을 수 없음 (사본 보존, 제출 보류 대상)")
            continue
        extra = ""
        if s.get("status") == "hold":
            extra = f" 보류 {s.get('problems')}건, 미반영 {s.get('pending_bytes')} bytes"
        else:
            extra = f" {s.get('lines')}줄 {s.get('original_bytes')} bytes, {s.get('first_timestamp')} ~ {s.get('last_timestamp')}"
        print(f"  {s['key']}: {s['status']}{extra}")
    if r["holds_total"]:
        print(f"  보류 항목 {r['holds_total']}건 (값은 표시하지 않음):")
        _print_problems(r["holds"])
    for e in r["errors"]:
        print(f"  오류: {e}")
    if r.get("unresolved_codex_files"):
        print(f"  참고: 프로젝트 여부를 판정하지 못한 Codex 파일 {r['unresolved_codex_files']}개(다른 프로젝트일 수 있음)")


def _print_commit_result(r, label):
    if r.get("snapshot"):
        _print_snapshot(r["snapshot"])
    if not r.get("ok"):
        print(f"{label} 중단: {r.get('reason')} {r.get('detail', '') or ''}".rstrip())
        for k, v in sorted((r.get("sessions") or {}).items()):
            print(f"  {k}: {v}")
        return 1
    if r.get("committed"):
        print(f"{label} 완료: {r['commit'][:12]} ({r.get('branch')}, 스냅샷 {r.get('snapshots')}개, 파일 {r.get('files')}개)")
    elif r.get("held"):
        print("새로 커밋할 스냅샷 없음: 보류가 아닌 세션은 이미 최신입니다.")
    else:
        print("변경 없음: 이미 최신 스냅샷이 커밋되어 있습니다.")
    for k, n in sorted((r.get("held") or {}).items()):
        print(f"  보류로 제외: {k} {n}건 (값은 커밋하지 않음)")
    if r.get("held"):
        print("  해소: python -I scripts/ai_logging/cli.py holds → mask-held 또는 허용 목록. 해소 뒤 다음 커밋에 이어서 쌓입니다.")
    return 0


def _print_snapshot(r):
    print(f"스냅샷: {r['result']} (정책 {r.get('policy_version')})")
    for s in r.get("sessions", []):
        extra = f" 꼬리 보류 {s['deferred']}" if s.get("deferred") else ""
        print(f"  {s['key']}: {s['status']} {s['base'].rstrip('/').split('/')[-1]} {s.get('main_lines')}줄{extra}")
    for m in r.get("missing", []):
        print(f"  {m['session']}: 원본 없음 (이전 스냅샷 {'있음' if m['snapshot_kept'] else '없음'})")
    if r.get("holds"):
        print(f"  보류 {len(r['holds'])}건 (값은 표시하지 않음):")
        _print_problems(r["holds"])
    for w in r.get("warnings", []):
        print(f"  경고: {w['rule']} {w.get('session', '')} {w.get('file', '')} {w.get('detail', '')}".rstrip())


def _print_flow(cmd, r):
    if r.get("ok"):
        print(f"{cmd} 완료: " + ", ".join(f"{k}={v}" for k, v in r.items() if k not in ("ok", "completeness", "warnings")))
        for w in r.get("warnings") or []:
            print(f"  참고: {w}")
        if any(w.startswith("logs:") for w in r.get("warnings") or []):
            print("  담기지 않은 대화는 다음 스냅샷 커밋·push에 이어서 쌓입니다.")
        return
    print(f"{cmd} 중단: {r.get('reason')} {r.get('detail', '') or ''}".rstrip())
    for k in ("note", "stderr"):
        if r.get(k):
            print(f"  {k}: {r[k]}")
    if r.get("completeness"):
        _print_problems(r["completeness"]["failures"])
    if r.get("leaks"):
        _print_problems(r["leaks"])
    if r.get("snapshot"):
        _print_snapshot(r["snapshot"])


def _task_cmd(ctx, args):
    from ailog import tasks

    if not args or args[0] == "list":
        for t in tasks.listing(ctx):
            print(f"{t['id']} {t['kind']:13} {t['status']:9} {t['time']} {t.get('reason') or ''}".rstrip())
        return 0
    if args[0] == "run":
        r = tasks.run_pending(ctx, trigger="manual", lock_timeout=20)
        print(f"task run: {r}")
        return 0 if r.get("reason") in ("ok", "no_tasks") else 1
    if args[0] in ("cancel", "retry") and len(args) == 2:
        r = getattr(tasks, args[0])(ctx, args[1])
        print(f"task {args[0]}: {r}")
        return 0 if r["ok"] else 1
    if args[0] == "reserve" and len(args) >= 2:
        kind, rest = args[1], args[2:]
        a = {}
        if "--message-file" in rest:
            i = rest.index("--message-file")
            with open(rest[i + 1], "rb") as f:
                a["message"] = f.read().decode("utf-8")
            rest = rest[:i] + rest[i + 2:]
        if kind == "commit":
            if "--" not in rest:
                return 2
            a["paths"] = rest[rest.index("--") + 1:]
        elif kind == "squash":
            if "--onto" not in rest:
                return 2
            a["onto"] = rest[rest.index("--onto") + 1]
        elif kind == "stage-log":
            if not rest:
                return 2
            a["task"] = rest[0]
            a["stage"] = " ".join(rest[1:]) or None
        elif kind in ("push-develop", "resume", "merge-feature"):
            if not rest:
                return 2
            a["feature"] = rest[0]
        by = os.environ.get("CLAUDE_CODE_SESSION_ID") or os.environ.get("CODEX_SESSION_ID")
        r = tasks.reserve(ctx, kind, a, by=by)
        print(f"task reserve: {r}")
        return 0 if r["ok"] else 1
    return 2


def main(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    _utf8_stdout()  # hook·githook 포함 모든 명령의 출력(stdout·stderr)을 UTF-8로
    ctx = default_context()
    cmd = argv[0] if argv else "status"
    if cmd in ("push-develop", "merge-feature", "submit", "sync", "verify"):
        os.environ.setdefault("AI_LOGGING_PROGRESS", "1")
    if cmd == "hook":
        if len(argv) != 3:
            return 0
        return engine.hook(ctx, argv[1], argv[2])
    _utf8_stdout()
    if cmd == "install":
        r = engine.install(ctx)
        print(f"치환 목록: 총 {r['total']}개 (이번에 추가 {r['added']}개) - 값은 .ai-logging/mask-values.jsonl 에만 저장")
        for src, n in sorted(r["by_source"].items()):
            print(f"  {src}: {n}")
        from ailog import githooks
        from ailog.common import git

        if git(ctx.root, "rev-parse", "--git-dir") is None:
            print("Git 저장소가 아니어서 Git hook 설치는 건너뜀")
            h = {"ok": True}
        else:
            h = githooks.install_runtime(ctx)
            print(f"Git hook 런타임: core.hooksPath={h['hooks_path']}")
            print(f"  실행 확인: commit-msg 잘못된 메시지 거부={h['commit_msg_rejects_bad']}, "
                  f"pre-push 실행={h['pre_push_runs']}, master 삭제 거부={h['pre_push_rejects_master_delete']}")
        rep = engine.sync(ctx)
        _print_sync(rep)
        return 0 if rep["result"] == "ok" and h["ok"] else 1
    if cmd == "sync":
        rep = engine.sync(ctx)
        _print_sync(rep)
        return 0 if rep["result"] == "ok" else 1
    if cmd == "verify":
        final = "--final" in argv[1:]
        r = engine.verify(ctx, final=final)
        print(
            f"검증{'(최종)' if final else ''}: {'통과' if r['ok'] else '실패'} - 세션 {r['sessions_collected']}/{r['sessions_discovered']}, 파일 {r['files_checked']}개"
        )
        if r["failures"]:
            print("  실패:")
            _print_problems(r["failures"])
        if r["warnings"]:
            print("  참고(진행 중인 세션 등):")
            _print_problems(r["warnings"])
        return 0 if r["ok"] else 1
    if cmd == "status":
        st = engine.status(ctx)
        print(f"저장소: {st['root']}")
        print(f"치환 목록 항목: {st['mask_list_entries']}개")
        print(f"Claude hook 설정: {', '.join(st['claude_hooks_configured']) or '없음'}")
        print(f"Codex hook 설정: {', '.join(st['codex_hooks_configured']) or '없음'}")
        tr = st["codex_trust"]
        trusted = [k for k, v in sorted(tr["events"].items()) if v] if tr["readable"] else []
        print(f"Codex hook 신뢰 기록(trusted_hash): {', '.join(trusted) or '없음'} "
              "- 기록만으로 현재 설정의 신뢰·실행을 뜻하지 않음")
        cv = st["codex_hook_verified"]
        if cv.get("configured"):
            ev = cv["executed_events_since"]
            print(f"Codex hook 실제 실행(설정 변경 {cv['config_changed']} 이후): {', '.join(sorted(ev)) or '확인 안 됨'}")
        for tool, evs in sorted(st["last_hook_events"].items()):
            print(f"마지막 hook 실행({tool}): " + ", ".join(f"{e} {t}" for e, t in sorted(evs.items())))
        if not st["last_hook_events"]:
            print("마지막 hook 실행: 기록 없음")
        from ailog import githooks, tasks, turns

        rs = githooks.runtime_status(ctx)
        print(f"Git hook 런타임: 설치={rs.get('installed')}, core.hooksPath 일치={rs.get('hooks_path_ok')}")
        for k, v in sorted(turns.effective_states(ctx).items()):
            print(f"턴 상태 {k}: {v['state']} ({v['why']})")
        pend = [t for t in tasks.listing(ctx) if t["status"] in ("pending", "failed")]
        for t in pend:
            print(f"예약 작업 {t['id']} {t['kind']}: {t['status']} {t.get('reason') or ''}".rstrip())
        if st["last_sync"]:
            _print_sync(st["last_sync"])
        else:
            print("수집 기록 없음 - install 실행 필요")
        for e in st["recent_errors"]:
            print(f"최근 hook 오류: {e.get('time')} {e.get('tool')} {e.get('event')} {e.get('error')} @ {e.get('where')}")
        return 0
    if cmd == "submit":
        task = argv[1] if len(argv) > 1 else None
        print("주의: 모든 AI 응답이 끝난 뒤 일반 터미널에서 실행해야 마지막 대화까지 담깁니다.")
        r = engine.submit(ctx, task=task)
        return _print_commit_result(r, "제출 커밋")
    if cmd == "holds":
        list_id, items = engine.held_candidates(ctx)
        print(f"보류 후보 {len(items)}개 (목록 지문 {list_id}, 값은 표시하지 않음)")
        for g in items:
            where = ", ".join(g["at"][:3]) + (f" 외 {len(g['at']) - 3}곳" if len(g["at"]) > 3 else "")
            mark = "" if g["maskable"] else " (값 치환 불가: 허용 목록 검토 대상)"
            print(f"  #{g['no']} [{g['rule']}] 길이 {g['length']} 형태 {g['shape']} 키 {g['key']} 출현 {len(g['at'])}회: {where}{mark}")
        if items:
            print(f"  치환: python -I scripts/ai_logging/cli.py mask-held {list_id} <번호>...")
            print("  치환하지 않을 공개·가짜 값은 config/ai-logs-allowlist.json 에 사유와 함께 추가")
        return 0
    if cmd == "mask-held":
        if len(argv) < 3 or not all(a.isdigit() for a in argv[2:]):
            print(__doc__)
            return 2
        r = engine.mask_held(ctx, argv[1], [int(a) for a in argv[2:]])
        if not r["ok"]:
            print(f"치환 목록 추가 중단: {r['reason']} {r['detail']}".rstrip())
            return 1
        print(f"치환 목록: {r['added']}개 추가, 총 {r['total']}개 (값은 표시하지 않음)")
        rep = engine.sync(ctx)
        _print_sync(rep)
        return 0 if rep["result"] == "ok" else 1
    if cmd == "index":
        for r in engine.build_index(ctx):
            print(f"{r['first_timestamp']}  {r['tool']:6}  {r['session_id']}  {r['main_lines']}줄  {r['path']}")
        return 0
    if cmd == "repair-live" and len(argv) == 2:
        r = engine.repair_live(ctx, argv[1])
        print(f"repair-live: {r}")
        return 0 if r.get("ok") else 1
    if cmd == "snapshot":
        from ailog import snapshot, turns

        r = snapshot.snapshot_all(ctx, states=turns.effective_states(ctx))
        _print_snapshot(r)
        return 0 if r["result"] == "ok" else 1
    if cmd == "completeness":
        from ailog import snapshot

        r = snapshot.completeness(ctx, argv[1] if len(argv) > 1 else None)
        print(f"완전성({r['treeish']}, 정책 {r['policy_version']}): {'완전' if r['complete'] else '불완전'}")
        _print_problems(r["failures"])
        _print_problems(r["warnings"])
        return 0 if r["complete"] else 1
    if cmd == "live-covered":
        from ailog import snapshot

        r = snapshot.live_covered(ctx)
        print(f"live ⊆ snapshot: {'예' if r['covered'] else '아니오'} (파일 {r['files_checked']}개)")
        _print_problems(r["gaps"])
        return 0 if r["covered"] else 1
    if cmd == "turns":
        from ailog import turns

        if len(argv) == 4 and argv[1] == "resolve":
            r = turns.resolve(ctx, argv[2], argv[3])
            print(f"turns resolve: {'완료' if r['ok'] else r['reason']}")
            return 0 if r["ok"] else 1
        for k, v in sorted(turns.effective_states(ctx).items()):
            print(f"{k}: {v['state']} ({v['why']})")
        return 0
    if cmd == "task":
        return _task_cmd(ctx, argv[1:])
    if cmd in ("new-feature", "push-develop", "resume", "merge-feature") and len(argv) >= 2:
        from ailog import flows

        fn = {"new-feature": flows.new_feature, "push-develop": flows.push_develop, "resume": flows.resume,
              "merge-feature": flows.merge_feature}[cmd]
        kw = {"push": False} if cmd == "push-develop" and "--no-push" in argv[2:] else {}
        r = fn(ctx, argv[1], **kw)
        _print_flow(cmd, r)
        return 0 if r.get("ok") else 1
    if cmd == "githook" and len(argv) >= 2:
        from ailog import githooks

        if argv[1] == "commit-msg" and len(argv) == 3:
            return githooks.commit_msg(ctx, argv[2])
        if argv[1] == "pre-push" and len(argv) >= 3:
            data = sys.stdin.buffer.read().decode("utf-8", "replace") if sys.stdin else ""
            return githooks.pre_push(ctx, argv[2], data)
        return 2
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
