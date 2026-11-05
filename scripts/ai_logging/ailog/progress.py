"""사용자 명령의 단계·대기 시간만 stderr에 표시한다. hook 응답 JSON은 건드리지 않는다."""
import os
import sys
import threading
import time


def report(message):
    if os.environ.get("AI_LOGGING_PROGRESS") == "1":
        print("[AI 로그] " + message, file=sys.stderr, flush=True)


def step(label, fn, *args, **kwargs):
    if os.environ.get("AI_LOGGING_PROGRESS") != "1":
        return fn(*args, **kwargs)
    start = time.monotonic()
    stopped = threading.Event()

    def heartbeat():
        while not stopped.wait(15):
            report(f"{label}: 진행 중 ({time.monotonic() - start:.1f}초)")

    report(label + ": 시작")
    thread = threading.Thread(target=heartbeat, daemon=True)
    thread.start()
    try:
        value = fn(*args, **kwargs)
    except BaseException:
        report(f"{label}: 중단 ({time.monotonic() - start:.1f}초)")
        raise
    else:
        report(f"{label}: 종료 ({time.monotonic() - start:.1f}초)")
        return value
    finally:
        stopped.set()
        thread.join(timeout=1)
