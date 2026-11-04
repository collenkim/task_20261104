"""불변 스냅샷과 로그 전용 커밋.

경로: ai-logs/snapshots/<tool>/<session-id>/<policy-version>/L<원본 바이트 20자리>-<sha256 앞 16자리>/
  manifest.json  + files/<원본 상대 경로>  (세션의 모든 파일 전체 사본, 마스킹만 적용)

- 같은 원본·같은 정책이면 같은 ID(폴더 이름)가 나온다. 생성 시각 같은 가변 값은 manifest에 넣지 않는다.
- 전체 sha256은 manifest의 snapshot_sha256에 기록한다(폴더 이름에는 앞 16자리만).
- 이미 있는 폴더는 고치지 않는다. 같은 ID인데 내용이 다르면 보류한다.
- 원본 길이가 같은데 내용(해시)이 다른 스냅샷은 '더 최신'으로 추정하지 않고 보류한다.
- 원본 앞부분 불변성(절단·재작성·삭제)은 이전 스냅샷·live·상태 기록 모두와 대조한다.
"""
from __future__ import annotations

import json
import os

from .common import (
    LARGE_FILE_BYTES,
    SCHEMA_VERSION,
    SNAPSHOTS_REL,
    Context,
    GitError,
    atomic_write,
    dumps_json,
    file_lock,
    read_json_ro,
    sha256,
    utc_now,
)
from .discover import Session, resolve_targets

TIME_BASIS = "UTC - 도구가 기록한 ISO 8601 timestamp 값을 그대로 사용"
ORDER_BASIS = "파일 안의 줄 순서 (타임스탬프로 재정렬하지 않음)"


# ------------------------------------------------------------------ identity
def _id_payload(m: dict) -> bytes:
    files = sorted(
        [e["path"], e["original_bytes"], e["original_sha256"], e["copy_sha256"]] for e in m.get("files", [])
    )
    payload = {
        "schema": m.get("schema"),
        "tool": m.get("tool"),
        "session_id": m.get("session_id"),
        "policy_version": m.get("policy_version"),
        "files": files,
    }
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def snapshot_sha256(m: dict) -> str:
    return sha256(_id_payload(m))


def snapshot_id(m: dict) -> str:
    """manifest 내용으로 ID를 다시 계산한다(경로를 믿지 않는다)."""
    total = sum(int(e["original_bytes"]) for e in m.get("files", []))
    return "L%020d-%s" % (total, snapshot_sha256(m)[:16])


def snap_base(tool: str, sid: str, policy: str, sid_: str) -> str:
    return f"{SNAPSHOTS_REL}/{tool}/{sid}/{policy}/{sid_}/"


# ------------------------------------------------------------ known snapshots
def worktree_manifests(ctx: Context) -> dict[str, dict]:
    """작업 폴더의 스냅샷 manifest {base 경로: manifest}. 읽을 수 없는 manifest는 base만 표시(None)."""
    out: dict[str, dict] = {}
    root = ctx.snapshots_dir
    if not os.path.isdir(root):
        return out
    for tool in sorted(os.listdir(root)):
        for sid in sorted(_dirs(os.path.join(root, tool))):
            for pol in sorted(_dirs(os.path.join(root, tool, sid))):
                for snap in sorted(_dirs(os.path.join(root, tool, sid, pol))):
                    m, st = read_json_ro(os.path.join(root, tool, sid, pol, snap, "manifest.json"))
                    out[snap_base(tool, sid, pol, snap)] = m if st == "ok" and isinstance(m, dict) else None
    return out


def _dirs(path: str) -> list[str]:
    try:
        return [d for d in os.listdir(path) if os.path.isdir(os.path.join(path, d))]
    except OSError:
        return []


def tree_manifests(root: str, treeish: str) -> dict[str, dict]:
    """커밋 트리 안의 스냅샷 manifest {base 경로: manifest}(Git blob에서 읽음)."""
    from . import gitops

    files = gitops.tree_files(root, treeish, SNAPSHOTS_REL)
    mans = {p: oid for p, (_, oid) in files.items() if p.endswith("/manifest.json")}
    blobs = gitops.cat_blobs(root, mans.values())
    out = {}
    for path, oid in mans.items():
        base = path[: -len("manifest.json")]
        try:
            out[base] = json.loads(blobs[oid].decode("utf-8"))
        except (KeyError, ValueError, UnicodeDecodeError):
            out[base] = None
    return out


def known_manifests(ctx: Context, refs=()) -> dict[str, dict]:
    """작업 폴더 + 주어진 커밋들의 스냅샷 manifest 합집합."""
    out = {}
    for ref in refs:
        try:
            out.update({k: v for k, v in tree_manifests(ctx.root, ref).items() if k not in out or out[k] is None})
        except GitError:
            continue
    for k, v in worktree_manifests(ctx).items():
        if k not in out or out[k] is None:
            out[k] = v
    return out


def by_session(manifests: dict[str, dict]) -> dict[str, list[tuple[str, dict]]]:
    out: dict[str, list] = {}
    for base, m in manifests.items():
        parts = base.rstrip("/").split("/")
        out.setdefault(f"{parts[-4]}/{parts[-3]}", []).append((base, m))
    return out


def latest(entries: list[tuple[str, dict]], policy_version: str):
    """정책별 최신 스냅샷: 원본 바이트가 가장 큰 것. 같은 길이에 다른 ID가 있으면 (None, 'ambiguous')."""
    cands = [(b, m) for b, m in entries if m and m.get("policy_version") == policy_version]
    if not cands:
        return None, "none"
    top = max(m.get("original_bytes_total", -1) for _, m in cands)
    tops = {m.get("snapshot_id") for _, m in cands if m.get("original_bytes_total") == top}
    if len(tops) > 1:
        return None, "ambiguous"
    return next((b, m) for b, m in cands if m.get("original_bytes_total") == top), "ok"


# ------------------------------------------------------------------- create
def _prefix_problems(rel: str, data: bytes, prior: list[tuple[int, str]]) -> list[str]:
    out = []
    for n, h in sorted(set(prior)):
        if len(data) < n:
            out.append("original_truncated")
        elif sha256(data[:n]) != h:
            out.append("original_rewritten")
    return sorted(set(out))


def _prior_knowledge(session_key: str, state: dict, snaps: list[tuple[str, dict]]) -> dict[str, list]:
    """파일별 (원본 바이트, 앞부분 sha256) 기록: 상태(live 수집) + 모든 이전 스냅샷."""
    prior: dict[str, list] = {}
    prefix = session_key + "/"
    for k, v in (state.get("files") or {}).items():
        if k.startswith(prefix) and isinstance(v.get("original_bytes"), int) and v.get("original_sha256"):
            prior.setdefault(k[len(prefix):], []).append((v["original_bytes"], v["original_sha256"]))
    for _, m in snaps:
        for e in (m or {}).get("files", []):
            if isinstance(e.get("original_bytes"), int) and e.get("original_sha256"):
                prior.setdefault(e["path"], []).append((e["original_bytes"], e["original_sha256"]))
    return prior


def build_snapshot(ctx: Context, s: Session, policy, state: dict, snaps: list[tuple[str, dict]],
                   idle: bool) -> dict:
    """세션 하나의 스냅샷을 준비한다(쓰지 않음). 결과: {status: ready|hold, manifest, copies, problems, ...}.

    개행 없는 마지막 JSONL 기록: 세션이 idle로 확인되고 그 꼬리가 JSON으로 파싱되면 '종료된 유효 기록'으로
    포함한다. 그 밖에는 마지막 개행까지만 담고 deferred_tail로 보고한다(최종 완전성 검사는 실패한다).
    """
    from .engine import check_masked, jsonl_stats, read_full

    problems, warnings, deferred = [], [], []
    prior = _prior_knowledge(s.key, state, snaps)
    entries, copies = [], {}
    current = {f.rel for f in s.files}
    for rel in sorted(set(prior) - current):
        problems.append({"rule": "original_file_deleted", "file": rel})
    main_stats = (0, None, None)
    for f in s.files:
        try:
            data = read_full(f)
        except OSError as e:
            problems.append({"rule": "original_unreadable", "file": f.rel, "detail": type(e).__name__})
            continue
        ends_open = False
        if f.rel.endswith(".jsonl") and data and not data.endswith(b"\n"):
            cut = data.rfind(b"\n") + 1
            tail = data[cut:]
            try:
                json.loads(tail)
                tail_ok = True
            except ValueError:
                tail_ok = False
            if idle and tail_ok:
                ends_open = True
            else:
                deferred.append({"file": f.rel, "bytes": len(tail), "reason": "unterminated_tail"})
                data = data[:cut]
        for rule in _prefix_problems(f.rel, data, prior.get(f.rel, [])):
            problems.append({"rule": rule, "file": f.rel})
        masked, counts = policy.mask(data)
        for p in check_masked(f.rel, data, masked, policy):
            problems.append(p)
        if len(data) > LARGE_FILE_BYTES:
            warnings.append({"rule": "large_file", "file": f.rel, "detail": f"bytes={len(data)}"})
        entry = {
            "path": f.rel,
            "role": f.role,
            "original_bytes": len(data),
            "original_sha256": sha256(data),
            "copy_bytes": len(masked),
            "copy_sha256": sha256(masked),
            "masked": dict(sorted(counts.items())),
        }
        if f.rel.endswith(".jsonl"):
            n, first, last, bad = jsonl_stats(data)
            entry["lines"] = n
            entry["ends_without_newline"] = ends_open
            if bad:
                entry["non_json_lines"] = bad[:50]
            if f.role == "main":
                main_stats = (n, first, last)
        entries.append(entry)
        copies[f.rel] = masked
    entries.sort(key=lambda e: (e["role"] != "main", e["path"]))
    n, first, last = main_stats
    m = {
        "schema": SCHEMA_VERSION,
        "kind": "snapshot",
        "tool": s.tool,
        "session_id": s.session_id,
        "policy_version": policy.version,
        "time_basis": TIME_BASIS,
        "order_basis": ORDER_BASIS,
        "first_timestamp": first,
        "last_timestamp": last,
        "main_lines": n,
        "original_bytes_total": sum(e["original_bytes"] for e in entries),
        "files": entries,
        "warnings": warnings,
    }
    m["snapshot_sha256"] = snapshot_sha256(m)
    m["snapshot_id"] = snapshot_id(m)
    # 같은 정책에서 같은 길이·다른 내용, 또는 더 긴 스냅샷이 이미 있는데 지금이 더 짧은 경우
    for base, old in snaps:
        if not old or old.get("policy_version") != policy.version:
            continue
        if old.get("original_bytes_total") == m["original_bytes_total"] and old.get("snapshot_id") != m["snapshot_id"]:
            problems.append({"rule": "same_length_different_snapshot", "detail": base})
    status = "hold" if problems else "ready"
    return {"key": s.key, "status": status, "manifest": m, "copies": copies, "problems": problems,
            "warnings": warnings, "deferred": deferred,
            "base": snap_base(s.tool, s.session_id, policy.version, m["snapshot_id"])}


def write_snapshot(ctx: Context, prep: dict) -> str:
    """준비된 스냅샷을 쓴다. 이미 있으면 내용이 같은지 확인만 한다. 반환: created|exists|conflict."""
    base_abs = os.path.join(ctx.root, *prep["base"].rstrip("/").split("/"))
    mpath = os.path.join(base_abs, "manifest.json")
    want = dumps_json(prep["manifest"])
    if os.path.exists(mpath):
        with open(mpath, "rb") as f:
            have = f.read()
        if have != want:
            return "conflict"
        for rel, data in prep["copies"].items():
            p = os.path.join(base_abs, "files", *rel.split("/"))
            try:
                with open(p, "rb") as f:
                    if f.read() != data:
                        return "conflict"
            except FileNotFoundError:
                return "conflict"
        return "exists"
    for rel, data in prep["copies"].items():
        atomic_write(os.path.join(base_abs, "files", *rel.split("/")), data)
    atomic_write(mpath, want)  # manifest를 마지막에 써서 '완성된 폴더'의 표시로 삼는다
    return "created"


def snapshot_all(ctx: Context, refs=("HEAD",), states: dict | None = None) -> dict:
    """대상 세션 전체 스냅샷. 보류가 하나라도 있으면 그 세션은 쓰지 않는다."""
    from .engine import build_policy, load_state

    with file_lock(ctx.lock_path, timeout=60):
        state = load_state(ctx)
        policy = build_policy(ctx)
        known = known_manifests(ctx, refs)
        grouped = by_session(known)
        targets = resolve_targets(ctx, state, extra_known=grouped.keys())
        report = {"time": utc_now(), "policy_version": policy.version, "sessions": [], "holds": [],
                  "warnings": [], "missing": [], "result": "ok"}
        if not os.path.exists(ctx.mask_path):
            report.update(result="error", error="mask_list_missing")
            return report
        for s in targets.sessions:
            idle = bool(states and states.get(s.key, {}).get("state") == "idle")
            prep = build_snapshot(ctx, s, policy, state, grouped.get(s.key, []), idle)
            row = {"key": s.key, "base": prep["base"], "status": prep["status"],
                   "original_bytes": prep["manifest"]["original_bytes_total"],
                   "main_lines": prep["manifest"]["main_lines"],
                   "last_timestamp": prep["manifest"]["last_timestamp"]}
            if prep["deferred"]:
                row["deferred"] = prep["deferred"]
            if prep["status"] == "ready":
                row["status"] = write_snapshot(ctx, prep)
                if row["status"] == "conflict":
                    report["holds"].append({"rule": "snapshot_dir_conflict", "session": s.key})
            else:
                report["holds"].extend(dict(p, session=s.key) for p in prep["problems"])
            report["warnings"].extend(dict(w, session=s.key) for w in prep["warnings"])
            report["sessions"].append(row)
        for key in targets.missing:
            has = bool(grouped.get(key))
            report["missing"].append({"session": key, "snapshot_kept": has})
        if report["holds"]:
            report["result"] = "hold"
        return report


# ------------------------------------------------------------- completeness
def completeness(ctx: Context, treeish: str | None, policy=None, read_originals: bool = True) -> dict:
    """커밋(또는 작업 폴더) 기준 제출 완전성.

    모든 대상 세션에 대해, 현재 정책의 최신 스냅샷이 원본 파일 전체(EOF)와 같은지 확인한다.
    failures가 비어 있어야 '완전'이다. 원본이 사라진 세션은 이전 스냅샷이 있으면 경고, 없으면 실패.
    """
    from .engine import build_policy, load_state

    policy = policy or build_policy(ctx)
    mans = tree_manifests(ctx.root, treeish) if treeish else worktree_manifests(ctx)
    grouped = by_session(mans)
    state = load_state(ctx)
    targets = resolve_targets(ctx, state, extra_known=grouped.keys())
    failures, warnings, rows = [], [], []
    for base, m in mans.items():
        if m is None:
            failures.append({"rule": "snapshot_manifest_unreadable", "detail": base})
    for s in targets.sessions:
        pick, how = latest(grouped.get(s.key, []), policy.version)
        if pick is None:
            failures.append({"rule": "snapshot_missing" if how == "none" else "snapshot_ambiguous", "session": s.key})
            continue
        b, m = pick
        listed = {e["path"]: e for e in m["files"]}
        cur = {f.rel: f for f in s.files}
        for rel in sorted(set(cur) - set(listed)):
            failures.append({"rule": "file_not_in_snapshot", "session": s.key, "file": rel})
        if read_originals:
            for rel, e in listed.items():
                f = cur.get(rel)
                if f is None:
                    failures.append({"rule": "original_file_deleted", "session": s.key, "file": rel})
                    continue
                try:
                    size = os.path.getsize(f.path)
                    with open(f.path, "rb") as fh:
                        head = fh.read(e["original_bytes"])
                except OSError:
                    failures.append({"rule": "original_unreadable", "session": s.key, "file": rel})
                    continue
                if size < e["original_bytes"] or sha256(head) != e["original_sha256"]:
                    failures.append({"rule": "original_changed", "session": s.key, "file": rel})
                elif size > e["original_bytes"]:
                    failures.append({"rule": "snapshot_behind_original", "session": s.key, "file": rel,
                                     "detail": f"bytes={size - e['original_bytes']}"})
        rows.append({"key": s.key, "snapshot": b, "original_bytes": m.get("original_bytes_total")})
    for key in targets.missing:
        if grouped.get(key):
            warnings.append({"rule": "original_missing_snapshot_kept", "session": key})
        else:
            failures.append({"rule": "original_missing_not_snapshotted", "session": key})
    if targets.unresolved:
        warnings.append({"rule": "unresolved_codex_files", "detail": f"count={targets.unresolved}"})
    return {"treeish": treeish or "worktree", "policy_version": policy.version, "sessions": rows,
            "failures": failures, "warnings": warnings, "complete": not failures}


def scan_tree_secrets(ctx: Context, treeish: str, policy=None) -> list[dict]:
    """커밋 트리의 모든 스냅샷 blob을 현재 정책으로 다시 검사한다(치환 목록 잔류·민감정보 후보).

    이미 커밋된 값은 새 마스킹 커밋으로 지워지지 않는다. 발견되면 게시를 멈추고 보고한다.
    """
    from . import gitops
    from .engine import build_policy

    policy = policy or build_policy(ctx)
    files = gitops.tree_files(ctx.root, treeish, SNAPSHOTS_REL)
    blobs = gitops.cat_blobs(ctx.root, {oid for p, (_, oid) in files.items() if "/files/" in p})
    out = []
    for path, (_, oid) in sorted(files.items()):
        if "/files/" not in path:
            continue
        data = blobs.get(oid, b"")
        if policy.residue(data):
            out.append({"rule": "committed_mask_residue", "file": path})
        for h in policy.detect(data):
            out.append({"rule": "committed_" + h.rule, "file": path, "line": h.line})
    return out


# ------------------------------------------------------------ live coverage
def live_covered(ctx: Context) -> dict:
    """live 사본 ⊆ 스냅샷: live 제외 정책으로 바꾸기 전 확인.

    각 live 파일에 대해 같은 세션의 최신 스냅샷(정책 무관, 원본 바이트 최대)이 live보다 길거나 같고,
    현재 원본 기준으로 두 범위가 모두 같은 원본의 앞부분이며, 같은 정책이면 스냅샷 사본이 live 사본으로 시작한다.
    """
    import glob

    snaps = by_session(worktree_manifests(ctx))
    gaps, checked = [], 0
    for mpath in glob.glob(os.path.join(ctx.live_dir, "*", "*", "manifest.json")):
        m, st = read_json_ro(mpath)
        if st != "ok":
            gaps.append({"rule": "live_manifest_unreadable", "detail": os.path.basename(os.path.dirname(mpath))})
            continue
        key = f"{m['tool']}/{m['session_id']}"
        cands = [x for x in snaps.get(key, []) if x[1]]
        for e in m["files"]:
            checked += 1
            best = None
            for base, sm in cands:
                se = next((x for x in sm["files"] if x["path"] == e["path"]), None)
                if se and se["original_bytes"] >= e["original_bytes"]:
                    if best is None or se["original_bytes"] > best[1]["original_bytes"]:
                        best = (base, se, sm)
            if best is None:
                gaps.append({"rule": "live_not_in_snapshot", "session": key, "file": e["path"]})
                continue
            base, se, sm = best
            live_copy = os.path.join(os.path.dirname(mpath), "files", *e["path"].split("/"))
            snap_copy = os.path.join(ctx.root, *base.rstrip("/").split("/"), "files", *e["path"].split("/"))
            if sm.get("policy_version") == m.get("policy_version"):
                with open(live_copy, "rb") as a, open(snap_copy, "rb") as b:
                    if not b.read().startswith(a.read()):
                        gaps.append({"rule": "snapshot_not_extending_live", "session": key, "file": e["path"]})
            # 정책이 다르면 원본 기준으로 대조: live 범위와 스냅샷 범위가 같은 원본의 앞부분이어야 한다
            orig = _original_path(ctx, key, e["path"])
            if orig:
                with open(orig, "rb") as f:
                    data = f.read(se["original_bytes"])
                if sha256(data[: e["original_bytes"]]) != e["original_sha256"] or sha256(data) != se["original_sha256"]:
                    gaps.append({"rule": "live_snapshot_original_mismatch", "session": key, "file": e["path"]})
            elif sm.get("policy_version") != m.get("policy_version"):
                gaps.append({"rule": "original_missing_cannot_compare", "session": key, "file": e["path"]})
    return {"files_checked": checked, "gaps": gaps, "covered": not gaps}


def _original_path(ctx: Context, key: str, rel: str):
    from .engine import load_state

    targets = resolve_targets(ctx, load_state(ctx))
    for s in targets.sessions:
        if s.key == key:
            for f in s.files:
                if f.rel == rel:
                    return f.path
    return None


# ---------------------------------------------------------------- commit
def _message(task: str, title: str, rows: list[dict], policy_version: str, added: list[str],
             held: dict | None = None) -> str:
    body = [f"  스냅샷 {len(added)}개 추가 (정책 {policy_version}, 세션 {len(rows)}개 확인)"]
    for r in rows:
        if r["base"] in added:
            body.append(f"  - {r['key']}: {r['base'].rstrip('/').split('/')[-1]}, {r['main_lines']}줄, "
                        f"마지막 {r['last_timestamp']}")
    for key, n in sorted((held or {}).items()):
        body.append(f"  - {key}: 보류 {n}건으로 이번 커밋 제외 (해소 뒤 다음 스냅샷에 이어서 반영)")
    return f"[{task}] {title}\n\n" + "\n".join(body) + "\n"


def commit_snapshots(ctx: Context, task: str, title: str, *, branch: str | None = None,
                     expect_branch: str | None = None, expect_head: str | None = None, states: dict | None = None,
                     require_complete: bool = False, extra_refs=()) -> dict:
    """브랜치에 로그 전용 커밋(부모 1개)을 만든다. 사용자 index·작업 폴더의 다른 항목은 바꾸지 않는다.

    branch를 주면 체크아웃하지 않은 브랜치에도 ref만 비교 후 교체(CAS)로 커밋한다(작업 폴더 전환 없음).
    중단 조건: 분리된 HEAD, 진행 중인 Git 작업, 스테이징된 변경, 예상과 다른 브랜치·HEAD.
    스냅샷 보류 세션은 중단 사유가 아니다: 그 세션만 빼고 커밋하고 결과의 held에 세션별 건수를 돌려준다.
    변경할 스냅샷이 없으면 커밋하지 않는다(빈 커밋 금지).
    """
    from . import gitops, rules

    root = ctx.root
    current = gitops.current_branch(root)
    target = branch or current
    if not target:
        return {"ok": False, "reason": "detached_head"}
    if expect_branch and current != expect_branch:
        return {"ok": False, "reason": "branch_changed", "detail": f"{expect_branch}->{current}"}
    head = gitops.rev(root, f"refs/heads/{target}")
    if expect_head and head != expect_head:
        return {"ok": False, "reason": "head_changed"}
    busy = gitops.in_progress(root)
    if busy:
        return {"ok": False, "reason": "git_operation_in_progress", "detail": ",".join(busy)}
    if target == current and gitops.staged_changes(root):
        return {"ok": False, "reason": "staged_changes_present"}
    if not rules.TASK_ID_RE.match(task or ""):
        return {"ok": False, "reason": "task_id_format", "detail": "예: SETUP-001"}
    from . import config

    mm = config.check_branch_task(ctx, task, target)
    if mm:
        return {"ok": False, "reason": "task_branch_mismatch", "detail": mm.split(":", 1)[1]}
    refs = tuple(r for r in ((head,) if head else ()) + tuple(extra_refs) if r)
    rep = snapshot_all(ctx, refs=refs, states=states)
    if rep["result"] not in ("ok", "hold"):
        return {"ok": False, "reason": f"snapshot_{rep['result']}", "snapshot": rep}
    # 보류(민감정보 후보·원본 이상) 세션은 이번 커밋에서 통째로 뺀다. 값은 게시되지 않는다.
    # 나머지 세션은 그대로 커밋한다. 보류 세션은 해소 뒤 다음 스냅샷(전체본)에 순서대로 이어서 쌓인다.
    held: dict[str, int] = {}
    for h in rep["holds"]:
        held[h["session"]] = held.get(h["session"], 0) + 1
    in_head = tree_manifests(root, head) if head else {}
    wt = worktree_manifests(ctx)
    current_bases = {r["base"] for r in rep["sessions"]}
    # 이번 실행의 최신 스냅샷 + 이전 실행에서 커밋하지 못한 온전한 폴더(같은 정책)를 함께 올린다.
    added = sorted(b for b, m in wt.items() if m is not None and b not in in_head
                   and f"{m.get('tool')}/{m.get('session_id')}" not in held
                   and (b in current_bases or m.get("policy_version") == rep["policy_version"]))
    if require_complete:
        comp = completeness(ctx, None)
        if not comp["complete"]:
            return {"ok": False, "reason": "not_complete", "completeness": comp}
    if not added:
        return {"ok": True, "committed": False, "reason": "nothing_to_commit", "commit": head, "snapshot": rep,
                "held": held}
    paths = []
    for b in added:
        base_abs = os.path.join(root, *b.rstrip("/").split("/"))
        for dirpath, _, fns in os.walk(base_abs):
            for fn in fns:
                paths.append(os.path.relpath(os.path.join(dirpath, fn), root).replace(os.sep, "/"))
    oids = gitops.hash_files(root, sorted(paths))
    msg = _message(task, title, rep["sessions"], rep["policy_version"], added, held)
    problems = rules.check_message(msg)
    if problems:
        return {"ok": False, "reason": "message_format", "detail": ",".join(problems)}
    commit, _ = gitops.build_commit(root, head, msg, add={p: ("100644", o) for p, o in oids.items()},
                                    state_dir=ctx.state_dir)
    changes = gitops.diff_tree(root, head, commit)
    files = gitops.tree_files(root, commit, SNAPSHOTS_REL)
    blob_cache = {}

    def blob(path):
        if path not in files:
            return None
        oid = files[path][1]
        if oid not in blob_cache:
            blob_cache.update(gitops.cat_blobs(root, [oid]))
        return blob_cache.get(oid)

    problems = rules.check_changes(changes, blob, allow_source=False)
    if problems:
        return {"ok": False, "reason": "log_commit_check_failed", "detail": ",".join(problems)}
    try:
        gitops.cas_update_branch(root, target, commit, head, "log snapshot commit")
    except GitError:
        return {"ok": False, "reason": "head_changed_during_commit"}
    if target == current:
        gitops.sync_index_paths(root, commit, sorted(paths))
    out = {"ok": True, "committed": True, "commit": commit, "branch": target, "snapshots": len(added),
           "sessions": len(rep["sessions"]), "files": len(paths), "missing": rep["missing"], "held": held}
    if held:
        out["snapshot"] = rep  # 보류 형태(값 없음)를 출력하기 위해
    return out


def submit_snapshots(ctx: Context, task: str | None = None, title: str = "세션 로그 제출") -> dict:
    """사용자가 모든 AI 응답이 끝난 뒤 실행하는 제출: 턴 보호 확인 → 전체 스냅샷 → 로그 전용 커밋."""
    from . import config, turns

    task = task or config.task_for_branch(ctx)
    if not task:
        return {"ok": False, "reason": "task_id_required", "detail": "feature 브랜치가 아니면 작업 ID를 지정"}
    with turns.guard_lock(ctx, timeout=20):
        states = turns.effective_states(ctx)
        busy = {k: v["state"] for k, v in states.items() if v["state"] != "idle"}
        if busy:
            return {"ok": False, "reason": "ai_turn_not_idle", "sessions": busy}
        return commit_snapshots(ctx, task, title, states=states)
