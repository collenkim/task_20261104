"""두 도구의 네이티브 세션 원본 탐색.

판정은 폴더 이름이 아니라 기록 안의 cwd 값으로 한다. Claude 프로젝트 폴더 이름은
'_'를 '-'로 바꿔 만들기 때문에 다른 경로와 겹칠 수 있다.
"""
from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field

from .common import Context, git, is_within, native_path, norm_path

_UUID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
_REF_KEY_RE = re.compile(r"(parent|fork|source|thread)", re.IGNORECASE)

# cwd를 찾기 위해 한 파일에서 읽는 최대 범위
_MAX_SCAN_LINES = 2000
_MAX_SCAN_BYTES = 8 * 1024 * 1024


@dataclass
class SessionFile:
    rel: str  # 세션 기준 상대 경로(POSIX 구분자). 사본 경로에 그대로 쓴다.
    path: str  # 원본 절대 경로
    role: str  # "main" | "attachment"


@dataclass
class Session:
    tool: str
    session_id: str
    main_path: str
    files: list[SessionFile] = field(default_factory=list)

    @property
    def key(self) -> str:
        return f"{self.tool}/{self.session_id}"


@dataclass
class Discovery:
    sessions: list[Session]
    unresolved: int  # cwd도 부모 연결도 확인하지 못한 Codex 파일 수
    roots: list[str]


def project_roots(ctx: Context, state: dict) -> list[str]:
    roots = [ctx.root]
    out = git(ctx.root, "worktree", "list", "--porcelain")
    if out:
        for line in out.splitlines():
            if line.startswith("worktree "):
                roots.append(line[len("worktree "):].strip())
    roots.extend(state.get("extra_roots", []))
    seen, uniq = set(), []
    for r in roots:
        n = norm_path(r)
        if n not in seen:
            seen.add(n)
            uniq.append(r)
    return uniq


def _in_roots(cwd, roots) -> bool:
    return isinstance(cwd, str) and any(is_within(cwd, r) for r in roots)


def _read_claude_cwd(path: str):
    read = 0
    with open(path, "rb") as f:
        for i, line in enumerate(f):
            read += len(line)
            if i >= _MAX_SCAN_LINES or read > _MAX_SCAN_BYTES:
                break
            try:
                o = json.loads(line)
            except ValueError:
                continue
            if isinstance(o, dict) and isinstance(o.get("cwd"), str):
                return o["cwd"]
    return None


def _collect_refs(obj, depth=0, under_ref_key=False, out=None):
    out = set() if out is None else out
    if depth > 3:
        return out
    if isinstance(obj, dict):
        for k, v in obj.items():
            _collect_refs(v, depth + 1, under_ref_key or bool(_REF_KEY_RE.search(str(k))), out)
    elif isinstance(obj, list):
        for v in obj[:20]:
            _collect_refs(v, depth + 1, under_ref_key, out)
    elif isinstance(obj, str) and under_ref_key:
        out.update(m.group(0).lower() for m in _UUID_RE.finditer(obj))
    return out


def _read_codex_meta(path: str):
    with open(path, "rb") as f:
        first = f.readline()
    try:
        o = json.loads(first)
    except ValueError:
        return None
    if not isinstance(o, dict) or o.get("type") != "session_meta":
        return None
    p = o.get("payload") if isinstance(o.get("payload"), dict) else {}
    sid = p.get("id") or p.get("session_id")
    if not isinstance(sid, str):
        m = _UUID_RE.search(os.path.basename(path))
        sid = m.group(0) if m else None
    refs = sorted(_collect_refs(p) - ({sid.lower()} if sid else set()))
    return {"cwd": p.get("cwd") if isinstance(p.get("cwd"), str) else None, "id": sid, "refs": refs}


def _cached(cache: dict, path: str, loader):
    """경로별 메타데이터 캐시. cwd를 찾은 경우에만 저장한다(새 파일은 다시 읽는다)."""
    hit = cache.get(path)
    if hit is not None:
        return hit
    try:
        value = loader(path)
    except OSError:
        return None
    if value is not None and (not isinstance(value, dict) or value.get("cwd") or value.get("refs")):
        cache[path] = value
    return value


def _is_plain_file(p: str) -> bool:
    return os.path.isfile(p) and not os.path.islink(p)


def discover_claude(ctx: Context, roots: list[str], cache: dict) -> list[Session]:
    base = os.path.join(ctx.claude_home, "projects")
    if not os.path.isdir(base):
        return []
    sessions = []
    for proj in sorted(os.listdir(base)):
        pdir = os.path.join(base, proj)
        if not os.path.isdir(pdir) or os.path.islink(pdir):
            continue
        for name in sorted(os.listdir(pdir)):
            fpath = os.path.join(pdir, name)
            if not name.endswith(".jsonl") or not _is_plain_file(fpath):
                continue
            meta = _cached(cache, fpath, lambda p: {"cwd": _read_claude_cwd(p)})
            if not meta or not _in_roots(meta.get("cwd"), roots):
                continue
            sessions.append(claude_session(fpath))
    return sessions


def claude_session(main_path: str, sid: str | None = None) -> Session:
    """Claude 주 JSONL과 같은 이름 폴더(<sid>/, subagents·tool-results 등)의 전체 파일."""
    pdir, name = os.path.split(main_path)
    sid = sid or name[: -len(".jsonl")]
    s = Session("claude", sid, main_path, [SessionFile(name, main_path, "main")])
    sdir = os.path.join(pdir, name[: -len(".jsonl")])
    if os.path.isdir(sdir) and not os.path.islink(sdir):
        for dirpath, dirnames, filenames in os.walk(sdir, followlinks=False):
            dirnames[:] = sorted(d for d in dirnames if not os.path.islink(os.path.join(dirpath, d)))
            for fn in sorted(filenames):
                fp = os.path.join(dirpath, fn)
                if fn.endswith(".tmp") or ".tmp-" in fn or not _is_plain_file(fp):
                    continue
                rel = os.path.relpath(fp, pdir).replace(os.sep, "/")
                s.files.append(SessionFile(rel, fp, "attachment"))
    return s


def discover_codex(ctx: Context, roots: list[str], cache: dict):
    found = []  # (path, meta)
    for sub in ("sessions", "archived_sessions"):
        base = os.path.join(ctx.codex_home, sub)
        if not os.path.isdir(base):
            continue
        for dirpath, dirnames, filenames in os.walk(base, followlinks=False):
            dirnames.sort()
            for fn in sorted(filenames):
                fp = os.path.join(dirpath, fn)
                if fn.startswith("rollout-") and fn.endswith(".jsonl") and _is_plain_file(fp):
                    meta = _cached(cache, fp, _read_codex_meta)
                    found.append((fp, meta))
    included: dict[str, Session] = {}
    pending = []
    for fp, meta in found:
        if meta and meta.get("id") and _in_roots(meta.get("cwd"), roots):
            sid = meta["id"]
            included.setdefault(sid, Session("codex", sid, fp, [SessionFile(os.path.basename(fp), fp, "main")]))
        elif meta and meta.get("id") and not meta.get("cwd"):
            pending.append((fp, meta))
    # cwd가 없는 하위 rollout은 이미 포함된 세션을 참조할 때만 포함한다(반복 적용).
    changed = True
    known = {k.lower() for k in included}
    while changed:
        changed = False
        rest = []
        for fp, meta in pending:
            if set(meta.get("refs", [])) & known:
                sid = meta["id"]
                included.setdefault(sid, Session("codex", sid, fp, [SessionFile(os.path.basename(fp), fp, "main")]))
                known.add(sid.lower())
                changed = True
            else:
                rest.append((fp, meta))
        pending = rest
    unresolved = sum(1 for fp, meta in found if not meta or not meta.get("id"))
    unresolved += len(pending)
    return list(included.values()), unresolved


def discover(ctx: Context, state: dict) -> Discovery:
    cache = state.setdefault("meta_cache", {})
    roots = project_roots(ctx, state)
    claude = discover_claude(ctx, roots, cache)
    codex, unresolved = discover_codex(ctx, roots, cache)
    return Discovery(sessions=claude + codex, unresolved=unresolved, roots=roots)


# ------------------------------------------------------------------ targets
@dataclass
class Targets:
    """수집·최종 검증 대상 세션 집합(합집합).

    sessions: 원본이 있는 대상. missing: 대상으로 알려졌지만 원본을 찾지 못한 세션 키.
    reasons: 세션 키별 근거(discovered / registry / known / live_manifest / snapshot / git_tree).
    """

    sessions: list[Session]
    missing: list[str]
    unresolved: int
    roots: list[str]
    reasons: dict[str, list[str]]


def _registry_entries(ctx: Context) -> list[dict]:
    out = []
    try:
        with open(ctx.registry_path, "rb") as f:
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


def _session_from_registry(entry: dict) -> Session | None:
    path, tool, sid = entry.get("transcript_path"), entry.get("tool"), entry.get("session_id")
    if not (isinstance(path, str) and isinstance(sid, str) and tool in ("claude", "codex")):
        return None
    path = native_path(path)
    if not _is_plain_file(path) or not path.endswith(".jsonl"):
        return None
    if tool == "claude":
        return claude_session(path)
    meta = None
    try:
        meta = _read_codex_meta(path)
    except OSError:
        pass
    real_sid = (meta or {}).get("id") or sid
    return Session("codex", real_sid, path, [SessionFile(os.path.basename(path), path, "main")])


def manifest_keys(ctx: Context) -> set[str]:
    """작업 폴더의 live·snapshot manifest에 기록된 세션 키(읽기 전용)."""
    keys = set()
    for base in (ctx.live_dir, ctx.snapshots_dir):
        if not os.path.isdir(base):
            continue
        for tool in os.listdir(base):
            tdir = os.path.join(base, tool)
            if tool in ("claude", "codex") and os.path.isdir(tdir):
                for sid in os.listdir(tdir):
                    if os.path.isdir(os.path.join(tdir, sid)):
                        keys.add(f"{tool}/{sid}")
    return keys


def resolve_targets(ctx: Context, state: dict, extra_known=()) -> Targets:
    disc = discover(ctx, state)
    by_key = {s.key: s for s in disc.sessions}
    reasons: dict[str, list[str]] = {k: ["discovered"] for k in by_key}
    for e in _registry_entries(ctx):
        cwd = e.get("cwd")
        if not (e.get("in_project") or _in_roots(cwd, disc.roots)):
            continue
        tool, sid = e.get("tool"), e.get("session_id")
        if tool not in ("claude", "codex") or not isinstance(sid, str):
            continue
        key = f"{tool}/{sid}"
        if key not in by_key:
            s = _session_from_registry(e)
            if s is not None:
                by_key.setdefault(s.key, s)
                reasons.setdefault(s.key, []).append("registry_transcript")
                if s.key != key:
                    continue
        reasons.setdefault(key, [])
        if "registry" not in reasons[key]:
            reasons[key].append("registry")
    for key in state.get("known_sessions", {}):
        reasons.setdefault(key, []).append("known")
    for key in manifest_keys(ctx):
        reasons.setdefault(key, []).append("manifest")
    for key in extra_known:
        reasons.setdefault(key, []).append("git_tree")
    missing = sorted(k for k in reasons if k not in by_key)
    return Targets(sorted(by_key.values(), key=lambda s: s.key), missing, disc.unresolved, disc.roots, reasons)
