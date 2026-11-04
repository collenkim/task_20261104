"""AI 턴 보호(turn guard).

세션별 상태: active(턴 진행 중) / idle(턴 종료 확인) / unknown(판단 불가).
- UserPromptSubmit: 보호 잠금 안에서 active + 세대(gen)를 기록한다. 기록하지 못하면 exit 2로 프롬프트를 막는다.
- Stop: 지금 턴의 Stop임이 확인될 때만 idle로 바꾼다.
  Codex는 turn_id가 같아야 한다. Claude는 last_assistant_message가 이 턴 시작 이후의 원본에 있어야 한다
  (원본 기록이 늦을 수 있어 잠시 다시 읽는다). 확인하지 못하면 바꾸지 않는다.
- SessionEnd·경과 시간·파일 크기 정지로 idle을 추정하지 않는다.
- 원본 증거: Claude system turn_duration·stop_hook_summary·중단 기록, Codex task_complete·turn_aborted.
  hook 기록과 원본 증거를 함께 본다. 증거가 이 턴 시작보다 뒤에 있을 때만 idle로 인정한다.
- 프롬프트 시작과 자동화 Git 작업은 같은 잠금(guard.lock)을 쓴다.
"""
from __future__ import annotations

import json
import os
import sys
import time
from contextlib import contextmanager

from .common import (
    Context,
    LockTimeout,
    append_jsonl,
    ensure_dir,
    file_lock,
    native_path,
    read_json_ro,
    sha256,
    utc_now,
    write_json,
)

ACTIVE, IDLE, UNKNOWN = "active", "idle", "unknown"
# 프롬프트 시작이 Git 자동화 작업을 기다리는 최대 시간(초). UserPromptSubmit 기본 제한 30초보다 짧게.
PROMPT_LOCK_TIMEOUT = float(os.environ.get("AI_LOGGING_PROMPT_LOCK_TIMEOUT") or 20.0)
STOP_MATCH_WAIT = 10.0


class TurnStateError(Exception):
    pass


@contextmanager
def guard_lock(ctx: Context, timeout: float = PROMPT_LOCK_TIMEOUT):
    ensure_dir(ctx.state_dir)
    with file_lock(ctx.guard_lock_path, timeout=timeout):
        yield


def load_turns(ctx: Context) -> dict:
    data, status = read_json_ro(ctx.turns_path)
    if status == "missing":
        return {"schema": 1, "sessions": {}}
    if status != "ok" or not isinstance(data, dict) or not isinstance(data.get("sessions"), dict):
        raise TurnStateError("turn_state_corrupt")
    return data


def _save(ctx: Context, data: dict) -> None:
    write_json(ctx.turns_path, data)


def _log(ctx: Context, row: dict) -> None:
    try:
        append_jsonl(ctx.p("turns-log.jsonl"), dict(row, time=utc_now()))
    except OSError:
        pass


# ------------------------------------------------------------------ evidence
_CLAUDE_HINTS = (b'"promptSource"', b'"turn_duration"', b'"stop_hook_summary"', b"[Request interrupted")
_CODEX_HINTS = (b'"task_started"', b'"task_complete"', b'"turn_aborted"')


def _is_interrupt(o: dict) -> bool:
    c = (o.get("message") or {}).get("content") if isinstance(o.get("message"), dict) else None
    if isinstance(c, str):
        return c.startswith("[Request interrupted")
    if isinstance(c, list):
        return any(isinstance(x, dict) and str(x.get("text", "")).startswith("[Request interrupted") for x in c)
    return False


def claude_evidence(path: str) -> dict:
    """마지막 실제 프롬프트 위치와 그 뒤의 턴 종료 기록 위치(바이트 offset)."""
    ev = {"size": 0, "prompt_off": None, "end_off": None, "ended": False}
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError:
        ev["unreadable"] = True
        return ev
    ev["size"] = len(data)
    off = 0
    for line in data.split(b"\n"):
        start, off = off, off + len(line) + 1
        if not any(h in line for h in _CLAUDE_HINTS):
            continue
        try:
            o = json.loads(line)
        except ValueError:
            continue
        if not isinstance(o, dict):
            continue
        t = o.get("type")
        if t == "user" and "promptSource" in o and not o.get("isCompactSummary") and not o.get("isSidechain"):
            ev["prompt_off"], ev["end_off"], ev["ended"] = start, None, False
        elif (t == "system" and o.get("subtype") in ("turn_duration", "stop_hook_summary")) or (
            t == "user" and _is_interrupt(o)
        ):
            if ev["prompt_off"] is not None:
                ev["end_off"], ev["ended"] = start, True
    return ev


def codex_evidence(path: str) -> dict:
    ev = {"size": 0, "prompt_off": None, "end_off": None, "ended": False, "turn_id": None}
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError:
        ev["unreadable"] = True
        return ev
    ev["size"] = len(data)
    off = 0
    for line in data.split(b"\n"):
        start, off = off, off + len(line) + 1
        if not any(h in line for h in _CODEX_HINTS):
            continue
        try:
            o = json.loads(line)
        except ValueError:
            continue
        p = o.get("payload") if isinstance(o, dict) else None
        if not isinstance(p, dict):
            continue
        typ = p.get("type")
        if typ == "task_started":
            ev.update(prompt_off=start, end_off=None, ended=False, turn_id=p.get("turn_id"))
        elif typ in ("task_complete", "turn_aborted") and ev["prompt_off"] is not None:
            tid = p.get("turn_id")
            if tid is None or tid == ev["turn_id"]:
                ev["end_off"], ev["ended"] = start, True
    return ev


def evidence(tool: str, path: str) -> dict:
    return codex_evidence(path) if tool == "codex" else claude_evidence(path)


def combine(tool: str, ent: dict | None, ev: dict) -> tuple[str, str]:
    """hook 기록 + 원본 증거 → (상태, 근거)."""
    if ent is None:
        if ev["prompt_off"] is None:
            return IDLE, "no_turn_in_original"
        return (IDLE, "original_turn_ended") if ev["ended"] else (UNKNOWN, "open_turn_without_hook_state")
    st = ent.get("state")
    if st == ACTIVE:
        start = ent.get("start_off", 0)
        same_turn = tool != "codex" or not ent.get("turn_id") or ev.get("turn_id") == ent.get("turn_id")
        if ev["ended"] and ev["end_off"] is not None and ev["end_off"] >= start and same_turn:
            return IDLE, "original_turn_ended"
        return ACTIVE, "hook_active"
    if st == IDLE:
        idle_off = ent.get("idle_off", 0)
        if ev["prompt_off"] is not None and ev["prompt_off"] >= idle_off:
            # idle 표시 뒤에 hook 없이 시작된 턴(설정 미적용 등)
            return (IDLE, "original_turn_ended") if ev["ended"] else (UNKNOWN, "turn_started_without_hook")
        return IDLE, "hook_idle"
    if ev["ended"] and ev["end_off"] is not None and ev["end_off"] >= ent.get("start_off", 0):
        return IDLE, "original_turn_ended"
    return UNKNOWN, ent.get("why", "marked_unknown")


def effective_states(ctx: Context, targets=None) -> dict:
    """대상 세션 전체의 턴 상태. 상태 파일이 손상되면 모두 unknown."""
    from .discover import resolve_targets
    from .engine import load_state

    try:
        data = load_turns(ctx)
        corrupt = False
    except TurnStateError:
        data, corrupt = {"sessions": {}}, True
    if targets is None:
        targets = resolve_targets(ctx, load_state(ctx))
    out = {}
    for s in targets.sessions:
        if corrupt:
            out[s.key] = {"state": UNKNOWN, "why": "turn_state_corrupt"}
            continue
        ev = evidence(s.tool, s.main_path)
        st, why = combine(s.tool, data["sessions"].get(s.key), ev)
        out[s.key] = {"state": st, "why": why}
    for key, ent in data["sessions"].items():
        if key not in out and ent.get("state") != IDLE:
            # 원본을 찾지 못한 세션의 진행 중 표시는 무시하지 않는다
            out[key] = {"state": UNKNOWN, "why": "hook_" + str(ent.get("state")) + "_original_not_found"}
    return out


def busy_sessions(ctx: Context, targets=None) -> dict:
    return {k: v for k, v in effective_states(ctx, targets).items() if v["state"] != IDLE}


# --------------------------------------------------------------------- hooks
def _payload_session(tool: str, payload: dict):
    sid = payload.get("session_id")
    path = payload.get("transcript_path")
    if not isinstance(sid, str) or not sid:
        return None, None
    return f"{tool}/{sid}", (native_path(path) if isinstance(path, str) and path else None)


def _codex_key(sid_key: str, path: str | None) -> str:
    """Codex hook의 session_id가 원본 session_meta id와 다를 수 있어 원본 기준으로 맞춘다."""
    if not path:
        return sid_key
    from .discover import _read_codex_meta

    try:
        meta = _read_codex_meta(path)
    except OSError:
        meta = None
    return f"codex/{meta['id']}" if meta and meta.get("id") else sid_key


def prompt_hook(ctx: Context, tool: str) -> int:
    """UserPromptSubmit. 출력 없음(stdout은 모델 문맥에 들어간다). 실패하면 exit 2 + stderr 사유."""
    from .engine import _read_stdin_json, _register

    try:
        payload = _read_stdin_json()
        if payload.get("_invalid_input"):
            raise TurnStateError("invalid_hook_input")
        key, path = _payload_session(tool, payload)
        if key is None:
            raise TurnStateError("session_id_missing")
        if tool == "codex":
            key = _codex_key(key, path)
        size = os.path.getsize(path) if path and os.path.isfile(path) else 0
        with guard_lock(ctx, timeout=min(PROMPT_LOCK_TIMEOUT, 25.0)):
            data = load_turns(ctx)
            old = data["sessions"].get(key) or {}
            data["sessions"][key] = {
                "state": ACTIVE,
                "gen": int(old.get("gen", 0)) + 1,
                "turn_id": payload.get("turn_id"),
                "start_off": size,
                "transcript": path,
                "started": utc_now(),
            }
            _save(ctx, data)
        _log(ctx, {"event": "prompt", "session": key, "gen": data["sessions"][key]["gen"]})
        try:
            _register(ctx, tool, "UserPromptSubmit", payload)
        except OSError:
            pass
        return 0
    except LockTimeout:
        reason = "Git 자동화 작업이 진행 중이라 턴 보호 잠금을 얻지 못했습니다. 잠시 후 다시 보내세요."
    except Exception as e:  # noqa: BLE001
        reason = f"턴 보호 상태를 기록하지 못했습니다({getattr(e, 'args', [''])[0] or type(e).__name__})."
    try:
        from .engine import _record_error

        _record_error(ctx, tool, "UserPromptSubmit", "guard_failed")
    except Exception:  # noqa: BLE001
        pass
    sys.stderr.write(
        "[AI 세션 로깅] " + reason + " 프롬프트를 막았습니다. "
        "확인: python -I scripts/ai_logging/cli.py turns\n"
    )
    sys.stderr.flush()
    return 2


def _escaped(text: str) -> bytes:
    return json.dumps(text, ensure_ascii=False)[1:-1].encode("utf-8")


def _claude_stop_matches(path: str, start: int, message) -> bool:
    if not isinstance(message, str) or not message.strip() or not path:
        return False
    needle = _escaped(message[:200])
    deadline = time.monotonic() + STOP_MATCH_WAIT
    while True:
        try:
            with open(path, "rb") as f:
                f.seek(start)
                if needle in f.read():
                    return True
        except OSError:
            return False
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.25)


def on_stop(ctx: Context, tool: str, payload: dict) -> str:
    """Stop: 이 턴의 Stop으로 확인되면 idle. 반환: marked_idle|unmatched|ignored."""
    key, path = _payload_session(tool, payload)
    if key is None:
        return "ignored"
    if tool == "codex":
        key = _codex_key(key, path)
    with guard_lock(ctx, timeout=10.0):
        data = load_turns(ctx)
        ent = data["sessions"].get(key)
        ev = evidence(tool, path) if path else {"prompt_off": None, "size": 0, "turn_id": None}
        if tool == "codex":
            tid = payload.get("turn_id")
            if ent and ent.get("turn_id"):
                ok = bool(tid) and tid == ent.get("turn_id")
            else:
                ok = bool(tid) and tid == ev.get("turn_id")
        else:
            if ent and ent.get("state") == ACTIVE:
                start = ent.get("start_off", 0)
            else:
                start = ev["prompt_off"] if ev["prompt_off"] is not None else 0
            ok = _claude_stop_matches(path, start, payload.get("last_assistant_message"))
        if not ok:
            _log(ctx, {"event": "stop_unmatched", "session": key})
            return "unmatched"
        size = os.path.getsize(path) if path and os.path.isfile(path) else ev.get("size", 0)
        data["sessions"][key] = {
            "state": IDLE,
            "gen": int((ent or {}).get("gen", 0)),
            "turn_id": payload.get("turn_id") or (ent or {}).get("turn_id"),
            "start_off": (ent or {}).get("start_off", 0),
            "idle_off": size,
            "transcript": path,
            "stopped": utc_now(),
        }
        _save(ctx, data)
    _log(ctx, {"event": "stop_idle", "session": key})
    return "marked_idle"


def resolve(ctx: Context, key: str, state: str) -> dict:
    """사용자 복구 명령: 멈춘 세션(비정상 종료 등)을 사람이 확인한 뒤 idle/unknown으로 지정한다."""
    if state not in (IDLE, UNKNOWN):
        return {"ok": False, "reason": "state_must_be_idle_or_unknown"}
    with guard_lock(ctx, timeout=20):
        try:
            data = load_turns(ctx)
        except TurnStateError:
            # 손상 파일은 지우지 않고 보관한 뒤 새로 시작한다(사용자 명령일 때만).
            with open(ctx.turns_path, "rb") as f:
                raw = f.read()
            from .common import atomic_write

            atomic_write(f"{ctx.turns_path}.corrupt-{int(time.time())}", raw)
            data = {"schema": 1, "sessions": {}}
        from .discover import resolve_targets
        from .engine import load_state

        sess = {s.key: s for s in resolve_targets(ctx, load_state(ctx)).sessions}
        s = sess.get(key)
        size = os.path.getsize(s.main_path) if s and os.path.isfile(s.main_path) else 0
        old = data["sessions"].get(key) or {}
        data["sessions"][key] = {"state": state, "gen": int(old.get("gen", 0)), "start_off": size,
                                 "idle_off": size, "why": "resolved_by_user", "resolved": utc_now(),
                                 "transcript": s.main_path if s else None}
        _save(ctx, data)
    _log(ctx, {"event": "resolve", "session": key, "state": state})
    return {"ok": True, "session": key, "state": state, "fingerprint": sha256(key.encode())[:8]}
