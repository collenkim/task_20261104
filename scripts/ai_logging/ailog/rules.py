"""공통 커밋 메시지 형식과 로그 전용 변경 판정. Git hook과 자동화 명령이 같은 검사기를 쓴다."""
from __future__ import annotations

import json
import re

from .common import LIVE_REL, SNAPSHOTS_REL, sha256

TITLE_RE = re.compile(r"^\[(?P<svc>[A-Z][A-Z0-9]*)-(?P<task>[A-Z0-9]+(?:-[A-Z0-9]+)*)\] \S.*$")
TRAILER_RE = re.compile(r"^[A-Za-z][A-Za-z0-9-]*: \S.*$")
CHERRY_RE = re.compile(r"^\(cherry picked from commit [0-9a-f]{7,64}\)$")
TASK_ID_RE = re.compile(r"^[A-Z][A-Z0-9]*-[A-Z0-9]+(?:-[A-Z0-9]+)*$")
SNAP_DIR_RE = re.compile(
    r"^" + re.escape(SNAPSHOTS_REL) + r"/(?P<tool>claude|codex)/(?P<sid>[^/]+)/(?P<policy>[0-9a-f]{12})/"
    r"(?P<snap>L\d{20}-[0-9a-f]{16})/(?P<rest>.+)$"
)
FORBIDDEN_PREFIXES = (LIVE_REL + "/", ".ai-logging/")


def check_message(text: str) -> list[str]:
    """`[작업ID] 제목` + 빈 줄 + 두 칸 들여쓴 작업 내용. 위반 사유 목록(빈 목록이면 통과).

    작업 ID는 `영문대문자로 시작하는 단어-단어...`(예: SETUP-001, AI-LOG-001). 브랜치와의 일치는 config.check_branch_task.
    """
    lines = [ln for ln in text.replace("\r\n", "\n").split("\n") if not ln.startswith("#")]
    while lines and not lines[-1].strip():
        lines.pop()
    if not lines or not TITLE_RE.match(lines[0]):
        return ["title_format"]
    if len(lines) < 3 or lines[1] != "":
        return ["body_missing"]
    body = [ln for ln in lines[2:] if ln.strip()]
    if not any(ln.startswith("  ") and ln.strip() for ln in body):
        return ["body_indent"]
    for ln in body:
        if not (ln.startswith("  ") or TRAILER_RE.match(ln) or CHERRY_RE.match(ln)):
            return ["body_indent"]
    return []


def task_from_title(text: str):
    m = TITLE_RE.match(text.split("\n", 1)[0])
    return f"{m.group('svc')}-{m.group('task')}" if m else None


def is_log_path(path: str) -> bool:
    return path.startswith(SNAPSHOTS_REL + "/")


def snapshot_groups(paths) -> tuple[dict[str, list[str]], list[str]]:
    """스냅샷 경로를 스냅샷 폴더별로 묶는다. (그룹, 형식이 맞지 않는 경로)."""
    groups: dict[str, list[str]] = {}
    bad = []
    for p in paths:
        m = SNAP_DIR_RE.match(p)
        if not m:
            bad.append(p)
            continue
        base = p[: len(p) - len(m.group("rest"))]
        groups.setdefault(base, []).append(m.group("rest"))
    return groups, bad


def check_snapshot_group(base: str, rests: list[str], blob) -> list[str]:
    """스냅샷 폴더 하나의 완전성: manifest와 파일 목록·blob sha256 일치, ID 재계산 일치.

    blob(path) -> bytes | None 는 검사 대상(index 또는 커밋)의 실제 내용을 돌려준다.
    """
    from .snapshot import snapshot_id, snapshot_sha256  # 순환 import 방지
    from . import metadata

    if "manifest.json" not in rests:
        return ["snapshot_manifest_missing"]
    raw = blob(base + "manifest.json")
    try:
        m = json.loads(raw.decode("utf-8"))
    except (AttributeError, ValueError, UnicodeDecodeError):
        return ["snapshot_manifest_unreadable"]
    problems = []
    paths = {e.get("path") for e in m.get("files", []) if e.get("role") != metadata.ROLE}
    for entry in m.get("files", []):
        if entry.get("role") == metadata.ROLE or entry.get("path", "").startswith(metadata.PREFIX):
            source = metadata.source_path(m.get("tool"), m.get("session_id"), entry)
            if source is None or source not in paths:
                problems.append("metadata_history_invalid")
    listed = {"files/" + e["path"]: e for e in m.get("files", []) if not e.get("original_missing")}
    present = {r for r in rests if r != "manifest.json"}
    if present != set(listed):
        problems.append("snapshot_file_list_mismatch")
    for rel, e in listed.items():
        data = blob(base + rel)
        if data is None or sha256(data) != e.get("copy_sha256") or len(data) != e.get("copy_bytes"):
            problems.append("snapshot_blob_mismatch")
            break
    parts = base.rstrip("/").split("/")
    if m.get("snapshot_id") != parts[-1] or m.get("policy_version") != parts[-2] or m.get("session_id") != parts[-3]:
        problems.append("snapshot_path_mismatch")
    elif snapshot_id(m) != parts[-1] or m.get("snapshot_sha256") != snapshot_sha256(m):
        problems.append("snapshot_id_mismatch")
    return problems


def check_changes(changes: list[tuple[str, str]], blob, *, allow_source: bool) -> list[str]:
    """커밋(또는 index) 변경 검사.

    - live·.ai-logging 경로는 언제나 금지.
    - 스냅샷은 추가만 허용(수정·삭제 금지)하고, 추가된 스냅샷 폴더는 완전해야 한다.
    - allow_source=False(로그 전용 커밋)이면 스냅샷 밖 변경이 하나라도 있으면 실패.
    """
    problems = []
    added_logs = []
    for st, path in changes:
        if path.startswith(FORBIDDEN_PREFIXES):
            problems.append("forbidden_path")
        elif is_log_path(path):
            if st != "A":
                problems.append("snapshot_modified_or_deleted")
            else:
                added_logs.append(path)
        elif not allow_source:
            problems.append("non_log_change")
    groups, bad = snapshot_groups(added_logs)
    if bad:
        problems.append("snapshot_path_format")
    for base, rests in sorted(groups.items()):
        problems.extend(check_snapshot_group(base, rests, blob))
    return sorted(set(problems))
