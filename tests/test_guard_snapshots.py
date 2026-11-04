"""2단계 테스트: 불변 스냅샷, 로그 전용 커밋, 턴 보호, 예약 작업, Git hook 검사.

가짜 원본·임시 저장소만 사용한다. 가짜 비밀값은 실행 중에 조립한다.
"""
import io
import json
import os
import subprocess
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest import mock

from test_ai_logging import (  # noqa: E402
    FAKE_ACCOUNT,
    OTHER_EMAIL,
    REPO,
    Env,
    claude_records,
    jl,
    rb,
)

from ailog import common, engine, gitops, githooks, rules, snapshot, tasks, turns  # noqa: E402

CLI = os.path.join(REPO, "scripts", "ai_logging", "cli.py")


def prompt_rec(text, n=1):
    return {"type": "user", "promptSource": "typed", "turnOrigin": "human",
            "turnPosition": {"promptIndex": n, "turnIndex": n}, "timestamp": "2026-11-04T01:00:0%d.000Z" % n,
            "message": {"role": "user", "content": text}}


def answer_rec(text):
    return {"type": "assistant", "timestamp": "2026-11-04T01:00:05.000Z",
            "message": {"role": "assistant", "content": [{"type": "text", "text": text}]}}


END = {"type": "system", "subtype": "turn_duration", "durationMs": 10, "timestamp": "2026-11-04T01:00:06.000Z"}


def codex_event(kind, turn_id):
    return {"timestamp": "2026-11-04T01:00:00.000Z", "type": "event_msg", "payload": {"type": kind, "turn_id": turn_id}}


def run_hook(env, tool, event, payload, extra_env=None):
    e = dict(os.environ, AI_LOGGING_ROOT=env.root, AI_LOGGING_CLAUDE_HOME=env.claude, AI_LOGGING_CODEX_HOME=env.codex)
    e.update(extra_env or {})
    return subprocess.run([sys.executable, "-I", CLI, "hook", tool, event], input=json.dumps(payload).encode(),
                          capture_output=True, env=e, timeout=120)


class Base(unittest.TestCase):
    git_repo = False

    def setUp(self):
        self.env = Env(git_repo=self.git_repo)
        self.addCleanup(self.env.cleanup)
        self.ctx = self.env.ctx
        self.env.write_mask()

    def claude(self, sid, *records):
        return self.env.claude_session("proj-root", sid, claude_records(self.env.root) + jl(*records))

    def append(self, path, *records):
        with open(path, "ab") as f:
            f.write(jl(*records))


# ================================================================== snapshots
class SnapshotTests(Base):
    def test_same_original_same_id_and_no_rewrite(self):
        self.claude("s1", prompt_rec("hi"), answer_rec("ok"), END)
        r1 = snapshot.snapshot_all(self.ctx, refs=())
        self.assertEqual(r1["result"], "ok", r1)
        base = r1["sessions"][0]["base"]
        self.assertRegex(base, r"/L\d{20}-[0-9a-f]{16}/$")
        m = json.loads(rb(os.path.join(self.env.root, *base.split("/"), "manifest.json")))
        self.assertEqual(len(m["snapshot_sha256"]), 64)
        self.assertTrue(m["snapshot_id"].endswith(m["snapshot_sha256"][:16]))
        self.assertEqual(snapshot.snapshot_id(m), m["snapshot_id"])
        r2 = snapshot.snapshot_all(self.ctx, refs=())
        self.assertEqual(r2["sessions"][0]["base"], base)
        self.assertEqual(r2["sessions"][0]["status"], "exists")

    def test_mask_list_change_creates_new_policy_dir(self):
        self.claude("s2", prompt_rec("hi"))
        b1 = snapshot.snapshot_all(self.ctx, refs=())["sessions"][0]["base"]
        with open(self.ctx.mask_path, "a", encoding="utf-8") as f:
            f.write(json.dumps({"kind": "literal", "value": "zz-extra-value"}) + "\n")
        b2 = snapshot.snapshot_all(self.ctx, refs=())["sessions"][0]["base"]
        self.assertNotEqual(b1.split("/")[4], b2.split("/")[4])  # 정책 폴더가 다르다

    def test_rewrite_same_length_is_held(self):
        p = self.claude("s3", prompt_rec("abcdef"))
        self.assertEqual(snapshot.snapshot_all(self.ctx, refs=())["result"], "ok")
        data = rb(p).replace(b"abcdef", b"abcdeg")
        with open(p, "wb") as f:
            f.write(data)
        r = snapshot.snapshot_all(self.ctx, refs=())
        self.assertEqual(r["result"], "hold")
        self.assertIn("original_rewritten", [h["rule"] for h in r["holds"]])

    def test_unterminated_tail_rules(self):
        p = self.claude("s4", prompt_rec("x"))
        with open(p, "ab") as f:
            f.write(json.dumps(answer_rec("done")).encode())  # 개행 없는 유효 JSON 꼬리
        r = snapshot.snapshot_all(self.ctx, refs=(), states={"claude/s4": {"state": "active"}})
        self.assertEqual(r["sessions"][0]["deferred"][0]["reason"], "unterminated_tail")
        comp = snapshot.completeness(self.ctx, None)
        self.assertIn("snapshot_behind_original", [f["rule"] for f in comp["failures"]])
        r = snapshot.snapshot_all(self.ctx, refs=(), states={"claude/s4": {"state": "idle"}})
        self.assertNotIn("deferred", r["sessions"][0])
        m = json.loads(rb(os.path.join(self.env.root, *r["sessions"][0]["base"].split("/"), "manifest.json")))
        self.assertTrue(m["files"][0]["ends_without_newline"])
        self.assertTrue(snapshot.completeness(self.ctx, None)["complete"])

    def test_broken_tail_is_never_included(self):
        p = self.claude("s5", prompt_rec("x"))
        with open(p, "ab") as f:
            f.write(b'{"type":"assistant","cut')
        r = snapshot.snapshot_all(self.ctx, refs=(), states={"claude/s5": {"state": "idle"}})
        self.assertTrue(r["sessions"][0]["deferred"])
        self.assertFalse(snapshot.completeness(self.ctx, None)["complete"])

    def test_large_file_warns_without_truncation(self):
        p = self.claude("s6", prompt_rec("y" * 300))
        with mock.patch.object(snapshot, "LARGE_FILE_BYTES", 100):
            r = snapshot.snapshot_all(self.ctx, refs=())
        self.assertIn("large_file", [w["rule"] for w in r["warnings"]])
        m = json.loads(rb(os.path.join(self.env.root, *r["sessions"][0]["base"].split("/"), "manifest.json")))
        self.assertEqual(m["files"][0]["original_bytes"], len(rb(p)))

    def test_attachment_deleted_from_original_is_held(self):
        e = self.env
        p = e.claude_session("proj-root", "s7", claude_records(e.root), {"tool-results/a.txt": b"aaa\n"})
        self.assertEqual(snapshot.snapshot_all(self.ctx, refs=())["result"], "ok")
        os.remove(os.path.join(os.path.dirname(p), "s7", "tool-results", "a.txt"))
        r = snapshot.snapshot_all(self.ctx, refs=())
        self.assertIn("original_file_deleted", [h["rule"] for h in r["holds"]])

    def test_secret_in_snapshot_blocks(self):
        self.claude("s8", prompt_rec("mail " + OTHER_EMAIL))
        r = snapshot.snapshot_all(self.ctx, refs=())
        self.assertEqual(r["result"], "hold")
        self.assertFalse(os.path.isdir(self.ctx.snapshots_dir) and os.listdir(self.ctx.snapshots_dir))

    def test_live_covered_by_snapshot(self):
        p = self.claude("s9", prompt_rec("x"))
        engine.sync(self.ctx)
        self.assertFalse(snapshot.live_covered(self.ctx)["covered"])
        self.append(p, answer_rec("more"))
        snapshot.snapshot_all(self.ctx, refs=())
        r = snapshot.live_covered(self.ctx)
        self.assertTrue(r["covered"], r)


class RulesTests(unittest.TestCase):
    def test_repo_gitattributes_keep_log_bytes(self):
        lines = rb(os.path.join(REPO, ".gitattributes")).decode().splitlines()
        self.assertIn("ai-logs/snapshots/** -text -merge", lines)

    def test_messages(self):
        ok = "[AI-LOG-001] 제목\n\n  작업 내용\n\nCo-Authored-By: X <x@example.com>\n"
        self.assertEqual(rules.check_message(ok), [])
        self.assertEqual(rules.check_message("[AI-LOG-001] 제목\n\n  내용\n\n(cherry picked from commit abcdef1)\n"), [])
        self.assertEqual(rules.check_message("fix stuff"), ["title_format"])
        self.assertEqual(rules.check_message("[AI-LOG-001] 제목"), ["body_missing"])
        self.assertEqual(rules.check_message("[AI-LOG-001] 제목\n\n내용 들여쓰기 없음\n"), ["body_indent"])

    def test_forbidden_and_modified_log_paths(self):
        none = lambda p: None  # noqa: E731
        self.assertIn("forbidden_path", rules.check_changes([("A", "ai-logs/live/x")], none, allow_source=True))
        self.assertIn("forbidden_path", rules.check_changes([("A", ".ai-logging/mask-values.jsonl")], none,
                                                            allow_source=True))
        snap = "ai-logs/snapshots/claude/s/0123456789ab/L%020d-%s/manifest.json" % (1, "0" * 16)
        self.assertIn("snapshot_modified_or_deleted", rules.check_changes([("M", snap)], none, allow_source=True))
        self.assertIn("non_log_change", rules.check_changes([("M", "src/a.py")], none, allow_source=False))


# ================================================================ log commits
class LogCommitTests(Base):
    git_repo = True

    def test_log_commit_is_log_only_single_parent_and_complete(self):
        e = self.env
        engine.ensure_local_exclude(self.ctx)
        self.claude("c1", prompt_rec("x"), answer_rec("y"), END)
        r = snapshot.commit_snapshots(self.ctx, "AI-LOG-009", "세션 로그 스냅샷 (테스트)")
        self.assertTrue(r["committed"], r)
        head = gitops.rev(e.root, "HEAD")
        self.assertEqual(len(gitops.parents(e.root, head)), 1)
        self.assertTrue(tasks.is_log_only(e.root, head))
        self.assertTrue(snapshot.completeness(self.ctx, head)["complete"])
        self.assertNotIn("ai-logs", e.git("status", "--porcelain"))  # index·작업 폴더가 HEAD와 일치
        r2 = snapshot.commit_snapshots(self.ctx, "AI-LOG-009", "세션 로그 스냅샷 (테스트)")
        self.assertFalse(r2["committed"])  # 빈 커밋 없음
        self.assertEqual(gitops.rev(e.root, "HEAD"), head)

    def test_crlf_bytes_preserved_with_autocrlf(self):
        e = self.env
        e.git("config", "core.autocrlf", "true")
        p = self.claude("c2", prompt_rec("line1\r\nline2"))
        with open(p, "ab") as f:
            f.write(b'{"type":"x","t":"crlf"}\r\n')
        r = snapshot.commit_snapshots(self.ctx, "AI-LOG-009", "스냅샷")
        self.assertTrue(r["committed"], r)
        files = gitops.tree_files(e.root, "HEAD", "ai-logs/snapshots")
        main = next(oid for path, (_, oid) in files.items() if path.endswith("/files/c2.jsonl"))
        blob = gitops.cat_blobs(e.root, [main])[main]
        self.assertTrue(blob.endswith(b"\r\n"))
        self.assertEqual(blob, engine.build_policy(self.ctx).mask(rb(p))[0])  # 개행 변환 없이 마스킹 결과 그대로

    def test_held_session_is_left_out_and_others_are_committed(self):
        e = self.env
        self.claude("ok1", prompt_rec("x"), answer_rec("y"), END)
        self.claude("held1", prompt_rec("mail " + OTHER_EMAIL), answer_rec("y"), END)
        r = snapshot.commit_snapshots(self.ctx, "AI-LOG-009", "스냅샷")
        self.assertTrue(r["ok"] and r["committed"], r)
        self.assertEqual(r["held"], {"claude/held1": 1})
        keys = {f"{m['tool']}/{m['session_id']}" for m in snapshot.tree_manifests(e.root, r["commit"]).values()}
        self.assertEqual(keys, {"claude/ok1"})  # 보류 세션은 사본도 커밋하지 않는다
        body = e.git("log", "-1", "--format=%B", r["commit"])
        self.assertIn("claude/held1: 보류 1건으로 이번 커밋 제외", body)
        self.assertNotIn(OTHER_EMAIL, body)
        self.assertEqual(snapshot.scan_tree_secrets(self.ctx, r["commit"], engine.build_policy(self.ctx)), [])
        # 보류만 남은 경우: 실패가 아니라 커밋할 것 없음
        r2 = snapshot.commit_snapshots(self.ctx, "AI-LOG-009", "스냅샷")
        self.assertTrue(r2["ok"] and not r2["committed"], r2)
        self.assertEqual(r2["held"], {"claude/held1": 1})

    def test_refuses_when_staged_or_operation_in_progress(self):
        e = self.env
        self.claude("c3", prompt_rec("x"))
        with open(os.path.join(e.root, "w.txt"), "w") as f:
            f.write("w\n")
        e.git("add", "w.txt")
        r = snapshot.commit_snapshots(self.ctx, "AI-LOG-009", "스냅샷")
        self.assertEqual(r["reason"], "staged_changes_present")
        e.git("reset", "-q")  # 테스트 준비 단계(사용자 역할)
        gd = gitops.git_dir(e.root)
        open(os.path.join(gd, "MERGE_HEAD"), "w").close()
        r = snapshot.commit_snapshots(self.ctx, "AI-LOG-009", "스냅샷")
        self.assertEqual(r["reason"], "git_operation_in_progress")


# ================================================================ turn guard
class TurnGuardTests(Base):
    def test_codex_late_stop_does_not_idle_new_turn(self):
        e = self.env
        p = e.codex_session("sessions", "cx-1", e.root, jl(codex_event("task_started", "t1")))
        payload = {"session_id": "cx-1", "transcript_path": p, "cwd": e.root}
        r = run_hook(e, "codex", "UserPromptSubmit", dict(payload, turn_id="t1"))
        self.assertEqual((r.returncode, r.stdout), (0, b""))
        self.append(p, codex_event("task_complete", "t1"))
        r = run_hook(e, "codex", "UserPromptSubmit", dict(payload, turn_id="t2"))
        self.append(p, codex_event("task_started", "t2"))
        r = run_hook(e, "codex", "Stop", dict(payload, turn_id="t1"))  # 늦게 도착한 이전 턴 Stop
        self.assertEqual(r.stdout, b"{}")
        self.assertEqual(turns.effective_states(self.ctx)["codex/cx-1"]["state"], "active")
        run_hook(e, "codex", "Stop", dict(payload, turn_id="t2"))
        self.assertEqual(turns.effective_states(self.ctx)["codex/cx-1"]["state"], "idle")

    def test_claude_stop_must_match_this_turn(self):
        p = self.claude("cl-1", prompt_rec("first"), answer_rec("old answer"), END)
        payload = {"session_id": "cl-1", "transcript_path": p, "cwd": self.env.root}
        with mock.patch.object(turns, "STOP_MATCH_WAIT", 0.3):
            self.assertEqual(run_hook(self.env, "claude", "UserPromptSubmit", payload).returncode, 0)
            self.append(p, prompt_rec("second", 2))
            self.assertEqual(turns.on_stop(self.ctx, "claude", dict(payload, last_assistant_message="old answer")),
                             "unmatched")
            self.assertEqual(turns.effective_states(self.ctx)["claude/cl-1"]["state"], "active")
            self.append(p, answer_rec("new answer\nline2"))
            self.assertEqual(turns.on_stop(self.ctx, "claude", dict(payload, last_assistant_message="new answer\nline2")),
                             "marked_idle")
        self.assertEqual(turns.effective_states(self.ctx)["claude/cl-1"]["state"], "idle")

    def test_interrupt_and_turn_end_evidence_only_after_turn_start(self):
        p = self.claude("cl-2", prompt_rec("a"), answer_rec("b"), END)
        payload = {"session_id": "cl-2", "transcript_path": p, "cwd": self.env.root}
        run_hook(self.env, "claude", "UserPromptSubmit", payload)
        self.assertEqual(turns.effective_states(self.ctx)["claude/cl-2"]["state"], "active")  # 이전 턴의 END는 무시
        self.append(p, prompt_rec("c", 2), {"type": "user", "message": {"role": "user", "content": [
            {"type": "text", "text": "[Request interrupted by user]"}]}})
        self.assertEqual(turns.effective_states(self.ctx)["claude/cl-2"]["state"], "idle")

    def test_session_end_never_marks_idle(self):
        p = self.claude("cl-3", prompt_rec("a"))
        payload = {"session_id": "cl-3", "transcript_path": p, "cwd": self.env.root}
        run_hook(self.env, "claude", "UserPromptSubmit", payload)
        run_hook(self.env, "claude", "SessionEnd", dict(payload, reason="other"))
        self.assertEqual(turns.effective_states(self.ctx)["claude/cl-3"]["state"], "active")

    def test_open_turn_without_hook_state_is_unknown(self):
        self.claude("cl-4", prompt_rec("a"))
        self.assertEqual(turns.effective_states(self.ctx)["claude/cl-4"]["state"], "unknown")
        r = turns.resolve(self.ctx, "claude/cl-4", "idle")
        self.assertTrue(r["ok"])
        self.assertEqual(turns.effective_states(self.ctx)["claude/cl-4"]["state"], "idle")

    def test_prompt_blocked_when_guard_cannot_be_recorded(self):
        p = self.claude("cl-5", prompt_rec("a"))
        payload = {"session_id": "cl-5", "transcript_path": p, "cwd": self.env.root}
        with open(self.ctx.turns_path, "wb") as f:
            f.write(b"{corrupt")
        r = run_hook(self.env, "claude", "UserPromptSubmit", payload)
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout, b"")
        self.assertIn("[AI".encode(), r.stderr)
        self.assertEqual(rb(self.ctx.turns_path), b"{corrupt")  # 손상 파일을 고치거나 지우지 않음
        r = run_hook(self.env, "claude", "UserPromptSubmit", {"cwd": self.env.root})  # session_id 없음
        self.assertEqual(r.returncode, 2)

    def test_prompt_blocked_while_git_automation_holds_lock(self):
        p = self.claude("cl-6", prompt_rec("a"))
        payload = {"session_id": "cl-6", "transcript_path": p, "cwd": self.env.root}
        with turns.guard_lock(self.ctx):
            r = run_hook(self.env, "claude", "UserPromptSubmit", payload, {"AI_LOGGING_PROMPT_LOCK_TIMEOUT": "1"})
        self.assertEqual(r.returncode, 2)
        self.assertIn(b"[AI", r.stderr)

    def test_stop_hook_errors_do_not_rerun_model(self):
        p = self.claude("cl-7", prompt_rec("a"))
        os.makedirs(self.ctx.state_dir, exist_ok=True)
        with open(self.ctx.turns_path, "wb") as f:
            f.write(b"{corrupt")
        r = run_hook(self.env, "claude", "Stop", {"session_id": "cl-7", "transcript_path": p,
                                                  "last_assistant_message": "x"})
        self.assertEqual((r.returncode, r.stdout), (0, b""))
        r = run_hook(self.env, "codex", "Stop", {"session_id": "cl-7", "transcript_path": p})
        self.assertEqual((r.returncode, r.stdout), (0, b"{}"))


# ============================================================== reserved tasks
class TaskTests(Base):
    git_repo = True

    def setUp(self):
        super().setUp()
        engine.ensure_local_exclude(self.ctx)
        self.p = self.claude("tk", prompt_rec("a"), answer_rec("b"), END)

    def msg(self, title="작업"):
        return f"[AI-LOG-009] {title}\n\n  내용\n"

    def write(self, name, text):
        with open(os.path.join(self.env.root, name), "w") as f:
            f.write(text)

    def test_tasks_wait_for_idle_then_run_in_order(self):
        e = self.env
        base = gitops.rev(e.root, "HEAD")
        self.write("a.txt", "1\n")
        self.assertTrue(tasks.reserve(self.ctx, "commit", {"paths": ["a.txt"], "message": self.msg("a")})["ok"])
        self.assertTrue(tasks.reserve(self.ctx, "squash", {"onto": base, "message": self.msg("합침")})["ok"])
        self.assertTrue(tasks.reserve(self.ctx, "stage-log", {"task": "AI-LOG-009", "stage": "1단계"})["ok"])
        self.append(self.p, prompt_rec("next", 2))  # 진행 중인 턴
        r = tasks.run_pending(self.ctx)
        self.assertEqual(r["reason"], "ai_turn_not_idle")
        self.assertEqual(gitops.rev(e.root, "HEAD"), base)
        self.append(self.p, answer_rec("c"), END)
        self.write("b.txt", "2\n")  # 예약 뒤 수정한 파일은 경로에 없으므로 커밋에 들어가지 않는다
        r = tasks.run_pending(self.ctx)
        self.assertEqual(r["reason"], "ok", r)
        log = e.git("log", "--format=%s", f"{base}..HEAD").splitlines()
        self.assertEqual(log, ["[AI-LOG-009] 세션 로그 스냅샷 (1단계)", "[AI-LOG-009] 합침"])
        self.assertIn("b.txt", e.git("status", "--porcelain"))
        self.assertTrue(e.git("for-each-ref", "refs/ai-logging/backup/").strip())

    def test_task_refuses_on_staged_changes_without_touching_index(self):
        e = self.env
        self.write("a.txt", "1\n")
        tasks.reserve(self.ctx, "commit", {"paths": ["a.txt"], "message": self.msg()})
        self.write("u.txt", "u\n")
        e.git("add", "u.txt")
        r = tasks.run_pending(self.ctx)
        self.assertEqual(r["reason"], "staged_changes_present")
        self.assertIn("u.txt", e.git("diff", "--cached", "--name-only"))
        self.assertEqual(tasks.listing(self.ctx)[0]["status"], "failed")
        self.assertEqual(tasks.run_pending(self.ctx)["reason"], "blocked_by_failed_task")

    def test_task_refuses_when_head_moved(self):
        e = self.env
        self.write("a.txt", "1\n")
        tasks.reserve(self.ctx, "commit", {"paths": ["a.txt"], "message": self.msg()})
        e.git("commit", "-q", "--allow-empty", "-m", "[AI-TEST-001] other\n\n  x")
        self.assertEqual(tasks.run_pending(self.ctx)["reason"], "head_changed")

    def test_reserve_rejects_bad_message_and_log_paths(self):
        self.assertEqual(tasks.reserve(self.ctx, "commit", {"paths": ["a"], "message": "bad"})["reason"],
                         "message_format")
        self.assertEqual(tasks.reserve(self.ctx, "commit", {"paths": ["ai-logs/x"], "message": self.msg()})["reason"],
                         "path_not_allowed")
        self.assertEqual(tasks.reserve(self.ctx, "commit", {"paths": ["."], "message": self.msg()})["reason"],
                         "path_not_allowed")

    def test_squash_refuses_log_commits_in_range(self):
        e = self.env
        base = gitops.rev(e.root, "HEAD")
        snapshot.commit_snapshots(self.ctx, "AI-LOG-009", "스냅샷")
        tasks.reserve(self.ctx, "squash", {"onto": base, "message": self.msg()})
        r = tasks.run_pending(self.ctx)
        self.assertEqual(r["reason"], "log_commit_in_range")


# ================================================================ git hooks
class GitHookTests(Base):
    git_repo = True

    def setUp(self):
        super().setUp()
        engine.ensure_local_exclude(self.ctx)
        self.p = self.claude("gh", prompt_rec("a"), answer_rec("b"), END)
        env = {"AI_LOGGING_CLAUDE_HOME": self.env.claude, "AI_LOGGING_CODEX_HOME": self.env.codex}
        patcher = mock.patch.dict(os.environ, env)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.hooks = githooks.install_runtime(self.ctx)
        self.env.git("add", "config")
        self.env.git("commit", "-q", "-m", "[AI-TEST-001] 설정\n\n  허용 목록")

    def commit(self, msg, *files):
        for name in files:
            with open(os.path.join(self.env.root, name), "w") as f:
                f.write(name + "\n")
            self.env.git("add", name)
        return subprocess.run(["git", "-C", self.env.root, "commit", "-q", "-F", "-"], input=msg.encode(),
                              capture_output=True)

    def test_runtime_installed_and_verified(self):
        self.assertTrue(self.hooks["ok"], self.hooks)
        self.assertTrue(githooks.runtime_status(self.ctx)["hooks_path_ok"])

    def test_commit_msg_checks_format_turns_and_paths(self):
        e = self.env
        self.assertNotEqual(self.commit("bad", "x.txt").returncode, 0)
        self.assertEqual(self.commit("[AI-LOG-009] 기능\n\n  내용\n", "x.txt").returncode, 0)
        self.append(self.p, prompt_rec("busy", 2))
        r = self.commit("[AI-LOG-009] 기능2\n\n  내용\n", "y.txt")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn(b"ai_turn_not_idle", r.stderr)
        self.append(self.p, END)
        os.makedirs(os.path.join(e.root, "ai-logs", "live"), exist_ok=True)
        with open(os.path.join(e.root, "ai-logs", "live", "z"), "w") as f:
            f.write("z\n")
        e.git("add", "-f", "ai-logs/live/z")
        r = self.commit("[AI-LOG-009] 기능3\n\n  내용\n")
        self.assertIn(b"forbidden_path", r.stderr)

    def test_hooks_are_read_only(self):
        e = self.env
        idx = os.path.join(gitops.git_dir(e.root), "index")
        before_idx, before_state = rb(idx), sorted(os.listdir(self.ctx.state_dir))
        refs_before = e.git("for-each-ref")
        msg = os.path.join(self.env.tmp, "m")
        with open(msg, "w") as f:
            f.write("[AI-LOG-009] x\n\n  y\n")
        e.git("hook", "run", "commit-msg", "--", msg)
        self.assertEqual(rb(idx), before_idx)
        self.assertEqual(sorted(os.listdir(self.ctx.state_dir)), before_state)
        self.assertEqual(e.git("for-each-ref"), refs_before)

    def test_hooks_survive_switch_to_branch_without_scripts(self):
        e = self.env
        e.git("switch", "-q", "-c", "other")
        r = self.commit("bad", "q.txt")
        self.assertNotEqual(r.returncode, 0)  # 작업 폴더에 수집기가 없어도 런타임 사본이 실행된다
        self.assertIn(b"title_format", r.stderr)

    def test_pre_push_master_warns_on_log_lag_and_blocks_committed_secret(self):
        e = self.env
        remote = os.path.join(e.tmp, "remote.git")
        subprocess.run(["git", "init", "-q", "--bare", remote], check=True)
        e.git("remote", "add", "origin", remote)
        cur = e.git("symbolic-ref", "--short", "HEAD").strip()
        with open(self.ctx.config_path, "w") as f:
            json.dump({"main": cur}, f)
        r = subprocess.run(["git", "-C", e.root, "push", "-q", "origin", cur], capture_output=True)
        self.assertEqual(r.returncode, 0, r.stderr.decode("utf-8", "replace"))  # 스냅샷 없음: 경고만
        self.assertIn(b"logs:snapshot_missing", r.stderr)
        self.assertIn("push 진행(참고)".encode("utf-8"), r.stderr)
        self.assertTrue(snapshot.commit_snapshots(self.ctx, "AI-LOG-009", "스냅샷")["committed"])
        r = subprocess.run(["git", "-C", e.root, "push", "-q", "origin", cur], capture_output=True)
        self.assertEqual(r.returncode, 0, r.stderr.decode("utf-8", "replace"))
        # 커밋 뒤 치환 목록에 추가된 값: 이미 커밋된 스냅샷에 남아 있으면 push 중단(새 마스킹 커밋으로 못 지움)
        with open(self.ctx.mask_path, "a", encoding="utf-8") as f:
            f.write(json.dumps({"kind": "literal", "value": "프로젝트"}, ensure_ascii=False) + "\n")
        leaks = snapshot.scan_tree_secrets(self.ctx, "HEAD")
        self.assertEqual(leaks[0]["rule"], "committed_mask_residue")
        self.assertTrue(snapshot.commit_snapshots(self.ctx, "AI-LOG-009", "새 정책 스냅샷")["committed"])
        r = subprocess.run(["git", "-C", e.root, "push", "-q", "origin", cur], capture_output=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn(b"secret:committed_mask_residue", r.stderr)
        self.assertNotIn("프로젝트".encode(), r.stderr)  # 값은 출력하지 않는다


if __name__ == "__main__":
    unittest.main()
