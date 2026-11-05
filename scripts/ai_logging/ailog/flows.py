"""브랜치 흐름 명령: new-feature · push-develop · resume · merge-feature.

공통 원칙
- 모든 AI 세션 idle, 진행 중인 Git 작업·스테이징 변경·작업 폴더 변경이 없을 때만 실행한다.
- 턴 보호 잠금(guard.lock)을 잡고 실행한다(그동안 새 프롬프트는 대기 후 차단).
- 작업 폴더를 전환하지 않는다. 체크아웃하지 않은 브랜치는 Git 객체로 커밋·병합을 만들고
  비교 후 교체(CAS)로 ref를 옮긴다. 충돌이 나면 아무것도 바꾸지 않고 멈춘다.
- force push·reset·stash·빈 커밋을 쓰지 않는다. develop을 master에 병합하거나 develop:master로 push하지 않는다.
- 단계별 진행 상태를 .ai-logging/runs/에 남겨, 같은 명령을 다시 실행하면 이어서 진행한다.
"""
from __future__ import annotations

import os
from contextlib import contextmanager

# 작업 폴더의 스크립트가 바뀌어도 실행 중인 명령이 깨지지 않도록 필요한 모듈을 먼저 읽어 둔다.
from . import config, discover, engine, gitops, policy, rules, snapshot, turns  # noqa: F401
from .common import SNAPSHOTS_REL, Context, GitError, read_json_ro, utc_now, write_json
from .progress import step, report


class FlowStop(Exception):
    def __init__(self, reason: str, detail: str = "", **extra):
        super().__init__(reason)
        self.reason, self.detail, self.extra = reason, detail, extra


def _feature(cfg: dict, feature: str):
    name = feature if feature.startswith(cfg["feature_prefix"]) else cfg["feature_prefix"] + feature
    fid = config.feature_id(cfg, name)
    if not fid:
        raise FlowStop("feature_name_format", f"예: {cfg['feature_prefix']}LOG-002")
    return name, fid, config.task_id(cfg, fid)


@contextmanager
def _guard(ctx: Context, held: bool):
    if held:
        yield
    else:
        with turns.guard_lock(ctx, timeout=20):
            yield


def _preflight(ctx: Context) -> str:
    busy = turns.busy_sessions(ctx)
    if busy:
        raise FlowStop("ai_turn_not_idle", ", ".join(f"{k}={v['state']}" for k, v in sorted(busy.items())))
    ops = gitops.in_progress(ctx.root)
    if ops:
        raise FlowStop("git_operation_in_progress", ",".join(ops))
    if gitops.staged_changes(ctx.root):
        raise FlowStop("staged_changes_present")
    dirty = gitops.worktree_dirty(ctx.root)
    if dirty:
        raise FlowStop("worktree_dirty", f"{len(dirty)}개 경로")
    cur = gitops.current_branch(ctx.root)
    if not cur:
        raise FlowStop("detached_head")
    return cur


def _fetch(ctx: Context, remote: str, *branches: str) -> None:
    for b in branches:
        r = gitops.git_run(ctx.root, "fetch", "--no-tags", remote, b, check=False, timeout=300)
        if r.returncode != 0 and b"couldn't find remote ref" not in r.stderr:
            raise FlowStop("fetch_failed", f"{remote} {b}")


def _align(ctx: Context, branch: str, remote_rev: str | None, current: str, allow_ahead_log_only: bool = False,
           allow_ahead=None) -> str | None:
    """로컬 브랜치를 원격 추적 ref에 맞춘다(빨리 감기만).

    앞서 있는 커밋은 allow_ahead(커밋) 또는 allow_ahead_log_only(로그 전용)일 때만 허용한다.
    """
    local = gitops.rev(ctx.root, f"refs/heads/{branch}")
    if remote_rev is None:
        return local
    if local is None:
        gitops.git_run(ctx.root, "update-ref", f"refs/heads/{branch}", remote_rev, gitops.ZERO)
        return remote_rev
    if local == remote_rev:
        return local
    if gitops.is_ancestor(ctx.root, local, remote_rev):
        if branch == current:
            gitops.git_run(ctx.root, "merge", "--ff-only", "-q", remote_rev)
        else:
            gitops.cas_update_branch(ctx.root, branch, remote_rev, local, "fast-forward to remote")
        return remote_rev
    if gitops.is_ancestor(ctx.root, remote_rev, local):
        extra = (gitops.git(ctx.root, "rev-list", f"{remote_rev}..{local}") or "").split()
        ok = allow_ahead or (lambda c: allow_ahead_log_only and _is_log_commit(ctx.root, c))
        if all(ok(c) for c in extra):
            return local
        raise FlowStop("local_branch_ahead_of_remote", f"{branch}: {len(extra)}개 커밋 (push 또는 정리 필요)")
    raise FlowStop("branch_diverged", branch)


def _is_log_commit(root: str, c: str) -> bool:
    """로그 전용 커밋: 첫 부모 대비 변경이 모두 스냅샷 추가(병합 커밋 포함)."""
    ps = gitops.parents(root, c)
    if not ps:
        return False
    ch = gitops.diff_tree(root, ps[0], c)
    return bool(ch) and all(rules.is_log_path(p) and st == "A" for st, p in ch)


def _nonlog(root: str, treeish: str) -> dict:
    return {p: v for p, v in gitops.tree_files(root, treeish).items() if not p.startswith(SNAPSHOTS_REL + "/")}


def _bases(root: str, treeish: str) -> set[str]:
    return set(snapshot.tree_manifests(root, treeish))


def _commit_bases(root: str, c: str) -> set[str]:
    ps = gitops.parents(root, c)
    groups, _ = rules.snapshot_groups([p for st, p in gitops.diff_tree(root, ps[0] if ps else None, c)])
    return set(groups)


def _run_file(ctx: Context, flow: str, fid: str) -> str:
    return os.path.join(ctx.runs_dir, f"{flow}-{fid}.json")


def _load_run(ctx: Context, flow: str, fid: str) -> dict:
    data, st = read_json_ro(_run_file(ctx, flow, fid))
    if st == "ok" and isinstance(data, dict) and not data.get("finished"):
        data["resumed"] = True
        return data
    return {"flow": flow, "feature": fid, "started": utc_now(), "steps": {}}


def _save_run(ctx: Context, run: dict) -> None:
    run["updated"] = utc_now()
    write_json(_run_file(ctx, run["flow"], run["feature"]), run)


def _message_ok(msg: str) -> None:
    problems = rules.check_message(msg)
    if problems:
        raise FlowStop("message_format", ",".join(problems))


def _plumb_merge(ctx: Context, ours: str, theirs: str, message: str) -> str:
    """두 부모 병합 커밋 객체를 만든다(ref는 바꾸지 않음). 충돌이면 FlowStop."""
    _message_ok(message)
    r = gitops.git_run(ctx.root, "merge-tree", "--write-tree", "--no-messages", ours, theirs, check=False)
    if r.returncode != 0:
        raise FlowStop("merge_conflict", "충돌 경로는 git merge-tree 로 확인(자동 해결하지 않음)")
    tree = r.stdout.decode().split()[0]
    return gitops.git_run(ctx.root, "commit-tree", tree, "-p", ours, "-p", theirs, "-F", "-",
                          input=message.encode("utf-8")).stdout.decode().strip()


def _pick(ctx: Context, onto: str, c: str) -> str | None:
    """`cherry-pick -x`와 같은 결과(원 작성자·메시지 + cherry picked from 줄)를 작업 폴더 전환 없이 만든다.

    이미 반영된 변경이면 None(빈 커밋을 만들지 않음). 충돌이면 FlowStop.
    """
    parent = gitops.parents(ctx.root, c)[0]
    r = gitops.git_run(ctx.root, "merge-tree", "--write-tree", "--no-messages", f"--merge-base={parent}", onto, c,
                       check=False)
    if r.returncode != 0:
        raise FlowStop("cherry_pick_conflict", c[:12])
    tree = r.stdout.decode().split()[0]
    if tree == gitops.tree_of(ctx.root, onto):
        return None
    raw = gitops.git_run(ctx.root, "log", "-1", "--date=raw", "--format=%an%x00%ae%x00%ad%x00%B", c).stdout
    name, email, date, body = raw.decode("utf-8").split("\x00", 3)
    msg = body.rstrip("\n") + f"\n\n(cherry picked from commit {c})\n"
    _message_ok(msg)
    env = {"GIT_AUTHOR_NAME": name, "GIT_AUTHOR_EMAIL": email, "GIT_AUTHOR_DATE": date}
    return gitops.git_run(ctx.root, "commit-tree", tree, "-p", onto, "-F", "-", input=msg.encode("utf-8"),
                          env=env).stdout.decode().strip()


def _final_log_checks(ctx: Context, commit: str) -> list[str]:
    """커밋된 비밀값은 중단. 원본보다 뒤처진 로그는 경고만 돌려준다(다음 스냅샷·게시 때 이어서 쌓인다)."""
    pol = engine.build_policy(ctx)
    comp = step("원본과 최신 스냅샷 완전성 검사", snapshot.completeness, ctx, commit, pol)
    warnings = [f"logs:{f['rule']} {f.get('session', '')}".rstrip() for f in comp["failures"][:20]]
    leaks = step("과거 스냅샷 포함 비밀 검사", snapshot.scan_tree_secrets, ctx, commit, pol)
    if leaks:
        raise FlowStop("committed_secret_found",
                       "이미 커밋된 값은 새 마스킹 커밋으로 지울 수 없음 - 게시 중단, 이력 정리는 사용자 결정",
                       leaks=leaks[:20])
    return warnings


def _hold_warnings(r: dict) -> list[str]:
    """로그 커밋에서 빠진 보류 세션(값은 게시하지 않음). 해소하면 다음 스냅샷 커밋·push에 이어서 쌓인다."""
    return [f"logs:snapshot_hold {k} {n}건 - holds로 형태 확인 후 mask-held 또는 허용 목록으로 해소"
            for k, n in sorted((r.get("held") or {}).items())]


def _result(fn):
    def wrapper(ctx, feature, guard_held=False, **kw):
        try:
            return fn(ctx, feature, guard_held=guard_held, **kw)
        except FlowStop as e:
            return dict({"ok": False, "reason": e.reason, "detail": e.detail}, **e.extra)
        except GitError as e:
            return {"ok": False, "reason": "git_error", "detail": str(e)}

    wrapper.__name__ = fn.__name__
    wrapper.__doc__ = fn.__doc__
    return wrapper


# ------------------------------------------------------------------ commands
@_result
def new_feature(ctx: Context, feature: str, guard_held: bool = False) -> dict:
    """master에서 feature/<ID> 생성·전환. master에 로깅 설정이 있어야 하고, 병합 안 된 다른 feature가 없어야 한다."""
    cfg = config.load(ctx)
    name, fid, task = _feature(cfg, feature)
    main, remote = cfg["main"], cfg["remote"]
    with _guard(ctx, guard_held):
        cur = _preflight(ctx)
        _fetch(ctx, remote, main)
        om = gitops.remote_ref(ctx.root, remote, main) or gitops.rev(ctx.root, f"refs/heads/{main}")
        if not om:
            raise FlowStop("master_missing")
        if not config.has_config_in(ctx.root, om):
            raise FlowStop("master_without_logging_config",
                           f"최초 도입 절차({cfg['bootstrap_feature']}의 merge-feature 후 master push)를 먼저 마친다")
        if gitops.rev(ctx.root, f"refs/heads/{name}"):
            raise FlowStop("feature_exists", name)
        refs = gitops.git(ctx.root, "for-each-ref", "--format=%(refname)",
                          f"refs/heads/{cfg['feature_prefix']}", f"refs/remotes/{remote}/{cfg['feature_prefix']}") or ""
        open_ = [r for r in refs.split() if not gitops.is_ancestor(ctx.root, r, om)]
        if open_:
            raise FlowStop("unmerged_feature_exists", ", ".join(x.split("/", 2)[-1] for x in open_),
                           note="동시에 진행하는 feature는 하나")
        _align(ctx, main, om, cur, allow_ahead_log_only=True)
        gitops.git_run(ctx.root, "switch", "-c", name, f"refs/heads/{main}")
        return {"ok": True, "branch": name, "task": task, "from": om}


@_result
def push_develop(ctx: Context, feature: str, guard_held: bool = False, push: bool = True) -> dict:
    """develop push 시점의 로그 동기화(사용자 실행).

    1) develop에 전체 스냅샷 로그 커밋(검토 대화·실패 기록 포함)
    2) master..develop의 모든 부모 경로에서 로그 전용 커밋 중 master에 없는 스냅샷만 골라
       `cherry-pick -x`와 같은 커밋을 master 위에 만든다
    3) master의 로그 밖 트리가 그대로인지, 로그 완전성·커밋된 비밀값 여부 확인
    4) `git push --atomic <remote> develop master` 후 원격 ref 확인
    develop 기능 검증은 자동으로 하지 않는다.
    """
    cfg = config.load(ctx)
    name, fid, task = _feature(cfg, feature)
    main, dev, remote = cfg["main"], cfg["develop"], cfg["remote"]
    root = ctx.root
    with _guard(ctx, guard_held):
        cur = _preflight(ctx)
        if cur == main:
            raise FlowStop("run_from_feature_or_develop", "master를 체크아웃한 상태에서는 실행하지 않는다")
        run = _load_run(ctx, "push-develop", fid)
        step("원격 브랜치 확인", _fetch, ctx, remote, main, dev)
        om = gitops.remote_ref(root, remote, main)
        od = gitops.remote_ref(root, remote, dev)
        if not om:
            raise FlowStop("remote_master_missing")
        if not config.has_config_in(root, om):
            raise FlowStop("master_without_logging_config",
                           f"최초 도입({cfg['bootstrap_feature']})은 merge-feature로 master에 반영한 뒤부터 동기화")
        if od:
            _align(ctx, dev, od, cur, allow_ahead=lambda c: True)  # 사용자가 병합한 미push 커밋 허용
        dev_before = gitops.rev(root, f"refs/heads/{dev}")
        if not dev_before:
            raise FlowStop("develop_missing")
        if od and not gitops.is_ancestor(root, od, dev_before):
            raise FlowStop("branch_diverged", dev)
        _align(ctx, main, om, cur, allow_ahead_log_only=True)
        main_before = gitops.rev(root, f"refs/heads/{main}")
        states = turns.effective_states(ctx)
        r = step("최신 원본 검증 및 develop 스냅샷", snapshot.commit_snapshots, ctx, task, "세션 로그 스냅샷 (develop 검토)", branch=dev, states=states,
                                      extra_refs=(main_before,))
        if not r.get("ok"):
            raise FlowStop("develop_log_commit_failed", r.get("reason", ""), snapshot=r.get("snapshot"))
        run["steps"]["develop_log_commit"] = r.get("commit")
        _save_run(ctx, run)
        dev_head = gitops.rev(root, f"refs/heads/{dev}")
        in_main = _bases(root, main_before)
        chain = (gitops.git(root, "rev-list", "--reverse", "--topo-order", f"{main_before}..{dev_head}") or "").split()
        picks = [c for c in chain if _is_log_commit(root, c) and len(gitops.parents(root, c)) == 1
                 and not _commit_bases(root, c) <= in_main]
        new_main = main_before
        applied = []
        report(f"master에 없는 로그 전용 커밋: {len(picks)}개")
        for c in picks:
            n = step(f"로그 커밋 동기화 {len(applied) + 1}/{len(picks)}", _pick, ctx, new_main, c)
            if n:
                new_main = n
                applied.append({"from": c, "to": n})
        if _nonlog(root, new_main) != _nonlog(root, main_before):
            raise FlowStop("master_non_log_tree_changed")
        dev_bases = _bases(root, dev_head)
        missing = dev_bases - _bases(root, new_main)
        if missing:
            raise FlowStop("master_missing_develop_snapshots", f"{len(missing)}개")
        warnings = _hold_warnings(r) + _final_log_checks(ctx, new_main)
        if new_main != main_before:
            gitops.cas_update_branch(root, main, new_main, main_before, "sync develop log commits")
        run["steps"]["master_logs"] = {"from": main_before, "to": new_main, "applied": applied}
        _save_run(ctx, run)
        result = {"ok": True, "develop": dev_head, "master": new_main, "picked": len(applied),
                  "develop_log_commit": r.get("committed", False), "pushed": False, "warnings": warnings}
        if push:
            refspecs = [f"refs/heads/{dev}:refs/heads/{dev}", f"refs/heads/{main}:refs/heads/{main}"]
            p = step("pre-push 검사 및 원격 atomic push", gitops.git_run,
                     root, "push", "--atomic", remote, *refspecs, check=False, timeout=600)
            if p.returncode != 0:
                raise FlowStop("push_rejected", p.stderr.decode("utf-8", "replace").strip()[-300:],
                               note="로컬 develop·master 커밋은 남아 있다. 원인 해결 후 같은 명령으로 이어서 진행")
            ls = gitops.git(root, "ls-remote", remote, f"refs/heads/{dev}", f"refs/heads/{main}", timeout=60) or ""
            got = {ln.split()[1]: ln.split()[0] for ln in ls.splitlines() if ln.strip()}
            if got.get(f"refs/heads/{dev}") != dev_head or got.get(f"refs/heads/{main}") != new_main:
                raise FlowStop("remote_verify_failed")
            result["pushed"] = True
        run["finished"] = utc_now()
        run["result"] = result
        _save_run(ctx, run)
        return result


@_result
def resume(ctx: Context, feature: str, guard_held: bool = False) -> dict:
    """보완 시작: 최신 master를 feature에 일반 병합. master의 새 변경은 로그 전용이어야 한다."""
    cfg = config.load(ctx)
    name, fid, task = _feature(cfg, feature)
    main, remote = cfg["main"], cfg["remote"]
    root = ctx.root
    with _guard(ctx, guard_held):
        cur = _preflight(ctx)
        _fetch(ctx, remote, main)
        om = gitops.remote_ref(root, remote, main) or gitops.rev(root, f"refs/heads/{main}")
        head = gitops.rev(root, f"refs/heads/{name}")
        if not head:
            raise FlowStop("feature_missing", name)
        if gitops.is_ancestor(root, om, head):
            return {"ok": True, "merged": False, "reason": "already_up_to_date"}
        base = (gitops.git(root, "merge-base", head, om) or "").strip()
        changes = gitops.diff_tree(root, base or None, om)
        bad = [p for _, p in changes if not rules.is_log_path(p)]
        if bad:
            raise FlowStop("master_has_non_log_changes", f"{len(bad)}개 경로 - 사용자 확인 필요")
        n = len((gitops.git(root, "rev-list", f"{head}..{om}") or "").split())
        msg = (f"[{task}] 최신 {main} 반영\n\n  {remote}/{main}의 새 커밋 {n}개(로그 전용)를 {name}에 일반 병합\n"
               f"  보완 작업 시작 전 동기화\n")
        _message_ok(msg)
        if cur == name:
            path = os.path.join(ctx.state_dir, "merge-message.txt")
            with open(path, "wb") as f:
                f.write(msg.encode("utf-8"))
            r = gitops.git_run(root, "merge", "--no-ff", "--no-edit", "-F", path, om, check=False, timeout=300)
            if r.returncode != 0:
                raise FlowStop("merge_failed", "자동 abort하지 않음 - git status로 확인",
                               stderr=r.stderr.decode("utf-8", "replace")[-300:])
            new = gitops.rev(root, "HEAD")
        else:
            new = _plumb_merge(ctx, head, om, msg)
            gitops.cas_update_branch(root, name, new, head, "resume: merge master")
        return {"ok": True, "merged": True, "commit": new, "master": om}


def _is_feature_merge(root: str, c: str, name: str) -> bool:
    ps = gitops.parents(root, c)
    title = (gitops.git(root, "log", "-1", "--format=%s", c) or "")
    return len(ps) == 2 and f"] {name} " in title


def direct_source_commits(root: str, base: str | None, tip: str, remote: str) -> list[str]:
    """master 첫 부모 경로에 병합 커밋 없이 들어온 소스 커밋(fast-forward·직접 커밋).

    master의 새 커밋은 feature --no-ff 병합 커밋(부모 2개)이나 로그 전용 커밋이어야 한다.
    """
    rng = [f"{base}..{tip}"] if base else [tip, "--not", f"--remotes={remote}"]
    out = []
    for c in (gitops.git(root, "rev-list", "--first-parent", *rng, timeout=60) or "").split():
        if len(gitops.parents(root, c)) == 1 and not _is_log_commit(root, c):
            out.append(c)
    return out


def _last_source_commit(root: str, head: str) -> str | None:
    for c in (gitops.git(root, "rev-list", "--first-parent", head) or "").split():
        if not _is_log_commit(root, c):
            return c
    return None


@_result
def merge_feature(ctx: Context, feature: str, guard_held: bool = False) -> dict:
    """최종 병합(사용자 실행): 꼬리 로그 커밋 → feature를 로컬 master에 --no-ff 병합 → 검증. push하지 않는다."""
    cfg = config.load(ctx)
    name, fid, task = _feature(cfg, feature)
    main, dev, remote = cfg["main"], cfg["develop"], cfg["remote"]
    root = ctx.root
    with _guard(ctx, guard_held):
        cur = _preflight(ctx)
        if cur == main:
            raise FlowStop("run_from_feature_or_develop")
        run = _load_run(ctx, "merge-feature", fid)
        _fetch(ctx, remote, main, dev)
        om = gitops.remote_ref(root, remote, main)
        head0 = gitops.rev(root, f"refs/heads/{name}")
        local_main = gitops.rev(root, f"refs/heads/{main}")
        direct = direct_source_commits(root, om, local_main, remote) if (om and local_main) else []
        warnings = [f"commit {c[:12]} master_direct_source_commit" for c in direct]
        if om:
            # 다시 실행하는 경우: 앞선 실행이 만든(아직 push 전) 이 feature의 병합·로그 커밋은 허용
            _align(ctx, main, om, cur, allow_ahead_log_only=True,
                   allow_ahead=lambda c: _is_log_commit(root, c) or (head0 and gitops.is_ancestor(root, c, head0))
                   or _is_feature_merge(root, c, name))
        main_before = gitops.rev(root, f"refs/heads/{main}")
        if not main_before:
            raise FlowStop("master_missing")
        # 최초 도입 여부는 원격 master 기준(앞선 실행이 로컬 master에 병합한 뒤 다시 실행해도 같게 판단)
        bootstrap = not config.has_config_in(root, om or main_before)
        if bootstrap and name != cfg["bootstrap_feature"]:
            raise FlowStop("master_without_logging_config")
        head = gitops.rev(root, f"refs/heads/{name}")
        if not head:
            raise FlowStop("feature_missing", name)
        base = (gitops.git(root, "merge-base", head, main_before) or "").strip()
        master_src = [p for _, p in gitops.diff_tree(root, base or None, main_before) if not rules.is_log_path(p)]
        if master_src:
            raise FlowStop("run_resume_first", f"master의 로그 밖 변경 {len(master_src)}개가 feature에 없음")
        code = _last_source_commit(root, head)
        dev_rev = gitops.rev(root, f"refs/heads/{dev}") or gitops.remote_ref(root, remote, dev)
        if not code:
            raise FlowStop("feature_has_no_source_commit")
        in_develop = bool(dev_rev and gitops.is_ancestor(root, code, dev_rev))
        # 일반 feature는 develop 최종 테스트를 거쳐야 한다. 최초 도입은 master에 검증 절차가 없으므로 예외.
        if not in_develop and not bootstrap:
            raise FlowStop("feature_code_not_in_develop", "feature의 마지막 코드 커밋이 develop에 병합되어 있어야 한다")
        develop_check = "included" if in_develop else "skipped_bootstrap"
        states = turns.effective_states(ctx)
        r = snapshot.commit_snapshots(ctx, task, "세션 로그 스냅샷 (최종 병합 전)", branch=name, states=states,
                                      extra_refs=(main_before,))
        if not r.get("ok"):
            raise FlowStop("tail_log_commit_failed", r.get("reason", ""), snapshot=r.get("snapshot"))
        warnings += _hold_warnings(r)
        head2 = gitops.rev(root, f"refs/heads/{name}")
        run["steps"]["tail_log_commit"] = head2
        _save_run(ctx, run)
        if gitops.is_ancestor(root, head2, main_before):
            return {"ok": True, "merged": False, "reason": "already_merged", "master": main_before,
                    "warnings": warnings}
        if develop_check == "included":
            check_line = f"  develop 검토 대상 코드 커밋 {code[:12]} 포함 확인 (테스트는 자동 검증하지 않음)\n"
        else:
            check_line = f"  develop 검증 생략(최초 도입: master에 검증 절차 없음), 코드 커밋 {code[:12]}\n"
        msg = (f"[{task}] {name} {main} 병합\n\n" + check_line
               + "  feature의 세션 로그 스냅샷 포함, --no-ff 병합\n")
        new = _plumb_merge(ctx, main_before, head2, msg)
        if _nonlog(root, new) != _nonlog(root, head2):
            raise FlowStop("merged_code_differs_from_feature")
        need = _bases(root, head2) | _bases(root, main_before)
        if not need <= _bases(root, new):
            raise FlowStop("merged_logs_missing")
        warnings += _final_log_checks(ctx, new)
        gitops.cas_update_branch(root, main, new, main_before, f"merge {name} --no-ff")
        run["steps"]["merge"] = new
        run["finished"] = utc_now()
        _save_run(ctx, run)
        return {"ok": True, "merged": True, "commit": new, "master_before": main_before, "bootstrap": bootstrap,
                "develop_check": develop_check, "warnings": warnings, "next": f"git push {remote} {main}  (사용자 실행)"}
