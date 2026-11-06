"""수집(sync)·검증(verify)·상태(status)·색인(index)·설치(install)·hook 진입점."""
from __future__ import annotations

import glob
import json
import os
import re
import sys
import time
import traceback

from .common import (
    LIVE_REL,
    SCHEMA_VERSION,
    SNAPSHOTS_REL,
    Context,
    GitError,
    LockTimeout,
    append_jsonl,
    atomic_write,
    dumps_json,
    ensure_dir,
    file_lock,
    git,
    is_within,
    read_json,
    read_json_ro,
    sha256,
    utc_now,
    write_json,
)
from .discover import Session, SessionFile, Targets, resolve_targets
from .policy import Policy, load_allowlist, load_mask_entries, shape
from . import metadata

# hook 이벤트 중 동기화를 실행하는 것. Codex SessionEnd는 제한 시간(최대 3초) 때문에 기록만 한다.
SYNC_EVENTS = {
    "claude": {"SessionStart", "Stop", "SessionEnd"},
    "codex": {"SessionStart", "Stop"},
}
MAX_REPORTED_HOLDS = 200


# --------------------------------------------------------------------- state
def _merge_manifest_knowledge(ctx: Context, files_state: dict) -> int:
    """live·스냅샷 manifest에 기록된 원본 길이·해시를 상태에 보충한다(읽기 전용).

    상태 파일이 손상·분실·과거본으로 돌아가도 원본 앞부분 불변성(재작성·절단) 검사를 잃지 않기 위해서다.
    더 긴(더 최근) 기록만 반영하고, 이렇게 보충한 항목은 증분 검증에 쓰지 않는다(policy=None).
    """
    merged = 0
    paths = glob.glob(os.path.join(ctx.live_dir, "*", "*", "manifest.json"))
    paths += glob.glob(os.path.join(ctx.snapshots_dir, "*", "*", "*", "*", "manifest.json"))
    for mpath in paths:
        m, status = read_json_ro(mpath)
        if status != "ok" or not isinstance(m, dict):
            continue
        live = m.get("kind") == "live"
        for e in m.get("files", []):
            if e.get("role") == metadata.ROLE:
                continue
            if e.get("original_missing") or not isinstance(e.get("original_bytes"), int):
                continue
            fkey = f"{m.get('tool')}/{m.get('session_id')}/{e.get('path')}"
            cur = files_state.get(fkey)
            if cur is None or cur.get("original_bytes", -1) < e["original_bytes"]:
                files_state[fkey] = {
                    "original_bytes": e["original_bytes"],
                    "original_sha256": e.get("original_sha256"),
                    "copy_sha256": e.get("copy_sha256") if live else None,
                    "policy": None,
                    "mask_digest": None,
                    "from": "live_manifest" if live else "snapshot_manifest",
                }
                merged += 1
    return merged


def load_state(ctx: Context) -> dict:
    """상태 읽기(파일을 바꾸지 않음). 손상·분실 시 빈 상태로 초기화하지 않는다.

    순서: state.json → state.json.prev → manifest 기록 보충. 결과의 "_load"는 저장하지 않는 진단 정보다.
    """
    st, status = read_json_ro(ctx.state_path)
    load = {"state_file": status}
    if status != "ok" or not isinstance(st, dict):
        if status == "ok":
            load["state_file"] = "corrupt"
        prev, pst = read_json_ro(ctx.state_prev_path)
        if load["state_file"] == "corrupt" and pst == "ok" and isinstance(prev, dict):
            st, load["recovered_from"] = prev, "state.json.prev"
        else:
            st = {}
            if load["state_file"] == "corrupt":
                load["recovered_from"] = "manifests"
    st.setdefault("schema", SCHEMA_VERSION)
    load["manifest_entries_merged"] = _merge_manifest_knowledge(ctx, st.setdefault("files", {}))
    st["_load"] = load
    return st


def save_state(ctx: Context, state: dict) -> None:
    """상태 저장: 직전 정상본을 .prev로 남기고, 손상본은 지우지 않고 복사해 둔다."""
    data = {k: v for k, v in state.items() if k != "_load"}
    cur, status = read_json_ro(ctx.state_path)
    if status == "ok" and isinstance(cur, dict):
        atomic_write(ctx.state_prev_path, dumps_json(cur))
    elif status == "corrupt":
        try:
            with open(ctx.state_path, "rb") as f:
                atomic_write(f"{ctx.state_path}.corrupt-{int(time.time())}", f.read())
        except OSError:
            pass
    write_json(ctx.state_path, data)


def build_policy(ctx: Context) -> Policy:
    return Policy(load_mask_entries(ctx.mask_path), load_allowlist(ctx.allowlist_path),
                  cache_dir=ctx.p("verification-cache"))


# ------------------------------------------------------------------- reading
def _stable_read(path: str) -> bytes:
    for _ in range(3):
        before = os.path.getsize(path)
        with open(path, "rb") as fh:
            data = fh.read()
        if os.path.getsize(path) == before == len(data):
            return data
        time.sleep(0.2)
    raise OSError("unstable_file")


def read_complete(f: SessionFile) -> bytes:
    """live 수집용: 완결된 부분만. JSONL은 마지막 개행까지(작성 중인 줄은 다음 수집으로 미룸)."""
    if f.rel.endswith(".jsonl"):
        with open(f.path, "rb") as fh:
            data = fh.read()
        cut = data.rfind(b"\n")
        return data[: cut + 1] if cut >= 0 else b""
    return _stable_read(f.path)


def read_full(f: SessionFile) -> bytes:
    """최종 검사·스냅샷용: 실제 파일 끝(EOF)까지."""
    return _stable_read(f.path)


def _session_dir(ctx: Context, tool: str, sid: str) -> str:
    return os.path.join(ctx.live_dir, tool, sid)


def _copy_path(ctx: Context, s_tool: str, sid: str, rel: str) -> str:
    return os.path.join(_session_dir(ctx, s_tool, sid), "files", *rel.split("/"))


def _read_bytes(path: str):
    try:
        with open(path, "rb") as f:
            return f.read()
    except FileNotFoundError:
        return None


def jsonl_stats(data: bytes):
    """줄 수, 첫·마지막 최상위 timestamp(파일 순서 기준), 파싱 실패 줄 번호."""
    first = last = None
    bad = []
    lines = data.splitlines()
    for i, line in enumerate(lines, 1):
        try:
            o = json.loads(line)
        except ValueError:
            bad.append(i)
            continue
        ts = o.get("timestamp") if isinstance(o, dict) else None
        if isinstance(ts, str):
            first = first or ts
            last = ts
    return len(lines), first, last, bad


def _problem(rule, file=None, line=None, detail=""):
    p = {"rule": rule}
    if file is not None:
        p["file"] = file
    if line is not None:
        p["line"] = line
    if detail:
        p["detail"] = detail
    return p


def check_masked(rel: str, data: bytes, masked: bytes, policy: Policy, scan_from: int = 0,
                 old_lines: int = 0) -> list[dict]:
    """마스킹 결과 검사: 줄 수 유지, JSON 유효성 유지, 바이너리, 민감정보 후보, 치환 목록 잔류."""
    problems = []
    if masked.count(b"\n") != data.count(b"\n"):
        problems.append(_problem("mask_changed_line_count", rel))
    if rel.endswith(".jsonl"):
        for i, (o_line, m_line) in enumerate(zip(data.splitlines(), masked.splitlines()), 1):
            try:
                json.loads(o_line)
            except ValueError:
                continue  # 원본 자체가 JSON이 아닌 줄은 그대로 보존(검증 보고에만 표시)
            try:
                json.loads(m_line)
            except ValueError:
                problems.append(_problem("mask_broke_json", rel, i))
    else:
        try:
            data.decode("utf-8")
        except UnicodeDecodeError:
            if sha256(data) not in policy.allow.image_hashes:
                problems.append(_problem("binary_attachment", rel, detail=f"sha256={sha256(data)}"))
    for h in policy.detect(masked[scan_from:]):
        problems.append(_problem(h.rule, rel, h.line + old_lines, h.detail))
    if policy.residue(masked):
        problems.append(_problem("mask_residue", rel))
    return problems


# ------------------------------------------------------------------------ sync
def _process_session(ctx: Context, s: Session, policy: Policy, state: dict, snaps=()) -> dict:
    files_state = state.setdefault("files", {})
    sess_dir = _session_dir(ctx, s.tool, s.session_id)
    manifest_path = os.path.join(sess_dir, "manifest.json")
    old_manifest, mstatus = read_json_ro(manifest_path)
    problems, prepared = [], []
    if mstatus == "corrupt":
        # 손상 manifest는 덮어쓰지 않는다. 'repair' 명령으로 확인 후 복구한다.
        problems.append(_problem("live_manifest_unreadable"))
        old_manifest = None

    meta_data = {}
    for f in s.files:
        if metadata.eligible(s.tool, s.session_id, f.rel):
            try:
                meta_data[f.rel] = read_complete(f)
            except OSError:
                pass  # 일반 파일 처리에서 같은 오류를 보고한다.
    manifests = [(sess_dir, old_manifest)] if old_manifest else []
    manifests.extend((os.path.join(ctx.root, *base.rstrip("/").split("/")), m) for base, m in snaps)
    history = metadata.prepare(ctx, s, policy, meta_data, state, manifests)
    problems.extend(history["problems"])

    for f in s.files:
        fkey = f"{s.key}/{f.rel}"
        try:
            data = meta_data[f.rel] if f.rel in meta_data else read_complete(f)
        except OSError as e:
            problems.append(_problem("original_unreadable", f.rel, detail=type(e).__name__))
            continue
        prev = files_state.get(fkey)
        if prev and isinstance(prev.get("original_bytes"), int):
            if len(data) < prev["original_bytes"]:
                problems.append(_problem("original_truncated", f.rel))
                continue
            if (sha256(data[: prev["original_bytes"]]) != prev.get("original_sha256")
                    and not metadata.matches(history["originals"].get(f.rel), prev)):
                problems.append(_problem("original_rewritten", f.rel))
                continue
        masked, counts = policy.mask(data)
        # 같은 정책으로 이미 검증한 앞부분은 다시 검사하지 않는다(완결된 줄 단위인 JSONL만).
        # 그 밖의 부속 파일은 경계에 걸친 값을 놓치지 않도록 매번 전체를 검사한다.
        scan_from, old_lines = 0, 0
        if f.rel.endswith(".jsonl") and prev:
            old_copy = _read_bytes(_copy_path(ctx, s.tool, s.session_id, f.rel))
            if (
                old_copy is not None
                and prev.get("policy") == policy.version
                and prev.get("mask_digest") == policy.mask_digest
                and sha256(old_copy) == prev.get("copy_sha256")
                and (not old_copy or old_copy.endswith(b"\n"))
                and masked.startswith(old_copy)
            ):
                scan_from, old_lines = len(old_copy), old_copy.count(b"\n")
        problems.extend(check_masked(f.rel, data, masked, policy, scan_from, old_lines))
        prepared.append((f, fkey, data, masked, counts))

    summary = {"key": s.key, "tool": s.tool, "session_id": s.session_id}
    if problems:
        published = sum((files_state.get(f"{s.key}/{f.rel}") or {}).get("original_bytes", 0) for f in s.files)
        current = sum(len(d) for _, _, d, _, _ in prepared)
        summary.update(status="hold", pending_bytes=max(0, current - published), problems=len(problems))
        return {"summary": summary, "holds": [dict(p, session=s.key) for p in problems]}

    # 과거 live 사본 보관이 완료되기 전에는 어느 현재 사본도 교체하지 않는다.
    metadata.write_archives(ctx, s, history["archives"])
    entries, changed = list(history["entries"]), False
    for rel, data in history["copies"].items():
        path = _copy_path(ctx, s.tool, s.session_id, rel)
        if _read_bytes(path) != data:
            atomic_write(path, data)
            changed = True
    main_stats = None
    for f, fkey, data, masked, counts in prepared:
        cpath = _copy_path(ctx, s.tool, s.session_id, f.rel)
        if _read_bytes(cpath) != masked:
            atomic_write(cpath, masked)
            changed = True
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
            if bad:
                entry["non_json_lines"] = bad[:50]
            if f.role == "main":
                main_stats = (n, first, last)
        entries.append(entry)
        files_state[fkey] = {
            "original_bytes": len(data),
            "original_sha256": entry["original_sha256"],
            "copy_sha256": entry["copy_sha256"],
            "policy": policy.version,
            "mask_digest": policy.mask_digest,
        }
    # 원본에서 사라진 부속 파일은 사본을 지우지 않고 표시만 한다.
    current_paths = {e["path"] for e in entries}
    for old in (old_manifest or {}).get("files", []):
        if old["path"] not in current_paths:
            entries.append(dict(old, original_missing=True))
    entries.sort(key=lambda e: (e["role"] != "main", e["path"]))
    n, first, last = main_stats or (0, None, None)
    manifest = {
        "schema": SCHEMA_VERSION,
        "kind": "live",
        "tool": s.tool,
        "session_id": s.session_id,
        "policy_version": policy.version,
        "time_basis": "UTC - 도구가 기록한 ISO 8601 timestamp 값을 그대로 사용",
        "order_basis": "파일 안의 줄 순서 (타임스탬프로 재정렬하지 않음)",
        "first_timestamp": first,
        "last_timestamp": last,
        "main_lines": n,
        "original_bytes_total": sum(e["original_bytes"] for e in entries if not e.get("original_missing")),
        "files": entries,
    }
    if _read_bytes(manifest_path) != dumps_json(manifest):
        write_json(manifest_path, manifest)
        changed = True
    summary.update(
        status="updated" if changed else "unchanged",
        lines=n,
        first_timestamp=first,
        last_timestamp=last,
        original_bytes=manifest["original_bytes_total"],
        files=len(entries),
    )
    return {"summary": summary, "holds": []}


def sync(ctx: Context, lock_timeout: float = 60.0) -> dict:
    from . import snapshot
    ensure_dir(ctx.state_dir)
    with file_lock(ctx.lock_path, timeout=lock_timeout):
        state = load_state(ctx)
        policy = build_policy(ctx)
        targets = resolve_targets(ctx, state)
        grouped = snapshot.by_session(snapshot.worktree_manifests(ctx))
        report = {
            "time": utc_now(),
            "policy_version": policy.version,
            "mask_list_present": os.path.exists(ctx.mask_path),
            "state_load": state.get("_load"),
            "sessions": [],
            "holds": [],
            "holds_total": 0,
            "errors": [],
            "unresolved_codex_files": targets.unresolved,
            "missing_sessions": targets.missing,
        }
        if not report["mask_list_present"]:
            # 치환 목록 없이 사본을 만들면 계정 식별자가 그대로 남는다. 아무것도 쓰지 않는다.
            report["errors"].append({"error": "mask_list_missing", "where": "install 명령을 먼저 실행"})
            report["result"] = "error"
            write_json(ctx.last_sync_path, report)
            return report
        known = state.setdefault("known_sessions", {})
        for s in targets.sessions:
            info = known.setdefault(s.key, {"first_seen": utc_now()})
            info["main"] = s.main_path
            try:
                res = _process_session(ctx, s, policy, state, grouped.get(s.key, []))
            except Exception as e:  # 세션 하나의 실패가 다른 세션 수집을 막지 않게 한다.
                report["errors"].append({"session": s.key, "error": type(e).__name__, "where": _where(e)})
                continue
            report["sessions"].append(res["summary"])
            report["holds_total"] += len(res["holds"])
            room = MAX_REPORTED_HOLDS - len(report["holds"])
            report["holds"].extend(res["holds"][: max(0, room)])
        for key in targets.missing:
            known.setdefault(key, {"first_seen": utc_now()})
            report["sessions"].append({"key": key, "status": "original_missing"})
        report["result"] = "error" if report["errors"] else ("hold" if report["holds_total"] else "ok")
        state["last_sync"] = report["time"]
        save_state(ctx, state)
        write_json(ctx.last_sync_path, report)
        return report


# ---------------------------------------------------------------------- verify
def _list_files(base: str) -> set[str]:
    out = set()
    for dirpath, _, filenames in os.walk(base):
        for fn in filenames:
            out.add(os.path.relpath(os.path.join(dirpath, fn), base).replace(os.sep, "/"))
    return out


def tail_status(f: SessionFile, data_full: bytes, collected: int) -> str | None:
    """수집 범위 뒤에 남은 원본 꼬리의 종류. None이면 남은 것 없음."""
    extra = data_full[collected:]
    if not extra:
        return None
    if f.rel.endswith(".jsonl") and b"\n" not in extra:
        return "incomplete_tail"
    return "behind_original"


def verify(ctx: Context, final: bool = False) -> dict:
    """읽기 전용 live 검사. 파일·상태·manifest를 바꾸지 않는다.

    - 사본 = 원본 앞부분 또는 검증된 이전 메타데이터의 마스킹 결과, 민감정보·치환 목록 잔류 없음
    - manifest에 없는 파일(a), 원본 꼬리(b), 대상 세션 합집합의 누락·원본 부재(c), manifest 손상(e)
    final=True면 원본보다 뒤처진 사본·미완성 꼬리·정책 변경도 실패로 본다.
    """
    from . import snapshot

    policy = build_policy(ctx)
    state = load_state(ctx)
    targets = resolve_targets(ctx, state)
    by_key = {s.key: s for s in targets.sessions}
    failures, warnings = [], []
    soft = failures if final else warnings
    manifests = {}
    if os.path.isdir(ctx.live_dir):
        for tool in sorted(os.listdir(ctx.live_dir)):
            tdir = os.path.join(ctx.live_dir, tool)
            if not os.path.isdir(tdir):
                continue
            for sid in sorted(os.listdir(tdir)):
                sdir = os.path.join(tdir, sid)
                if not os.path.isdir(sdir):
                    continue
                key = f"{tool}/{sid}"
                m, status = read_json_ro(os.path.join(sdir, "manifest.json"))
                if status == "missing":
                    failures.append(dict(_problem("manifest_missing"), session=key))
                elif status != "ok" or not isinstance(m, dict) or "files" not in m:
                    failures.append(dict(_problem("manifest_unreadable"), session=key))
                else:
                    manifests[key] = m
    if not os.path.exists(ctx.mask_path):
        failures.append(_problem("mask_list_missing"))
    for key in targets.missing:
        soft.append(dict(_problem("original_missing"), session=key))
    bad_manifest = {f.get("session") for f in failures}
    for key in sorted(set(by_key) - set(manifests) - bad_manifest):
        failures.append(dict(_problem("not_collected"), session=key))
    if targets.unresolved:
        warnings.append(_problem("unresolved_codex_files", detail=f"count={targets.unresolved}"))
    checked = 0
    snapshots = snapshot.by_session(snapshot.worktree_manifests(ctx))
    for key, m in manifests.items():
        s = by_key.get(key)
        if m.get("policy_version") != policy.version:
            soft.append(dict(_problem("policy_version_changed"), session=key))
        orig_files = {f.rel: f for f in (s.files if s else [])}
        listed = {e["path"] for e in m["files"]}
        if s:
            meta_data = {}
            for rel, f in orig_files.items():
                if metadata.eligible(s.tool, s.session_id, rel):
                    try:
                        meta_data[rel] = read_full(f)
                    except OSError:
                        pass
            bases = [(_session_dir(ctx, s.tool, s.session_id), m)]
            bases.extend((os.path.join(ctx.root, *base.rstrip("/").split("/")), sm)
                         for base, sm in snapshots.get(key, []))
            history = metadata.prepare(ctx, s, policy, meta_data, state, bases)
            failures.extend(dict(p, session=key) for p in history["problems"])
            for entry in history["entries"]:
                if entry["path"] not in listed:
                    failures.append(dict(_problem("metadata_history_not_collected", entry["path"]), session=key))
        for rel in sorted(set(orig_files) - listed):
            soft.append(dict(_problem("attachment_not_collected", rel), session=key))
        actual = _list_files(os.path.join(ctx.live_dir, *key.split("/"), "files"))
        for rel in sorted(actual - listed):
            failures.append(dict(_problem("unlisted_file", rel), session=key))
        for e in m["files"]:
            checked += 1
            copy = _read_bytes(_copy_path(ctx, m["tool"], m["session_id"], e["path"]))
            if copy is None or sha256(copy) != e["copy_sha256"]:
                failures.append(dict(_problem("copy_hash_mismatch", e["path"]), session=key))
                continue
            historic = e.get("role") == metadata.ROLE
            rel = metadata.source_path(m["tool"], m["session_id"], e) if historic else e["path"]
            f = orig_files.get(rel)
            if f is None or e.get("original_missing"):
                soft.append(dict(_problem("original_missing", e["path"]), session=key))
                continue
            try:
                data = read_full(f)
            except OSError:
                failures.append(dict(_problem("original_unreadable", e["path"]), session=key))
                continue
            if historic:
                data = metadata.original_data(m["tool"], m["session_id"], e, data)
                if data is None:
                    failures.append(dict(_problem("metadata_history_source_changed", e["path"]), session=key))
                    continue
            if len(data) < e["original_bytes"]:
                failures.append(dict(_problem("original_truncated", e["path"]), session=key))
                continue
            prefix = data[: e["original_bytes"]]
            if sha256(prefix) != e["original_sha256"]:
                failures.append(dict(_problem("original_rewritten", e["path"]), session=key))
                continue
            if policy.mask(prefix)[0] != copy:
                failures.append(dict(_problem("copy_not_masked_original", e["path"]), session=key))
            for h in policy.detect(copy):
                failures.append(dict(_problem(h.rule, e["path"], h.line, h.detail), session=key))
            if policy.residue(copy):
                failures.append(dict(_problem("mask_residue", e["path"]), session=key))
            tail = None if historic else tail_status(f, data, e["original_bytes"])
            if tail:
                soft.append(dict(_problem(tail, e["path"], detail=f"bytes={len(data) - e['original_bytes']}"),
                                 session=key))
    return {
        "time": utc_now(),
        "final": final,
        "sessions_discovered": len(by_key),
        "sessions_missing": len(targets.missing),
        "sessions_collected": len(manifests),
        "files_checked": checked,
        "failures": failures,
        "warnings": warnings,
        "ok": not failures,
    }


# ----------------------------------------------------------------------- index
def build_index(ctx: Context, write: bool = True) -> list[dict]:
    rows = []
    for mpath in glob.glob(os.path.join(ctx.live_dir, "*", "*", "manifest.json")):
        m = read_json(mpath, None)
        if not m:
            continue
        rows.append(
            {
                "tool": m["tool"],
                "session_id": m["session_id"],
                "first_timestamp": m.get("first_timestamp"),
                "last_timestamp": m.get("last_timestamp"),
                "main_lines": m.get("main_lines"),
                "files": len(m.get("files", [])),
                "path": os.path.relpath(os.path.dirname(mpath), ctx.root).replace(os.sep, "/"),
            }
        )
    rows.sort(key=lambda r: (r["first_timestamp"] or "9999", r["tool"], r["session_id"]))
    if write:
        write_json(ctx.index_path, {"time": utc_now(), "time_basis": "UTC", "sessions": rows})
    return rows


# --------------------------------------------------------------------- install
_EMAIL_TEXT_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")


def extract_mask_values(ctx: Context, sessions: list[Session]) -> list[dict]:
    """원본에서 자동으로 기록되는 식별자를 찾아 치환 목록 후보를 만든다(값은 출력하지 않는다)."""
    found: dict[tuple, dict] = {}

    def add(kind, value, source):
        if isinstance(value, str) and len(value) >= 2:
            found.setdefault((kind, value), {"kind": kind, "value": value, "source": source})

    def add_email(value, source):
        add("literal", value, source)
        local = value.split("@", 1)[0]
        if len(local) >= 5:
            add("literal", local, source + ".local-part")

    for s in sessions:
        try:
            with open(s.main_path, "rb") as fh:
                lines = [fh.readline()] if s.tool == "codex" else fh.readlines()
        except OSError:
            continue
        for raw in lines:
            try:
                o = json.loads(raw)
            except ValueError:
                continue
            if not isinstance(o, dict):
                continue
            if s.tool == "codex" and o.get("type") == "session_meta":
                p = o.get("payload") or {}
                add("literal", p.get("creator_account_id"), "codex.session_meta.creator_account_id")
                add("literal", p.get("creator_user_id"), "codex.session_meta.creator_user_id")
            elif s.tool == "claude":
                t = o.get("type")
                if t == "session-owner":
                    add("literal", o.get("accountUuid"), "claude.session-owner.accountUuid")
                    add("literal", o.get("organizationUuid"), "claude.session-owner.organizationUuid")
                a = o.get("attachment") if isinstance(o.get("attachment"), dict) else None
                if a and a.get("type") == "session_context":
                    for em in _EMAIL_TEXT_RE.findall(json.dumps(a, ensure_ascii=False)):
                        add_email(em, "claude.session_context.email")
                if a and a.get("type") == "credential_org":
                    add("literal", a.get("organizationUuid"), "claude.credential_org.organizationUuid")
    git_email = git(ctx.root, "config", "user.email")
    if git_email and "@" in git_email:
        add_email(git_email, "git.user.email")
    home_user = os.path.basename(os.path.normpath(os.path.expanduser("~")))
    add("home_user", home_user, "os.home_directory_name")
    # Windows 로그인 이름이 홈 폴더 이름과 다를 수 있다(USERNAME / USER 환경 변수)
    for var in ("USERNAME", "USER"):
        name = os.environ.get(var)
        if name and name.lower() != home_user.lower():
            add("home_user", name, f"os.env.{var}")
    return list(found.values())


def merge_mask_values(ctx: Context) -> dict:
    ensure_dir(ctx.state_dir)
    state = load_state(ctx)
    targets = resolve_targets(ctx, state)
    existing = load_mask_entries(ctx.mask_path)
    have = {(e.get("kind"), e.get("value")) for e in existing}
    new = [e for e in extract_mask_values(ctx, targets.sessions) if (e["kind"], e["value"]) not in have]
    if new or not os.path.exists(ctx.mask_path):
        merged = existing + new  # 기존 항목은 지우지 않는다(합집합).
        atomic_write(ctx.mask_path, "".join(json.dumps(e, ensure_ascii=False) + "\n" for e in merged).encode())
    by_source: dict[str, int] = {}
    for e in existing + new:
        by_source[e.get("source", "manual")] = by_source.get(e.get("source", "manual"), 0) + 1
    return {"added": len(new), "total": len(existing) + len(new), "by_source": by_source}


def install(ctx: Context) -> dict:
    """치환 목록 확정(합집합) + 로컬 제외 설정. Git hook 설치는 setup.install_runtime."""
    ensure_dir(ctx.live_dir)
    ensure_local_exclude(ctx)
    return merge_mask_values(ctx)


LOCAL_EXCLUDES = (f"/{LIVE_REL}/", f"/{SNAPSHOTS_REL}/", "/.ai-logging/")


def ensure_local_exclude(ctx: Context) -> bool:
    """로컬 전용 제외(.git/info/exclude) 등록. 브랜치와 무관하게 유지된다.

    - live 사본·로컬 상태(.ai-logging: 치환 목록 포함)가 어느 브랜치에서도 `git add -A`에 섞이지 않게 한다
      (로깅 설정이 없는 master로 전환해도 유지).
    - 스냅샷은 로그 전용 커밋 명령이 blob을 직접 만들어 커밋한다. 커밋된 스냅샷은 추적 파일이라 제외 규칙과
      무관하게 계속 추적되고, 커밋 전 폴더는 일반 `git add`나 병합의 미추적 파일 충돌에 섞이지 않는다.
    """
    rel = git(ctx.root, "rev-parse", "--git-path", "info/exclude")
    if not rel:
        return False
    path = rel if os.path.isabs(rel) else os.path.join(ctx.root, rel)
    try:
        with open(path, "rb") as f:
            text = f.read().decode("utf-8", "replace")
    except FileNotFoundError:
        text = ""
    have = set(text.splitlines())
    add = [x for x in LOCAL_EXCLUDES if x not in have]
    if not add:
        return True
    ensure_dir(os.path.dirname(path))
    with open(path, "ab") as f:
        f.write((("" if not text or text.endswith("\n") else "\n") + "".join(x + "\n" for x in add)).encode())
    return True


def repair_live(ctx: Context, key: str) -> dict:
    """사용자 복구 명령: 손상된 live manifest를 지우지 않고 보관 이름으로 옮긴다. 다음 sync가 원본에서 다시 쓴다.

    verify·hook은 이 작업을 하지 않는다(읽기와 복구 분리).
    """
    parts = key.split("/")
    if len(parts) != 2 or parts[0] not in ("claude", "codex") or not parts[1] or parts[1] in (".", ".."):
        return {"ok": False, "reason": "key_format"}
    mpath = os.path.join(ctx.live_dir, parts[0], parts[1], "manifest.json")
    with file_lock(ctx.lock_path, timeout=60):
        _, status = read_json_ro(mpath)
        if status != "corrupt":
            return {"ok": False, "reason": f"manifest_{status}"}
        dst = f"{mpath}.corrupt-{int(time.time())}"
        os.replace(mpath, dst)
    return {"ok": True, "moved_to": os.path.basename(dst)}


# ------------------------------------------------------- held candidates
def held_candidates(ctx: Context) -> tuple[str, list[dict]]:
    """마스킹 후에도 검출되는 후보를 값별로 묶는다.

    반환하는 각 묶음의 "value"는 mask_held 내부에서만 쓴다. 출력·파일에는 형태 정보만 쓴다.
    목록 지문(list_id)은 번호를 고른 뒤 목록이 바뀌었는지 확인하는 용도다.
    """
    state = load_state(ctx)
    policy = build_policy(ctx)
    groups: dict[tuple, dict] = {}
    for s in resolve_targets(ctx, state).sessions:
        for f in s.files:
            try:
                data = read_full(f)
            except OSError:
                continue
            for h in policy.detect(policy.mask(data)[0]):
                gid = (h.rule, h.value) if h.value else (h.rule, f"{s.key}/{f.rel}:{h.line}".encode())
                g = groups.setdefault(
                    gid,
                    {
                        "rule": h.rule,
                        "value": h.value,
                        "maskable": bool(h.value),
                        "length": len(h.value),
                        "shape": shape(h.value) if h.value else "-",
                        "key": next((p[4:] for p in h.detail.split() if p.startswith("key=")), "-"),
                        "at": [],
                    },
                )
                where = s.session_id[:8] if f.path == s.main_path else f"{s.session_id[:8]}/{f.rel}"
                g["at"].append(f"{s.tool}/{where}:{h.line}")
    items = sorted(groups.values(), key=lambda g: (g["rule"], g["at"][0]))
    for i, g in enumerate(items, 1):
        g["no"] = i
    list_id = sha256("\n".join(f"{g['rule']}|{sha256(g['value'] or b'')}|{g['at'][0]}" for g in items).encode())[:8]
    return list_id, items


def mask_held(ctx: Context, list_id: str, numbers: list[int]) -> dict:
    """사람이 고른 보류 후보 값을 치환 목록에 추가한다(값은 출력하지 않음). 이후 sync가 다시 마스킹한다."""
    current_id, items = held_candidates(ctx)
    if list_id != current_id:
        return {"ok": False, "reason": "list_changed", "detail": "holds 목록을 다시 확인한 뒤 번호를 고르세요"}
    by_no = {g["no"]: g for g in items}
    bad = [n for n in numbers if n not in by_no or not by_no[n]["maskable"]]
    if bad or not numbers:
        return {"ok": False, "reason": "bad_numbers", "detail": ",".join(map(str, bad))}
    existing = load_mask_entries(ctx.mask_path)
    have = {(e.get("kind"), e.get("value")) for e in existing}
    new = []
    for n in numbers:
        g = by_no[n]
        e = {"kind": "literal", "value": g["value"].decode("utf-8", "replace"), "source": f"review.{g['rule']}"}
        if (e["kind"], e["value"]) not in have:
            have.add((e["kind"], e["value"]))
            new.append(e)
    if new:
        merged = existing + new
        atomic_write(ctx.mask_path, "".join(json.dumps(e, ensure_ascii=False) + "\n" for e in merged).encode())
    return {"ok": True, "added": len(new), "total": len(existing) + len(new)}


# ---------------------------------------------------------------------- submit
def submit(ctx: Context, task: str | None = None, title: str = "세션 로그 제출") -> dict:
    """최종 제출 로그 커밋. 구현은 snapshot.submit_snapshots(전체 스냅샷 + 로그 전용 커밋)."""
    from . import snapshot

    return snapshot.submit_snapshots(ctx, task=task, title=title)


# ---------------------------------------------------------------------- status
def codex_trust(ctx: Context) -> dict:
    """~/.codex/config.toml에서 이 저장소 .codex/hooks.json 항목의 신뢰 기록을 찾는다.

    trusted_hash 항목이 있다는 것은 '과거에 어떤 내용을 신뢰했다'는 뜻일 뿐, 현재 설정이 신뢰됐다는 증거가 아니다.
    실제 실행 확인은 hooks.json 수정 이후의 Codex hook 실행 기록으로 판단한다(status 참고).
    """
    path = os.path.join(ctx.codex_home, "config.toml")
    from .common import norm_path

    target = norm_path(os.path.join(ctx.root, ".codex", "hooks.json"))
    try:
        import tomllib

        with open(path, "rb") as f:
            data = tomllib.load(f)
    except Exception:
        return {"readable": False, "events": {}}
    events = {}
    for k, v in ((data.get("hooks") or {}).get("state") or {}).items():
        parts = k.rsplit(":", 3)
        if len(parts) == 4 and norm_path(parts[0]) == target:
            events[parts[1]] = bool(isinstance(v, dict) and v.get("trusted_hash"))
    return {"readable": True, "events": events}


def last_hook_events(ctx: Context) -> dict:
    """도구별·이벤트별 마지막 hook 실행 시각."""
    out: dict = {}
    try:
        with open(ctx.registry_path, "rb") as f:
            for line in f:
                try:
                    o = json.loads(line)
                except ValueError:
                    continue
                out.setdefault(o.get("tool"), {})[o.get("event")] = o.get("time")
    except FileNotFoundError:
        pass
    return out


def codex_hook_verified(ctx: Context) -> dict:
    """현재 .codex/hooks.json 내용이 실제로 실행됐는지: 파일 수정 시각 이후의 Codex hook 실행 기록."""
    path = os.path.join(ctx.root, ".codex", "hooks.json")
    try:
        mtime = os.path.getmtime(path)
    except OSError:
        return {"configured": False}
    from datetime import datetime, timezone

    since = datetime.fromtimestamp(mtime, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
    events = {ev: t for ev, t in (last_hook_events(ctx).get("codex") or {}).items() if t and t >= since}
    return {"configured": True, "config_changed": since, "executed_events_since": events}


def status(ctx: Context) -> dict:
    last = read_json(ctx.last_sync_path, None)
    errors = []
    try:
        with open(ctx.errors_path, "rb") as f:
            errors = [json.loads(x) for x in f.read().splitlines()[-5:] if x.strip()]
    except FileNotFoundError:
        pass
    settings = read_json(os.path.join(ctx.root, ".claude", "settings.json"), {})
    claude_hooks = sorted((settings.get("hooks") or {}).keys())
    codex_cfg = read_json(os.path.join(ctx.root, ".codex", "hooks.json"), {})
    codex_hooks = sorted((codex_cfg.get("hooks") or {}).keys())
    return {
        "root": ctx.root,
        "mask_list_entries": len(load_mask_entries(ctx.mask_path)),
        "claude_hooks_configured": claude_hooks,
        "codex_hooks_configured": codex_hooks,
        "codex_trust": codex_trust(ctx),
        "codex_hook_verified": codex_hook_verified(ctx),
        "last_hook_events": last_hook_events(ctx),
        "last_sync": last,
        "recent_errors": errors,
        "index": build_index(ctx, write=False),
    }


# ------------------------------------------------------------------------ hook
def _where(e: BaseException) -> str:
    tb = traceback.extract_tb(e.__traceback__)
    if not tb:
        return "-"
    fr = tb[-1]
    return f"{os.path.basename(fr.filename)}:{fr.lineno}"


def _record_error(ctx: Context, tool: str, event: str, error: str, where: str = "-") -> None:
    try:
        append_jsonl(ctx.errors_path, {"time": utc_now(), "tool": tool, "event": event, "error": error, "where": where})
    except OSError:
        pass


def _read_stdin_json() -> dict:
    stream = sys.stdin
    if stream is None or stream.isatty():
        return {}
    raw = stream.buffer.read() if hasattr(stream, "buffer") else stream.read().encode()
    raw = raw.removeprefix(b"\xef\xbb\xbf")  # 셸 설정에 따라 붙는 UTF-8 BOM
    if not raw.strip():
        return {}
    try:
        o = json.loads(raw.decode("utf-8", "replace"))
    except ValueError:
        return {"_invalid_input": True}
    return o if isinstance(o, dict) else {}


def _register(ctx: Context, tool: str, event: str, payload: dict) -> None:
    cwd = payload.get("cwd") if isinstance(payload.get("cwd"), str) else None
    entry = {
        "time": utc_now(),
        "tool": tool,
        "event": event,
        "session_id": payload.get("session_id"),
        "transcript_path": payload.get("transcript_path"),
        "cwd": cwd,
        "in_project": bool(cwd and is_within(cwd, ctx.root)),
        "branch": git(ctx.root, "rev-parse", "--abbrev-ref", "HEAD", timeout=5),
        "turn_id": payload.get("turn_id"),
        "source": payload.get("source") or payload.get("reason"),
    }
    if payload.get("_invalid_input"):
        entry["input"] = "invalid_json"
    append_jsonl(ctx.registry_path, entry)


def hook(ctx: Context, tool: str, event: str) -> int:
    """hook 진입점. 수집 hook은 어떤 경우에도 exit 0으로 끝나고, 모델을 다시 돌리는 출력을 내지 않는다.

    예외: UserPromptSubmit의 턴 보호는 안전 규칙이다. 보호 상태를 기록하지 못하면 exit 2로 프롬프트를 막는다.
    """
    from . import turns

    if event == "UserPromptSubmit":
        return turns.prompt_hook(ctx, tool)
    # Codex Stop은 stdout JSON을 기대한다. 빈 객체 = 정상 종료(차단·계속 요청 없음).
    out = "{}" if (tool == "codex" and event == "Stop") else ""
    try:
        payload = _read_stdin_json()
        _register(ctx, tool, event, payload)
        if event == "Stop":
            try:
                turns.on_stop(ctx, tool, payload)
            except Exception as e:  # noqa: BLE001
                _record_error(ctx, tool, event, "turn_" + type(e).__name__, _where(e))
        if event in SYNC_EVENTS.get(tool, ()):
            try:
                sync(ctx, lock_timeout=30.0)
            except LockTimeout:
                _record_error(ctx, tool, event, "lock_timeout")
        if event == "Stop":
            from . import tasks

            try:
                tasks.run_pending(ctx, trigger=f"{tool}:Stop")
            except Exception as e:  # noqa: BLE001
                _record_error(ctx, tool, event, "task_" + type(e).__name__, _where(e))
    except Exception as e:  # noqa: BLE001 - hook은 절대 실패로 끝나지 않는다.
        _record_error(ctx, tool, event, type(e).__name__, _where(e))
    finally:
        if out:
            sys.stdout.write(out)
            sys.stdout.flush()
    return 0
