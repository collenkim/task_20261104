"""공통 경로·입출력·잠금 유틸리티."""
from __future__ import annotations

import contextlib
import hashlib
import json
import os
import re
import socket
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone

SCHEMA_VERSION = 2
# 정규식 밖의 검증 로직(임계값·판정 순서 등)이 바뀌면 올린다. 정규식 원문·허용 목록·치환 목록 digest는 자동 포함된다.
POLICY_CODE_VERSION = "5"
LIVE_REL = "ai-logs/live"
SNAPSHOTS_REL = "ai-logs/snapshots"
LARGE_FILE_BYTES = 50_000_000


@dataclass
class Context:
    """수집기가 다루는 경로 묶음. 테스트에서는 임시 경로로 만든다."""

    root: str
    claude_home: str
    codex_home: str

    @property
    def state_dir(self) -> str:
        return os.path.join(self.root, ".ai-logging")

    @property
    def live_dir(self) -> str:
        return os.path.join(self.root, *LIVE_REL.split("/"))

    @property
    def snapshots_dir(self) -> str:
        return os.path.join(self.root, *SNAPSHOTS_REL.split("/"))

    @property
    def allowlist_path(self) -> str:
        return os.path.join(self.root, "config", "ai-logs-allowlist.json")

    @property
    def config_path(self) -> str:
        return os.path.join(self.root, "config", "ai-logging.json")

    def p(self, name: str) -> str:
        return os.path.join(self.state_dir, name)

    mask_path = property(lambda self: self.p("mask-values.jsonl"))
    state_path = property(lambda self: self.p("state.json"))
    state_prev_path = property(lambda self: self.p("state.json.prev"))
    registry_path = property(lambda self: self.p("registry.jsonl"))
    last_sync_path = property(lambda self: self.p("last-sync.json"))
    errors_path = property(lambda self: self.p("errors.jsonl"))
    lock_path = property(lambda self: self.p("sync.lock"))
    guard_lock_path = property(lambda self: self.p("guard.lock"))
    turns_path = property(lambda self: self.p("turns.json"))
    tasks_path = property(lambda self: self.p("tasks.jsonl"))
    runs_dir = property(lambda self: self.p("runs"))
    index_path = property(lambda self: self.p("index.json"))


def default_context() -> Context:
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.environ.get("AI_LOGGING_ROOT") or os.path.dirname(os.path.dirname(os.path.dirname(here)))
    home = os.path.expanduser("~")
    claude_home = (
        os.environ.get("AI_LOGGING_CLAUDE_HOME")
        or os.environ.get("CLAUDE_CONFIG_DIR")
        or os.path.join(home, ".claude")
    )
    codex_home = os.environ.get("AI_LOGGING_CODEX_HOME") or os.environ.get("CODEX_HOME") or os.path.join(home, ".codex")
    return Context(root=root, claude_home=claude_home, codex_home=codex_home)


_POSIX_DRIVE = re.compile(r"^/([a-zA-Z])(?:/|$)")


def norm_path(p: str) -> str:
    """경로 비교용 정규화. Git Bash 형식(/c/...)도 Windows 경로로 바꾼다."""
    if os.name == "nt":
        m = _POSIX_DRIVE.match(p)
        if m:
            p = m.group(1) + ":\\" + p[m.end():]
    return os.path.normcase(os.path.normpath(p))


def native_path(p: str) -> str:
    """hook 입력 경로를 이 OS에서 열 수 있는 형태로(대소문자는 유지). /c/... → C:/..."""
    if os.name == "nt":
        m = _POSIX_DRIVE.match(p)
        if m:
            p = m.group(1).upper() + ":\\" + p[m.end():]
    return os.path.normpath(p)


def is_within(path: str, root: str) -> bool:
    n, r = norm_path(path), norm_path(root)
    return n == r or n.startswith(r.rstrip("\\/") + os.sep)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def ensure_dir(path: str) -> None:
    os.makedirs(path, exist_ok=True)


def atomic_write(path: str, data: bytes) -> None:
    """같은 폴더의 임시 파일에 쓴 뒤 교체한다. Windows 일시 잠금은 재시도한다."""
    ensure_dir(os.path.dirname(path))
    tmp = f"{path}.tmp-{os.getpid()}-{threading.get_ident()}"
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    for attempt in range(20):
        try:
            os.replace(tmp, path)
            return
        except PermissionError:
            if attempt == 19:
                raise
            time.sleep(0.1)


def dumps_json(obj) -> bytes:
    """결정적인 JSON 직렬화(키 정렬, LF). manifest·상태 파일 전용."""
    return (json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def read_json_ro(path: str):
    """읽기 전용 JSON 읽기. 파일을 옮기거나 고치지 않는다. (값, "ok"|"missing"|"corrupt")."""
    try:
        with open(path, "rb") as f:
            raw = f.read()
    except FileNotFoundError:
        return None, "missing"
    except OSError:
        return None, "corrupt"
    try:
        return json.loads(raw.decode("utf-8")), "ok"
    except (ValueError, UnicodeDecodeError):
        return None, "corrupt"


def read_json(path: str, default):
    """읽기 전용 편의 함수: 없거나 손상이면 default. 손상 파일을 옮기지 않는다."""
    obj, status = read_json_ro(path)
    return obj if status == "ok" else default


def write_json(path: str, obj) -> None:
    atomic_write(path, dumps_json(obj))


def append_jsonl(path: str, obj) -> None:
    ensure_dir(os.path.dirname(path))
    line = json.dumps(obj, ensure_ascii=False, sort_keys=True) + "\n"
    with open(path, "ab") as f:
        f.write(line.encode("utf-8"))


def read_jsonl(path: str) -> list:
    out = []
    try:
        with open(path, "rb") as f:
            for raw in f:
                raw = raw.strip()
                if not raw:
                    continue
                try:
                    out.append(json.loads(raw.decode("utf-8")))
                except (ValueError, UnicodeDecodeError):
                    out.append({"_corrupt": True})
    except FileNotFoundError:
        pass
    return out


# ------------------------------------------------------------------ process
def process_start_token(pid: int):
    """실행 중인 프로세스의 시작 시각 식별자. 실행 중이 아니면 None.

    PID 재사용을 구분하기 위해 잠금 소유자 확인에 쓴다. 확인할 수 없지만 살아 있으면 "alive".
    """
    if not isinstance(pid, int) or pid <= 0:
        return None
    if os.name == "nt":
        return _win_start_token(pid)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return None
    except PermissionError:
        return "alive"
    try:
        with open(f"/proc/{pid}/stat", "rb") as f:
            fields = f.read().rsplit(b")", 1)[1].split()
        return fields[19].decode()  # starttime (22번째 필드)
    except (OSError, IndexError):
        return "alive"


def _win_start_token(pid: int):
    import ctypes
    from ctypes import wintypes

    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    k32.OpenProcess.restype = wintypes.HANDLE
    k32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    k32.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    k32.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    k32.CloseHandle.argtypes = [wintypes.HANDLE]
    h = k32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return "alive" if ctypes.get_last_error() == 5 else None  # 5 = 접근 거부(살아 있음)
    try:
        code = wintypes.DWORD()
        if not k32.GetExitCodeProcess(h, ctypes.byref(code)) or code.value != 259:  # STILL_ACTIVE
            return None
        times = [wintypes.FILETIME() for _ in range(4)]
        if not k32.GetProcessTimes(h, *[ctypes.byref(t) for t in times]):
            return "alive"
        return str((times[0].dwHighDateTime << 32) | times[0].dwLowDateTime)
    finally:
        k32.CloseHandle(h)


def owner_alive(owner: dict) -> bool:
    tok = process_start_token(owner.get("pid"))
    if tok is None:
        return False
    if owner.get("host") not in (None, socket.gethostname()):
        return True  # 다른 컴퓨터의 소유자는 확인할 수 없다 → 살아 있다고 본다
    return tok == "alive" or owner.get("start") in (None, "alive") or tok == owner.get("start")


# --------------------------------------------------------------------- locks
class LockTimeout(Exception):
    pass


_held: dict[str, list] = {}  # 같은 프로세스 안의 재진입 잠금: path -> [nonce, count]
_held_mutex = threading.Lock()
_ME = None


def _my_owner(nonce: str) -> dict:
    global _ME
    if _ME is None:
        _ME = {"pid": os.getpid(), "start": process_start_token(os.getpid()), "host": socket.gethostname()}
    return dict(_ME, nonce=nonce, time=utc_now())


def _read_owner(path: str):
    try:
        with open(path, "rb") as f:
            raw = f.read()
    except FileNotFoundError:
        return "missing"
    except OSError:
        return None
    try:
        o = json.loads(raw.decode("utf-8"))
        return o if isinstance(o, dict) else None
    except (ValueError, UnicodeDecodeError):
        return None


def _try_reclaim(path: str) -> None:
    """소유자가 실제로 종료된 잠금만 회수한다. 회수 자체도 별도 잠금으로 한 번에 하나만."""
    rpath = path + ".reclaim"
    try:
        fd = os.open(rpath, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        owner = _read_owner(rpath)
        if isinstance(owner, dict) and not owner_alive(owner):
            with contextlib.suppress(OSError):
                os.remove(rpath)
        elif owner is None:
            with contextlib.suppress(OSError):
                if time.time() - os.path.getmtime(rpath) > 30:
                    os.remove(rpath)
        return
    try:
        os.write(fd, json.dumps(_my_owner(uuid.uuid4().hex)).encode())
        os.close(fd)
        owner = _read_owner(path)
        if owner == "missing":
            return
        if owner is None:
            # 내용 없는 잠금(생성 직후 비정상 종료): 충분히 오래된 경우만 회수
            if time.time() - os.path.getmtime(path) > 30:
                os.remove(path)
            return
        if not owner_alive(owner):
            again = _read_owner(path)
            if isinstance(again, dict) and again.get("nonce") == owner.get("nonce"):
                os.remove(path)
    except OSError:
        pass
    finally:
        with contextlib.suppress(OSError):
            os.remove(rpath)


@contextlib.contextmanager
def file_lock(path: str, timeout: float = 60.0, stale_after: float = 0.0):
    """소유자(pid·시작 시각·nonce)를 기록하는 O_EXCL 파일 잠금.

    - 나이만으로 회수하지 않는다. 소유 프로세스가 실제로 끝난 경우에만 회수한다(stale_after는 호환용·무시).
    - 해제는 자기 nonce일 때만 한다. 같은 프로세스 안에서는 재진입할 수 있다.
    """
    key = os.path.normcase(os.path.abspath(path))
    with _held_mutex:
        if key in _held:
            _held[key][1] += 1
            reentrant = True
        else:
            reentrant = False
    if reentrant:
        try:
            yield
        finally:
            with _held_mutex:
                _held[key][1] -= 1
                if _held[key][1] == 0:
                    del _held[key]
        return
    ensure_dir(os.path.dirname(path))
    nonce = uuid.uuid4().hex
    start = time.time()
    while True:
        try:
            fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            _try_reclaim(path)
            if time.time() - start > timeout:
                raise LockTimeout(os.path.basename(path)) from None
            time.sleep(0.1)
            continue
        try:
            os.write(fd, json.dumps(_my_owner(nonce)).encode())
        finally:
            os.close(fd)
        break
    with _held_mutex:
        _held[key] = [nonce, 1]
    try:
        yield
    finally:
        with _held_mutex:
            _held.pop(key, None)
        owner = _read_owner(path)
        if isinstance(owner, dict) and owner.get("nonce") == nonce:
            with contextlib.suppress(FileNotFoundError):
                os.remove(path)


# ----------------------------------------------------------------------- git
class GitError(Exception):
    def __init__(self, args, code, stderr=b""):
        super().__init__(f"git {args[0] if args else ''} 실패(exit {code})")
        self.code = code
        self.stderr = stderr


def git(root: str, *args: str, timeout: float = 10.0):
    """git 명령의 표준 출력(문자열). 실패하면 None."""
    try:
        r = subprocess.run(["git", "-C", root, *args], capture_output=True, timeout=timeout, check=False)
    except (OSError, subprocess.SubprocessError):
        return None
    if r.returncode != 0:
        return None
    return r.stdout.decode("utf-8", "replace").strip()


def git_run(root: str, *args: str, input: bytes | None = None, env: dict | None = None, timeout: float = 120.0,
            check: bool = True) -> subprocess.CompletedProcess:
    """git 실행(바이트 입출력). check=True면 실패 시 GitError."""
    full_env = None
    if env:
        full_env = dict(os.environ)
        full_env.update(env)
    r = subprocess.run(["git", "-C", root, *args], input=input, capture_output=True, env=full_env, timeout=timeout,
                       check=False)
    if check and r.returncode != 0:
        raise GitError(args, r.returncode, r.stderr)
    return r
