"""Git hook 검사(commit-msg, pre-push)와 런타임 설치.

- 검사는 읽기 전용이다: 파일·index·ref를 바꾸지 않는다(잠금도 잡지 않는다).
- pre-push는 쌓인 로그 이력을 깨는 경우와 마스킹 실패만 막고, 로그 뒤처짐은 경고만 한다(다음 push에 이어서 쌓임).
- 런타임: .git/ai-logging/runtime/ 에 hook 스크립트와 수집기 사본을 두고 core.hooksPath를 그 hooks로 지정한다.
  로깅 설정이 없는 브랜치(최초 도입 전 master 등)로 전환해도 hook이 사라지지 않는다.
  hook은 작업 폴더에 수집기가 있으면 그것을, 없으면 런타임 사본을 실행한다.
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile

from . import config, gitops, rules, snapshot, turns
from .common import Context, GitError, ensure_dir

HOOK_NAMES = ("commit-msg", "pre-push")


def _err(lines: list[str]) -> None:
    for ln in lines:
        sys.stderr.write("[AI 세션 로깅] " + ln + "\n")
    sys.stderr.flush()


def _index_blob_reader(root: str):
    files = gitops.index_files(root, ".")
    cache = {}

    def blob(path):
        if path not in files:
            return None
        oid = files[path][1]
        if oid not in cache:
            cache.update(gitops.cat_blobs(root, [oid]))
        return cache.get(oid)

    return blob


def _commit_blob_reader(root: str, commit: str):
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


def check_commit_msg(ctx: Context, msg_path: str) -> list[str]:
    with open(msg_path, "rb") as f:
        text = f.read().decode("utf-8", "replace")
    problems = [f"message:{p}" for p in rules.check_message(text)]
    busy = turns.busy_sessions(ctx)
    if busy:
        problems.append("ai_turn_not_idle:" + ",".join(f"{k.split('/')[0]}={v['state']}" for k, v in sorted(busy.items())))
    changes = gitops.staged_changes(ctx.root)
    problems += [f"staged:{p}" for p in rules.check_changes(changes, _index_blob_reader(ctx.root), allow_source=True)]
    return problems


def commit_msg(ctx: Context, msg_path: str) -> int:
    problems = check_commit_msg(ctx, msg_path)
    if problems:
        _err(["커밋을 막았습니다: " + "; ".join(problems),
              "메시지 형식: [서비스-작업ID] 제목 / 빈 줄 / 두 칸 들여쓴 작업 내용",
              "AI 턴 진행 중이면 예약 작업(cli.py task reserve ...)으로 요청하세요."])
        return 1
    return 0


def _tree_policy(ctx: Context, commit: str):
    """푸시하는 커밋의 허용 목록 + 로컬 치환 목록으로 정책을 만든다(브랜치 전환과 무관하게 같은 기준)."""
    from .policy import Allowlist, Policy, load_mask_entries

    files = gitops.tree_files(ctx.root, commit, "config/ai-logs-allowlist.json")
    raw = b""
    if files:
        oid = next(iter(files.values()))[1]
        raw = gitops.cat_blobs(ctx.root, [oid]).get(oid, b"")
    allow = Allowlist(json.loads(raw.decode("utf-8")) if raw else {}, raw)
    return Policy(load_mask_entries(ctx.mask_path), allow)


def check_pre_push(ctx: Context, remote: str, lines: list[str]) -> tuple[list[str], list[str]]:
    """(막는 문제, 경고)를 돌려준다.

    막는 것: 쌓인 로그 이력을 바꾸거나 지우는 커밋·금지 경로(치환 목록·live), 커밋 메시지 형식,
    커밋된 비밀값(마스킹 실패), master 삭제.
    경고만 하는 것: 스냅샷이 원본보다 뒤처짐·없음(다음 push에 담긴다), 병합 커밋 없이 들어온 소스 커밋,
    AI 턴 진행 중. 스냅샷은 세션마다 더 긴 전체본이 계속 쌓이므로 뒤처진 부분은 다음 게시 때 이어진다.
    """
    cfg = config.load(ctx)
    main_ref = f"refs/heads/{cfg['main']}"
    root = ctx.root
    problems, warnings = [], []
    for ln in lines:
        parts = ln.split()
        if len(parts) != 4:
            continue
        local_ref, local_oid, remote_ref, remote_oid = parts
        if local_oid == gitops.ZERO:
            if remote_ref == main_ref:
                problems.append("master_delete_refused")
            continue
        base = remote_oid if remote_oid != gitops.ZERO and gitops.rev(root, remote_oid) else None
        rng = [f"{base}..{local_oid}"] if base else [local_oid, "--not", f"--remotes={remote}"]
        commits = (gitops.git(root, "rev-list", *rng, timeout=60) or "").split()
        for c in commits:
            msg = gitops.git(root, "log", "-1", "--format=%B", c) or ""
            for p in rules.check_message(msg):
                problems.append(f"commit {c[:12]} message:{p}")
            ps = gitops.parents(root, c)
            if len(ps) <= 1:
                ch = gitops.diff_tree(root, ps[0] if ps else None, c)
                for p in rules.check_changes(ch, _commit_blob_reader(root, c), allow_source=True):
                    problems.append(f"commit {c[:12]} {p}")
        if remote_ref == main_ref:
            from .flows import direct_source_commits

            # master를 처음 만드는 push(원격에 master 없음)는 도입 전 이력이므로 검사하지 않는다.
            if remote_oid != gitops.ZERO:
                for c in direct_source_commits(root, base, local_oid, remote):
                    warnings.append(f"commit {c[:12]} master_direct_source_commit")
            if turns.busy_sessions(ctx):
                warnings.append("ai_turn_not_idle")
            pol = _tree_policy(ctx, local_oid)
            comp = snapshot.completeness(ctx, local_oid, pol)
            for f in comp["failures"][:20]:
                warnings.append(f"logs:{f['rule']} {f.get('session', '')} {f.get('file', '')}".rstrip())
            for f in snapshot.scan_tree_secrets(ctx, local_oid, pol)[:20]:
                problems.append(f"secret:{f['rule']} {f['file']}" + (f":{f['line']}" if "line" in f else ""))
    return problems, warnings


ADVICE = (
    ("logs:", "이번 push에 담기지 않은 대화는 다음 push(다음 스냅샷 커밋)에 이어서 쌓입니다. 지금 포함하려면 병합 전에 "
              "'python -I scripts/ai_logging/cli.py submit'으로 스냅샷을 커밋하세요."),
    ("master_direct_source_commit", "병합 커밋 없이 master에 들어온 소스 커밋입니다(IDE·git merge의 fast-forward 등). "
                                    "이력 구분을 위해 다음부터는 --no-ff 병합(또는 merge-feature)을 권장합니다."),
    ("ai_turn_not_idle", "AI 턴이 진행 중입니다. 그 턴의 대화는 다음 push에 담깁니다."),
    ("message:", "커밋 메시지 형식: [서비스-작업ID] 제목 / 빈 줄 / 두 칸 들여쓴 작업 내용"),
    ("secret:", "이미 커밋된 값이 현재 치환 목록·검출 규칙에 걸립니다. 새 마스킹 커밋으로 지워지지 않으므로 게시를 멈추고 "
                "이력 정리를 결정하세요."),
)


def _advice(items: list[str]) -> list[str]:
    return [text for key, text in ADVICE if any(key in p for p in items)]


def pre_push(ctx: Context, remote: str, stdin_text: str) -> int:
    problems, warnings = check_pre_push(ctx, remote, [x for x in stdin_text.splitlines() if x.strip()])
    if problems:
        _err(["push를 막았습니다:"] + ["  - " + p for p in problems[:40]] + _advice(problems))
        return 1
    if warnings:
        _err(["push 진행(참고):"] + ["  - " + p for p in warnings[:40]] + _advice(warnings))
    return 0


# -------------------------------------------------------------- runtime install
def runtime_dir(ctx: Context) -> str:
    return os.path.join(gitops.common_dir(ctx.root), "ai-logging", "runtime")


def install_runtime(ctx: Context) -> dict:
    """hook 스크립트·수집기 사본을 런타임 폴더에 복사하고 core.hooksPath를 지정한 뒤 실제 실행을 확인한다."""
    # 실행 중인 수집기 코드를 그대로 복사한다(작업 폴더 → 런타임).
    src_pkg = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src_hooks = os.path.join(ctx.root, ".githooks")
    if not os.path.isdir(src_hooks):
        src_hooks = os.path.join(os.path.dirname(os.path.dirname(src_pkg)), ".githooks")
    rt = runtime_dir(ctx)
    staging = rt + ".new"
    shutil.rmtree(staging, ignore_errors=True)
    shutil.copytree(src_pkg, os.path.join(staging, "ai_logging"),
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    ensure_dir(os.path.join(staging, "hooks"))
    for name in HOOK_NAMES:
        with open(os.path.join(src_hooks, name), "rb") as f:
            data = f.read().replace(b"\r\n", b"\n")
        dst = os.path.join(staging, "hooks", name)
        with open(dst, "wb") as f:
            f.write(data)
        os.chmod(dst, 0o755)
    old = rt + ".old"
    shutil.rmtree(old, ignore_errors=True)
    if os.path.isdir(rt):
        os.replace(rt, old)
    os.replace(staging, rt)
    shutil.rmtree(old, ignore_errors=True)
    hooks_path = os.path.join(rt, "hooks").replace("\\", "/")
    gitops.git_run(ctx.root, "config", "core.hooksPath", hooks_path)
    return dict({"hooks_path": hooks_path}, **verify_runtime(ctx))


def verify_runtime(ctx: Context) -> dict:
    """hook이 실제로 실행되는지 확인: 잘못된 메시지는 commit-msg가 거부해야 하고,
    빈 push 입력은 pre-push가 통과, master 삭제 입력은 거부해야 한다."""
    out = {"commit_msg_rejects_bad": False, "pre_push_runs": False, "pre_push_rejects_master_delete": False}
    hp = gitops.git(ctx.root, "config", "--get", "core.hooksPath") or ""
    out["hooks_path_set"] = bool(hp)
    with tempfile.TemporaryDirectory() as d:
        bad = os.path.join(d, "bad-msg")
        with open(bad, "wb") as f:
            f.write(b"bad message\n")
        r = gitops.git_run(ctx.root, "hook", "run", "commit-msg", "--", bad, check=False, timeout=120)
        out["commit_msg_rejects_bad"] = r.returncode != 0 and "title_format".encode() in r.stderr
        empty = os.path.join(d, "empty")
        open(empty, "wb").close()
        r = gitops.git_run(ctx.root, "hook", "run", f"--to-stdin={empty}", "pre-push", "--", "origin", "x",
                           check=False, timeout=120)
        out["pre_push_runs"] = r.returncode == 0
        dele = os.path.join(d, "delete")
        main = config.load(ctx)["main"]
        with open(dele, "wb") as f:
            f.write(f"(delete) {gitops.ZERO} refs/heads/{main} {'1' * 40}\n".encode())
        r = gitops.git_run(ctx.root, "hook", "run", f"--to-stdin={dele}", "pre-push", "--", "origin", "x",
                           check=False, timeout=120)
        out["pre_push_rejects_master_delete"] = r.returncode != 0 and b"master_delete_refused" in r.stderr
    out["ok"] = all(v for k, v in out.items() if k != "ok")
    return out


def runtime_status(ctx: Context) -> dict:
    try:
        rt = runtime_dir(ctx)
    except GitError:
        return {"installed": False}
    hp = gitops.git(ctx.root, "config", "--get", "core.hooksPath") or ""
    want = os.path.join(rt, "hooks").replace("\\", "/")
    return {"installed": os.path.isfile(os.path.join(rt, "hooks", "commit-msg")),
            "hooks_path_ok": hp == want, "hooks_path": hp}
