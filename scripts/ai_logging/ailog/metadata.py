"""Claude 종료 메타데이터의 검증된 이전 버전 보존. JSONL에는 적용하지 않는다.

현재 JSON 끝의 finished:true 추가를 제거한 바이트가 과거 원본 해시와 같을 때만
이전 버전을 인정한다. 과거 live 사본은 교체 전에 로컬 불변 보관하고, 제출 사본은
검증된 이전 원본 바이트를 현재 정책으로 마스킹하여 files/_meta/에 넣는다.
"""
from __future__ import annotations

import json
import os
import re
import tempfile

from .common import atomic_write, dumps_json, read_json_ro, sha256

ROLE = "metadata_history"
PREFIX = "_meta/"
_HASH = re.compile(r"[0-9a-f]{64}\Z")
_NAME = re.compile(r"agent-[A-Za-z0-9_-]+\.meta\.json\Z")
_FINISHED = re.compile(rb',\s*"finished"\s*:\s*true(?P<end>\s*\}\s*)\Z')


def eligible(tool, sid, rel):
    parts = rel.split("/")
    return (tool == "claude" and len(parts) == 3 and parts[0] == sid
            and parts[1] == "subagents" and bool(_NAME.fullmatch(parts[2])))


def _pairs(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise ValueError("duplicate_json_key")
        out[key] = value
    return out


def _constant(value):
    raise ValueError("invalid_json_constant")


def predecessor(data):
    """정확히 마지막 finished:true만 제거. JSON 재직렬화로 다른 바이트를 바꾸지 않는다."""
    match = _FINISHED.search(data)
    if not match:
        return None
    previous = data[:match.start()] + match.group("end")
    try:
        current = json.loads(data, object_pairs_hook=_pairs, parse_constant=_constant)
        old = json.loads(previous, object_pairs_hook=_pairs, parse_constant=_constant)
    except (ValueError, UnicodeError):
        return None
    if (not isinstance(current, dict) or not isinstance(old, dict) or not old
            or current.get("finished") is not True or "finished" in old
            or {k: v for k, v in current.items() if k != "finished"} != old):
        return None
    return previous


def matches(data, entry):
    return (data is not None and len(data) == entry.get("original_bytes")
            and sha256(data) == entry.get("original_sha256"))


def history_path(rel, digest):
    # Windows 경로 길이를 늘리지 않는다. 전체 원본 해시·경로는 manifest와 ID에 결합한다.
    key = sha256((rel + "\0" + digest).encode())[:16]
    return f"{PREFIX}{key}.json"


def source_path(tool, sid, entry):
    """이력 경로와 실제 원본 파일의 연결을 검증한다."""
    rel, digest = entry.get("source_path"), entry.get("original_sha256")
    if (entry.get("role") != ROLE or not isinstance(rel, str) or not isinstance(digest, str)
            or not _HASH.fullmatch(digest) or not eligible(tool, sid, rel)
            or entry.get("path") != history_path(rel, digest)):
        return None
    return rel


def original_data(tool, sid, entry, current):
    if entry.get("role") != ROLE:
        return current
    if source_path(tool, sid, entry) is None:
        return None
    old = predecessor(current) if current is not None else None
    return old if matches(old, entry) else None


def archive_root(ctx, session):
    return ctx.p(os.path.join("metadata-history", session.tool, session.session_id))


def _archive_id(record):
    return sha256(dumps_json(record))


def archives(ctx, session):
    """불변 보관 사본을 검증해 읽는다. 원문과 보관 내용을 진단에 넣지 않는다."""
    root = archive_root(ctx, session)
    records, problems = [], []
    if not os.path.isdir(root):
        return records, problems
    for name in sorted(os.listdir(root)):
        if name.startswith(".tmp-"):
            continue  # 폴더 교체 전 중단된 준비본. live 원본 사본은 아직 교체되지 않았다.
        path = os.path.join(root, name)
        record, status = read_json_ro(os.path.join(path, "record.json"))
        if (not _HASH.fullmatch(name) or status != "ok" or not isinstance(record, dict)
                or record.get("tool") != session.tool or record.get("session_id") != session.session_id
                or not eligible(session.tool, session.session_id, record.get("path", ""))
                or _archive_id(record) != name):
            problems.append({"rule": "metadata_archive_invalid"})
            continue
        try:
            with open(os.path.join(path, "copy"), "rb") as stream:
                data = stream.read()
        except OSError:
            data = None
        if (data is None or sha256(data) != record.get("copy_sha256")
                or len(data) != record.get("copy_bytes")):
            problems.append({"rule": "metadata_archive_copy_mismatch", "file": record["path"]})
            continue
        records.append((record, data))
    return records, problems


def write_archives(ctx, session, records):
    """호출자는 sync 잠금을 보유한다. 완성된 디렉터리를 먼저 확정한 뒤 live를 바꾼다."""
    root = archive_root(ctx, session)
    for record, data in records:
        os.makedirs(root, exist_ok=True)
        destination = os.path.join(root, _archive_id(record))
        if os.path.exists(destination):
            with open(os.path.join(destination, "copy"), "rb") as stream:
                same = stream.read() == data
            old, status = read_json_ro(os.path.join(destination, "record.json"))
            if not same or status != "ok" or old != record:
                raise ValueError("metadata_archive_conflict")
            continue
        staging = tempfile.mkdtemp(prefix=".tmp-", dir=root)
        atomic_write(os.path.join(staging, "copy"), data)
        atomic_write(os.path.join(staging, "record.json"), dumps_json(record))
        os.rename(staging, destination)


def prepare(ctx, session, policy, current, state, manifests, read_copy=None):
    """이전 버전·사본 검증과 이력 준비만 수행한다. 쓰기는 호출자가 성공 확인 후 한다.

    manifests는 (사본 루트 절대 경로, manifest)의 목록이다. 현재 파일은 current[상대 경로].
    반환한 originals에는 검증된 과거 원본 바이트가 일시적으로만 담기며 로그에 쓰지 않는다.
    """
    from .engine import check_masked

    stored, problems = archives(ctx, session)
    references = []
    for record, data in stored:
        references.append((record["path"], record, data, False))
    for base, manifest in manifests:
        for entry in (manifest or {}).get("files", []):
            historic = entry.get("role") == ROLE or entry.get("path", "").startswith(PREFIX)
            rel = source_path(session.tool, session.session_id, entry) if historic else entry.get("path", "")
            if historic and rel is None:
                problems.append({"rule": "metadata_history_invalid", "file": entry.get("path", "")})
                continue
            if not eligible(session.tool, session.session_id, rel):
                continue
            data = None
            try:
                if read_copy is not None:
                    data = read_copy(base, entry)
                else:
                    with open(os.path.join(base, "files", *entry["path"].split("/")), "rb") as stream:
                        data = stream.read()
            except OSError:
                pass
            references.append((rel, entry, data, manifest.get("kind") == "live" and not historic))
    # 상태만 남은 경우에도 이전 원본 해시는 버리지 않는다. 대응하는 사본이 없으면 보류한다.
    prefix = session.key + "/"
    for key, entry in state.get("files", {}).items():
        if key.startswith(prefix) and eligible(session.tool, session.session_id, key[len(prefix):]):
            references.append((key[len(prefix):], entry, None, False))

    originals, validated, pending = {}, set(), []
    for rel, entry, copy, archive in references:
        data = current.get(rel)
        if data is None:
            problems.append({"rule": "original_file_deleted", "file": rel})
            continue
        if matches(data, entry):
            continue
        old = predecessor(data)
        if not matches(old, entry):
            problems.append({"rule": "original_rewritten", "file": rel})
            continue
        originals[rel] = old
        if copy is None:
            if "copy_bytes" in entry:
                problems.append({"rule": "metadata_previous_copy_missing", "file": rel})
            continue
        if sha256(copy) != entry.get("copy_sha256") or len(copy) != entry.get("copy_bytes"):
            problems.append({"rule": "metadata_previous_copy_mismatch", "file": rel})
            continue
        if policy.mask(copy)[0] != policy.mask(old)[0]:
            problems.append({"rule": "metadata_previous_copy_not_original", "file": rel})
            continue
        validated.add(rel)
        if archive:
            record = {"tool": session.tool, "session_id": session.session_id, "path": rel,
                      **{key: entry[key] for key in ("original_bytes", "original_sha256", "copy_bytes", "copy_sha256")}}
            pending.append((record, copy))
    entries, copies = [], {}
    for rel, old in sorted(originals.items()):
        if rel not in validated:
            problems.append({"rule": "metadata_previous_copy_missing", "file": rel})
            continue
        masked, counts = policy.mask(old)
        problems.extend(check_masked(rel, old, masked, policy))
        path = history_path(rel, sha256(old))
        if path in copies:
            problems.append({"rule": "metadata_history_path_collision", "file": rel})
            continue
        entries.append({"path": path, "source_path": rel, "role": ROLE,
                        "original_bytes": len(old), "original_sha256": sha256(old),
                        "copy_bytes": len(masked), "copy_sha256": sha256(masked),
                        "masked": dict(sorted(counts.items()))})
        copies[path] = masked
    return {"entries": entries, "copies": copies, "originals": originals,
            "archives": pending, "problems": problems}
