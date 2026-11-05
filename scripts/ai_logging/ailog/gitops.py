"""Git 객체를 직접 다루는 도우미.

원칙
- 자동화 커밋은 임시 index(GIT_INDEX_FILE)로 트리를 만들고 `commit-tree` + CAS `update-ref`로 반영한다.
  사용자 index의 다른 항목·작업 폴더를 바꾸지 않고, 그 사이 브랜치가 바뀌면 실패한다(reset 없음).
- 로그 파일은 `hash-object --no-filters`로 원래 바이트 그대로 blob을 만든다(개행 변환 없음).
- 검증은 작업 폴더가 아니라 커밋·index의 실제 blob을 읽는다.
"""
from __future__ import annotations

import os
import uuid

from .common import GitError, git, git_run

ZERO = "0" * 40
IN_PROGRESS_MARKERS = {
    "MERGE_HEAD": "merge",
    "CHERRY_PICK_HEAD": "cherry-pick",
    "REVERT_HEAD": "revert",
    "REBASE_HEAD": "rebase",
    "rebase-merge": "rebase",
    "rebase-apply": "rebase/am",
    "BISECT_LOG": "bisect",
}


def git_dir(root: str) -> str:
    d = git(root, "rev-parse", "--absolute-git-dir")
    if not d:
        raise GitError(("rev-parse",), 128)
    return d


def common_dir(root: str) -> str:
    d = git(root, "rev-parse", "--path-format=absolute", "--git-common-dir")
    if not d:
        raise GitError(("rev-parse",), 128)
    return d


def rev(root: str, ref: str):
    return git(root, "rev-parse", "--verify", "-q", ref + "^{commit}")


def current_branch(root: str):
    """체크아웃한 브랜치 이름(분리된 HEAD면 None)."""
    return git(root, "symbolic-ref", "-q", "--short", "HEAD")


def is_ancestor(root: str, a: str, b: str) -> bool:
    return git_run(root, "merge-base", "--is-ancestor", a, b, check=False).returncode == 0


def in_progress(root: str) -> list[str]:
    d = git_dir(root)
    return sorted({v for k, v in IN_PROGRESS_MARKERS.items() if os.path.exists(os.path.join(d, k))})


def staged_changes(root: str) -> list[tuple[str, str]]:
    """index ↔ HEAD 차이(상태, 경로). HEAD가 없으면 빈 트리 기준."""
    base = rev(root, "HEAD") or "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
    out = git_run(root, "diff-index", "--cached", "-z", "--no-renames", "--name-status", base).stdout
    return _parse_name_status(out)


def worktree_dirty(root: str) -> list[str]:
    """추적 파일 수정·삭제·미추적 파일(무시 파일 제외). 로그 live·.ai-logging은 무시 규칙 대상."""
    out = git_run(root, "status", "--porcelain=v1", "-z", "--untracked-files=all").stdout
    items = [x for x in out.split(b"\0") if x]
    paths, skip = [], False
    for x in items:
        if skip:
            skip = False
            continue
        st, path = x[:2], x[3:].decode("utf-8", "replace")
        if st[:1] in (b"R", b"C"):
            skip = True
        paths.append(path)
    return paths


def _parse_name_status(out: bytes) -> list[tuple[str, str]]:
    parts = [x for x in out.split(b"\0") if x != b""]
    res, i = [], 0
    while i < len(parts):
        st = parts[i].decode()
        if st[:1] in ("R", "C"):
            res.append((st[:1], parts[i + 2].decode("utf-8", "replace")))
            i += 3
        else:
            res.append((st[:1], parts[i + 1].decode("utf-8", "replace")))
            i += 2
    return res


def diff_tree(root: str, a: str | None, b: str) -> list[tuple[str, str]]:
    """커밋 a → b 변경(상태, 경로). a가 None이면 빈 트리 기준."""
    base = a or "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
    out = git_run(root, "diff-tree", "-r", "-z", "--no-renames", "--name-status", base, b).stdout
    return _parse_name_status(out)


def parents(root: str, commit: str) -> list[str]:
    line = git(root, "rev-list", "--parents", "-n", "1", commit) or ""
    return line.split()[1:]


def tree_files(root: str, treeish: str, prefix: str | None = None) -> dict[str, tuple[str, str]]:
    """{경로: (mode, blob oid)}. prefix 아래만(없으면 전체)."""
    args = ["ls-tree", "-r", "-z", "--full-tree", treeish]
    if prefix:
        args += ["--", prefix]
    r = git_run(root, *args, check=False)
    if r.returncode != 0:
        return {}
    out = {}
    for item in r.stdout.split(b"\0"):
        if not item:
            continue
        meta, path = item.split(b"\t", 1)
        mode, typ, oid = meta.decode().split()
        if typ == "blob":
            out[path.decode("utf-8", "replace")] = (mode, oid)
    return out


def index_files(root: str, prefix: str) -> dict[str, tuple[str, str]]:
    """현재 index의 {경로: (mode, oid)} (stage 0)."""
    out = {}
    r = git_run(root, "ls-files", "-s", "-z", "--", prefix, check=False)
    for item in r.stdout.split(b"\0"):
        if not item:
            continue
        meta, path = item.split(b"\t", 1)
        mode, oid, stage = meta.decode().split()
        if stage == "0":
            out[path.decode("utf-8", "replace")] = (mode, oid)
    return out


def cat_blobs(root: str, oids) -> dict[str, bytes]:
    oids = sorted(set(oids))
    if not oids:
        return {}
    r = git_run(root, "cat-file", "--batch", input=("\n".join(oids) + "\n").encode(), timeout=600)
    out, buf, i = {}, r.stdout, 0
    for _ in oids:
        nl = buf.index(b"\n", i)
        header = buf[i:nl].decode().split()
        i = nl + 1
        if len(header) < 3 or header[1] == "missing":
            continue
        size = int(header[2])
        out[header[0]] = buf[i:i + size]
        i += size + 1
    return out


def blob_batches(root: str, oids, max_bytes: int = 32 * 1024 * 1024):
    """전체 로그를 한꺼번에 메모리에 올리지 않는다. 큰 파일 하나는 단독 배치로 읽는다."""
    oids = sorted(set(oids))
    if not oids:
        return
    r = git_run(root, "cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)",
                input=("\n".join(oids) + "\n").encode(), timeout=120)
    sizes = {}
    for line in r.stdout.decode().splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[1] == "blob":
            sizes[parts[0]] = int(parts[2])
    batch, total = [], 0
    for oid in oids:
        size = sizes.get(oid, 0)
        if batch and (total + size > max_bytes or len(batch) >= 128):
            yield batch
            batch, total = [], 0
        batch.append(oid)
        total += size
    if batch:
        yield batch


def hash_files(root: str, rel_paths: list[str]) -> dict[str, str]:
    """작업 폴더 파일을 필터 없이(바이트 그대로) blob으로 저장하고 oid를 돌려준다."""
    if not rel_paths:
        return {}
    data = ("\n".join(rel_paths) + "\n").encode("utf-8")
    r = git_run(root, "hash-object", "-w", "--no-filters", "--stdin-paths", input=data, timeout=600)
    oids = r.stdout.decode().split()
    if len(oids) != len(rel_paths):
        raise GitError(("hash-object",), 1)
    return dict(zip(rel_paths, oids))


def _index_info(entries: dict[str, tuple[str, str]], removals=()) -> bytes:
    lines = [f"{mode} {oid}\t{path}" for path, (mode, oid) in sorted(entries.items())]
    lines += [f"0 {ZERO}\t{path}" for path in sorted(removals)]
    return ("\n".join(lines) + "\n").encode("utf-8") if lines else b""


def build_commit(root: str, parent: str | None, message: str, add: dict[str, tuple[str, str]] | None = None,
                 add_worktree_paths: list[str] | None = None, extra_parents=(), state_dir: str | None = None) -> tuple[str, str]:
    """임시 index로 (commit oid, tree oid)를 만든다. ref·사용자 index는 바꾸지 않는다.

    add: {경로: (mode, oid)} 정확한 blob 추가. add_worktree_paths: 일반 `git add -A` 규칙(필터 적용)으로 반영할 경로.
    """
    tmp_dir = state_dir or git_dir(root)
    os.makedirs(tmp_dir, exist_ok=True)
    idx = os.path.join(tmp_dir, f"tmp-index-{uuid.uuid4().hex}")
    env = {"GIT_INDEX_FILE": idx}
    try:
        if parent:
            git_run(root, "read-tree", parent, env=env)
        else:
            git_run(root, "read-tree", "--empty", env=env)
        if add:
            git_run(root, "update-index", "--add", "--index-info", input=_index_info(add), env=env)
        if add_worktree_paths:
            git_run(root, "add", "-A", "--", *add_worktree_paths, env=env)
        tree = git_run(root, "write-tree", env=env).stdout.decode().strip()
        args = ["commit-tree", tree]
        for p in ([parent] if parent else []) + list(extra_parents):
            args += ["-p", p]
        commit = git_run(root, *args, "-F", "-", input=message.encode("utf-8")).stdout.decode().strip()
        return commit, tree
    finally:
        for p in (idx, idx + ".lock"):
            try:
                os.remove(p)
            except FileNotFoundError:
                pass


def tree_of(root: str, commit: str) -> str:
    return git(root, "rev-parse", commit + "^{tree}")


def cas_update_branch(root: str, branch: str, new: str, old: str | None, reason: str) -> None:
    """브랜치가 old일 때만 new로 옮긴다(비교 후 교체). 다르면 GitError."""
    git_run(root, "update-ref", "-m", f"ai-logging: {reason}", f"refs/heads/{branch}", new, old or ZERO)


def sync_index_paths(root: str, commit: str, paths: list[str]) -> None:
    """방금 만든 커밋의 해당 경로 항목만 사용자 index에 반영한다(다른 항목은 그대로)."""
    if not paths:
        return
    files = tree_files(root, commit)
    present = {p: files[p] for p in paths if p in files}
    removed = [p for p in paths if p not in files]
    data = _index_info(present, removed)
    if data:
        git_run(root, "update-index", "--add", "--remove", "--index-info", input=data)
    git_run(root, "update-index", "-q", "--refresh", check=False)


def remote_ref(root: str, remote: str, branch: str):
    return rev(root, f"refs/remotes/{remote}/{branch}")
