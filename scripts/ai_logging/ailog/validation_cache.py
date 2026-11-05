"""통과한 검사만 내용·정책 해시로 재사용한다. 원문·검출값은 저장하지 않는다.

캐시 손상/잠금/권한 오류는 전체 재검사로 돌아간다. 로컬 신뢰 영역의 성능 자료이며
Git 객체/원본 무결성 검사를 대체하지 않는다. 삭제해도 로그와 검증 결과는 바뀌지 않는다.
"""
from __future__ import annotations

import hashlib
import os
import sqlite3
from contextlib import closing
from functools import lru_cache
from pathlib import Path


@lru_cache(maxsize=1)
def scanner_digest() -> str:
    digest = hashlib.sha256(b"validation-cache-v1")
    for name in ("policy.py", "common.py", "engine.py", "snapshot.py", "validation_cache.py"):
        digest.update(name.encode())
        digest.update(Path(__file__).with_name(name).read_bytes().replace(b"\r\n", b"\n"))
    return digest.hexdigest()


class ValidationCache:
    def __init__(self, directory: str, namespace: str):
        self.path = os.path.join(directory, "validation.sqlite3")
        self.namespace = namespace
        self.disabled = False
        self.hits = 0
        self.misses = 0
        try:
            os.makedirs(directory, exist_ok=True)
            with closing(self._connect()) as conn, conn:
                conn.execute("CREATE TABLE IF NOT EXISTS passed (namespace TEXT, kind TEXT, key TEXT, proof TEXT, "
                             "PRIMARY KEY(namespace, kind, key)) WITHOUT ROWID")
        except (OSError, sqlite3.Error):
            self.disabled = True

    def _connect(self):
        return sqlite3.connect(self.path, timeout=0.2)

    def _proof(self, kind, key):
        # 우발적 행 손상도 통과로 해석하지 않는다. 악의적 로컬 수정에 대한 서명은 아니다.
        return hashlib.sha256(f"v1|{self.namespace}|{kind}|{key}|passed".encode()).hexdigest()

    def known(self, kind: str, keys) -> set[str]:
        keys = sorted(set(keys))
        found = set()
        if not self.disabled and keys:
            try:
                with closing(self._connect()) as conn:
                    for i in range(0, len(keys), 400):
                        part = keys[i:i + 400]
                        rows = conn.execute(
                            "SELECT key, proof FROM passed WHERE namespace=? AND kind=? AND key IN (" +
                            ",".join("?" for _ in part) + ")", [self.namespace, kind, *part])
                        found.update(k for k, proof in rows if proof == self._proof(kind, k))
            except (OSError, sqlite3.Error):
                self.disabled = True
                found.clear()
        self.hits += len(found)
        self.misses += len(keys) - len(found)
        return found

    def remember(self, kind: str, keys) -> None:
        if self.disabled:
            return
        try:
            with closing(self._connect()) as conn, conn:
                conn.executemany("INSERT OR REPLACE INTO passed VALUES (?, ?, ?, ?)",
                                 ((self.namespace, kind, k, self._proof(kind, k)) for k in set(keys)))
        except (OSError, sqlite3.Error):
            self.disabled = True
