"""AI 턴 중에 요청된 Git 작업의 예약·실행.

AI 턴이 진행 중일 때는 Git 변경을 막는다. 대신 작업을 .ai-logging/tasks.jsonl에 예약하고,
모든 세션이 idle로 확인되는 Stop 시점(또는 사용자의 `tasks run`)에 순서대로 실행한다.

- 기록은 추가 전용 이벤트(reserved / done / failed / cancelled / retry)다.
- 실행 조건: 모든 세션 idle, 스테이징된 변경 없음, 진행 중인 Git 작업 없음,
  예약 때와 같은 브랜치, HEAD가 예약 때 값(또는 바로 앞 예약 작업이 만든 값)과 같음.
- 실패한 작업이 있으면 뒤 작업은 실행하지 않는다(사용자가 retry 또는 cancel).
- 자동 stash·강제 checkout/reset·작업 폴더 정리는 하지 않는다. 커밋은 임시 index + CAS로 만든다.
"""
from __future__ import annotations

import json
import os
import uuid

from .common import Context, GitError, LockTimeout, append_jsonl, utc_now
from . import config, gitops, rules

KINDS = ("commit", "squash", "stage-log", "push-develop", "resume", "merge-feature")


def _events(ctx: Context) -> list[dict]:
    out = []
    try:
        with open(ctx.tasks_path, "rb") as f:
            for raw in f:
                try:
                    o = json.loads(raw)
                except ValueError:
                    continue
                if isinstance(o, dict):
                    out.append(o)
    except FileNotFoundError:
        pass
    return out


def listing(ctx: Context) -> list[dict]:
    """예약 작업과 현재 상태(pending/done/failed/cancelled) — 예약 순서."""
    tasks: dict[str, dict] = {}
    order = []
    for e in _events(ctx):
        tid = e.get("id")
        if e.get("event") == "reserved":
            tasks[tid] = dict(e, status="pending")
            order.append(tid)
        elif tid in tasks:
            ev = e.get("event")
            if ev == "done":
                tasks[tid].update(status="done", result=e.get("result"), finished=e.get("time"))
            elif ev == "failed":
                tasks[tid].update(status="failed", reason=e.get("reason"), finished=e.get("time"))
            elif ev == "cancelled":
                tasks[tid].update(status="cancelled")
            elif ev == "retry":
                tasks[tid].update(status="pending", reason=None)
    return [tasks[t] for t in order]


def _check_args(kind: str, args: dict) -> str | None:
    if kind not in KINDS:
        return "unknown_kind"
    msg = args.get("message")
    if kind in ("commit", "squash"):
        if not isinstance(msg, str) or rules.check_message(msg):
            return "message_format"
    if kind == "commit":
        paths = args.get("paths")
        if not paths or not all(isinstance(p, str) and p for p in paths):
            return "paths_required"
        for p in paths:
            n = p.replace("\\", "/").strip("/")
            if n in ("", ".") or n.startswith(("ai-logs", ".ai-logging", "..")) or os.path.isabs(p):
                return "path_not_allowed"
    if kind == "squash" and not args.get("onto"):
        return "onto_required"
    if kind == "stage-log" and not rules.TASK_ID_RE.match(args.get("task") or ""):
        return "task_id_format"
    if kind in ("push-develop", "resume", "merge-feature") and not args.get("feature"):
        return "feature_required"
    return None


def _task_of(kind: str, args: dict) -> str | None:
    if kind in ("commit", "squash"):
        return rules.task_from_title(args.get("message") or "")
    if kind == "stage-log":
        return args.get("task")
    return None


def reserve(ctx: Context, kind: str, args: dict, by: str | None = None) -> dict:
    bad = _check_args(kind, args)
    if bad:
        return {"ok": False, "reason": bad}
    if kind in ("commit", "squash", "stage-log"):
        mm = config.check_branch_task(ctx, _task_of(kind, args), gitops.current_branch(ctx.root))
        if mm:
            return {"ok": False, "reason": "task_branch_mismatch", "detail": mm.split(":", 1)[1]}
    if kind == "squash":
        onto = gitops.rev(ctx.root, args["onto"])
        if not onto:
            return {"ok": False, "reason": "onto_not_found"}
        args = dict(args, onto=onto)
    pend = [t for t in listing(ctx) if t["status"] in ("pending", "failed")]
    row = {
        "event": "reserved",
        "id": uuid.uuid4().hex[:12],
        "kind": kind,
        "args": args,
        "branch": gitops.current_branch(ctx.root),
        "head": gitops.rev(ctx.root, "HEAD"),
        "after": pend[-1]["id"] if pend else None,
        "by": by,
        "time": utc_now(),
    }
    append_jsonl(ctx.tasks_path, row)
    return {"ok": True, "id": row["id"], "kind": kind, "after": row["after"]}


def _mark(ctx: Context, tid: str, event: str, **kw) -> None:
    append_jsonl(ctx.tasks_path, dict({"event": event, "id": tid, "time": utc_now()}, **kw))


def cancel(ctx: Context, tid: str) -> dict:
    t = next((x for x in listing(ctx) if x["id"] == tid), None)
    if not t or t["status"] not in ("pending", "failed"):
        return {"ok": False, "reason": "not_pending"}
    _mark(ctx, tid, "cancelled")
    return {"ok": True}


def retry(ctx: Context, tid: str) -> dict:
    t = next((x for x in listing(ctx) if x["id"] == tid), None)
    if not t or t["status"] != "failed":
        return {"ok": False, "reason": "not_failed"}
    _mark(ctx, tid, "retry")
    return {"ok": True}


# ------------------------------------------------------------------ executors
def _blob_reader(root: str, commit: str):
    files = gitops.tree_files(root, commit)
    cache = {}

    def blob(path):
        if path not in files:
            return None
        oid = files[path][1]
        if oid not in cache:
            cache.update(gitops.cat_blobs(root, [oid]))
        return cache.get(oid)

    return blob


def _do_commit(ctx: Context, t: dict, head: str, branch: str) -> dict:
    a = t["args"]
    if rules.check_message(a["message"]):
        return {"ok": False, "reason": "message_format"}
    mm = config.check_branch_task(ctx, rules.task_from_title(a["message"]), branch)
    if mm:
        return {"ok": False, "reason": "task_branch_mismatch", "detail": mm.split(":", 1)[1]}
    commit, tree = gitops.build_commit(ctx.root, head, a["message"], add_worktree_paths=a["paths"],
                                       state_dir=ctx.state_dir)
    if head and tree == gitops.tree_of(ctx.root, head):
        return {"ok": False, "reason": "nothing_to_commit"}
    changes = gitops.diff_tree(ctx.root, head, commit)
    if any(p.startswith("ai-logs/") for _, p in changes):
        return {"ok": False, "reason": "log_path_in_source_commit"}
    problems = rules.check_changes(changes, _blob_reader(ctx.root, commit), allow_source=True)
    if problems:
        return {"ok": False, "reason": "commit_check_failed", "detail": ",".join(problems)}
    gitops.cas_update_branch(ctx.root, branch, commit, head, "reserved commit")
    gitops.sync_index_paths(ctx.root, commit, [p for _, p in changes])
    return {"ok": True, "commit": commit, "files": len(changes)}


def is_log_only(root: str, commit: str) -> bool:
    ps = gitops.parents(root, commit)
    if len(ps) != 1:
        return False
    ch = gitops.diff_tree(root, ps[0], commit)
    return bool(ch) and all(rules.is_log_path(p) for _, p in ch)


def _do_squash(ctx: Context, t: dict, head: str, branch: str) -> dict:
    a = t["args"]
    mm = config.check_branch_task(ctx, rules.task_from_title(a["message"]), branch)
    if mm:
        return {"ok": False, "reason": "task_branch_mismatch", "detail": mm.split(":", 1)[1]}
    onto = a["onto"]
    if not gitops.is_ancestor(ctx.root, onto, head) or onto == head:
        return {"ok": False, "reason": "onto_not_ancestor"}
    revs = (gitops.git(ctx.root, "rev-list", f"{onto}..{head}") or "").split()
    for c in revs:
        if len(gitops.parents(ctx.root, c)) != 1:
            return {"ok": False, "reason": "merge_commit_in_range"}
        if is_log_only(ctx.root, c):
            return {"ok": False, "reason": "log_commit_in_range"}
    tree = gitops.tree_of(ctx.root, head)
    new = gitops.git_run(ctx.root, "commit-tree", tree, "-p", onto, "-F", "-",
                         input=a["message"].encode("utf-8")).stdout.decode().strip()
    stamp = utc_now().replace(":", "").replace("-", "").replace(".", "")
    backup = f"refs/ai-logging/backup/{branch}/{stamp}"
    gitops.git_run(ctx.root, "update-ref", backup, head, gitops.ZERO)
    gitops.cas_update_branch(ctx.root, branch, new, head, "reserved squash")
    return {"ok": True, "commit": new, "squashed": len(revs), "backup_ref": backup}


def _do_stage_log(ctx: Context, t: dict, head: str, branch: str, states: dict) -> dict:
    from . import snapshot

    a = t["args"]
    title = f"세션 로그 스냅샷 ({a.get('stage') or '단계 완료'})"
    r = snapshot.commit_snapshots(ctx, a["task"], title, expect_branch=branch, expect_head=head, states=states)
    if r.get("ok") and not r.get("committed"):
        return {"ok": True, "commit": head, "committed": False}
    return r


def _do_flow(ctx: Context, t: dict) -> dict:
    from . import flows

    fn = {"push-develop": flows.push_develop, "resume": flows.resume, "merge-feature": flows.merge_feature}[t["kind"]]
    return fn(ctx, t["args"]["feature"], guard_held=True)


def run_pending(ctx: Context, trigger: str = "manual", lock_timeout: float = 5.0) -> dict:
    from . import turns

    todo = [t for t in listing(ctx) if t["status"] in ("pending", "failed")]
    if not todo:
        return {"ran": 0, "reason": "no_tasks"}
    try:
        with turns.guard_lock(ctx, timeout=lock_timeout):
            states = turns.effective_states(ctx)
            busy = {k: v for k, v in states.items() if v["state"] != "idle"}
            if busy:
                return {"ran": 0, "reason": "ai_turn_not_idle", "sessions": busy}
            done_heads = {x["id"]: (x.get("result") or {}).get("commit") for x in listing(ctx) if x["status"] == "done"}
            ran = []
            for t in todo:
                if t["status"] == "failed":
                    return {"ran": len(ran), "reason": "blocked_by_failed_task", "task": t["id"], "done": ran}
                root = ctx.root
                branch = gitops.current_branch(root)
                head = gitops.rev(root, "HEAD")
                expected = done_heads.get(t["after"]) if t.get("after") in done_heads else t["head"]
                why = None
                if t["kind"] not in ("push-develop", "resume", "merge-feature"):
                    if branch != t["branch"]:
                        why = "branch_changed"
                    elif head != expected:
                        why = "head_changed"
                if not why and gitops.in_progress(root):
                    why = "git_operation_in_progress"
                if not why and gitops.staged_changes(root):
                    why = "staged_changes_present"
                if why:
                    _mark(ctx, t["id"], "failed", reason=why, trigger=trigger)
                    return {"ran": len(ran), "reason": why, "task": t["id"], "done": ran}
                try:
                    if t["kind"] == "commit":
                        r = _do_commit(ctx, t, head, branch)
                    elif t["kind"] == "squash":
                        r = _do_squash(ctx, t, head, branch)
                    elif t["kind"] == "stage-log":
                        r = _do_stage_log(ctx, t, head, branch, states)
                    else:
                        r = _do_flow(ctx, t)
                except GitError as e:
                    r = {"ok": False, "reason": "git_error", "detail": " ".join(map(str, e.args[0]))[:200]}
                if not r.get("ok"):
                    _mark(ctx, t["id"], "failed", reason=r.get("reason"), detail=r.get("detail"), trigger=trigger)
                    return {"ran": len(ran), "reason": r.get("reason"), "task": t["id"], "done": ran}
                result = {k: v for k, v in r.items() if k in ("commit", "squashed", "backup_ref", "files",
                                                              "snapshots", "committed", "run")}
                _mark(ctx, t["id"], "done", result=result, trigger=trigger)
                done_heads[t["id"]] = result.get("commit") or gitops.rev(root, "HEAD")
                ran.append(t["id"])
            return {"ran": len(ran), "reason": "ok", "done": ran}
    except LockTimeout:
        return {"ran": 0, "reason": "guard_lock_busy"}
