"""검토에서 재현된 수집기 결함(a~j)의 회귀 테스트.

각 테스트는 수정 전 코드에서 실패했고 수정 후 통과해야 한다(README '검증 기록' 참고).
가짜 원본·임시 저장소만 사용한다.
"""
import json
import os
import subprocess
import sys
import time
import unittest
from unittest import mock

from test_ai_logging import (  # noqa: E402  (tests 폴더가 sys.path에 있음)
    AT,
    OTHER_EMAIL,
    RANDOM_40,
    Env,
    base_entries,
    claude_records,
    jl,
    rb,
    repo_allowlist,
)

from ailog import common, engine, snapshot  # noqa: E402
from ailog.policy import Policy  # noqa: E402


def rules(items):
    return [x["rule"] for x in items]


class CollectorRegressionTests(unittest.TestCase):
    def setUp(self):
        self.env = Env()
        self.addCleanup(self.env.cleanup)
        self.ctx = self.env.ctx
        self.env.write_mask()

    # a. manifest에 없는 live 파일
    def test_a_unlisted_live_file_fails_final_verify(self):
        e = self.env
        e.claude_session("proj-root", "sess-a", claude_records(e.root))
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        extra = e.copy_of("claude", "sess-a", "sess-a/extra.txt")
        os.makedirs(os.path.dirname(extra), exist_ok=True)
        with open(extra, "wb") as f:
            f.write(b"not from any original\n")
        v = engine.verify(self.ctx, final=True)
        self.assertFalse(v["ok"])
        self.assertIn("unlisted_file", rules(v["failures"]))
        self.assertTrue(os.path.exists(extra))  # 자동 삭제하지 않는다

    # b. 개행 없는 마지막 JSONL 기록
    def test_b_unterminated_tail_fails_final_verify(self):
        e = self.env
        e.claude_session("proj-root", "sess-b", claude_records(e.root) + b'{"type":"assistant","t":"cut')
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")  # live는 미완성 꼬리를 미룬다
        v = engine.verify(self.ctx, final=True)
        self.assertFalse(v["ok"])
        self.assertIn("incomplete_tail", rules(v["failures"]))

    # c-1. 등록됐지만 탐색되지 않은 세션(cwd 없는 원본)
    def test_c_registered_session_without_cwd_is_collected(self):
        e = self.env
        p = e.claude_session("proj-root", "sess-c", jl({"type": "user", "message": {"role": "user", "content": "x"}}))
        engine._register(self.ctx, "claude", "SessionStart", {"session_id": "sess-c", "transcript_path": p, "cwd": e.root})
        rep = engine.sync(self.ctx)
        self.assertIn("claude/sess-c", [s["key"] for s in rep["sessions"] if s.get("status") != "original_missing"])
        self.assertTrue(os.path.exists(e.copy_of("claude", "sess-c", "sess-c.jsonl")))

    # c-2. 최초 수집이 보류된 뒤 원본이 사라진 세션
    def test_c_held_then_missing_session_fails_final_verify(self):
        e = self.env
        p = e.claude_session("proj-root", "sess-c2", claude_records(e.root, [{"type": "user", "message": f"mail {OTHER_EMAIL}"}]))
        self.assertEqual(engine.sync(self.ctx)["result"], "hold")
        os.remove(p)
        engine.sync(self.ctx)
        v = engine.verify(self.ctx, final=True)
        self.assertFalse(v["ok"])
        self.assertIn("original_missing", rules(v["failures"]))

    # d. 상태 파일 손상 후 원본 재작성
    def test_d_rewrite_detected_after_state_corruption(self):
        e = self.env
        orig = claude_records(e.root)
        p = e.claude_session("proj-root", "sess-d", orig)
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        with open(self.ctx.state_path, "wb") as f:
            f.write(b"{corrupt")
        with open(p, "wb") as f:
            f.write(orig.replace(b"session-owner", b"session-0wner") + jl({"type": "assistant"}))
        rep = engine.sync(self.ctx)
        self.assertEqual(rep["result"], "hold", rep)
        self.assertIn("original_rewritten", rules(rep["holds"]))

    # e. 읽기 전용 verify가 손상 manifest 이름을 바꿈
    def test_e_verify_does_not_touch_corrupt_manifest(self):
        e = self.env
        e.claude_session("proj-root", "sess-e", claude_records(e.root))
        engine.sync(self.ctx)
        mpath = os.path.join(self.ctx.live_dir, "claude", "sess-e", "manifest.json")
        with open(mpath, "wb") as f:
            f.write(b"{broken")
        before = sorted(os.listdir(os.path.dirname(mpath)))
        state_before = rb(self.ctx.state_path)
        v = engine.verify(self.ctx, final=True)
        self.assertFalse(v["ok"])
        self.assertIn("manifest_unreadable", rules(v["failures"]))
        self.assertEqual(sorted(os.listdir(os.path.dirname(mpath))), before)
        self.assertEqual(rb(mpath), b"{broken")
        self.assertEqual(rb(self.ctx.state_path), state_before)

    # h. 텍스트 부속 파일의 추가분과 기존 끝부분 사이
    def test_h_attachment_boundary_detection(self):
        e = self.env
        local, domain = OTHER_EMAIL.split(AT)
        att = {"tool-results/out.txt": f"contact {local}{AT}{domain[:4]}".encode()}
        p = e.claude_session("proj-root", "sess-h", claude_records(e.root), att)
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        with open(os.path.join(os.path.dirname(p), "sess-h", "tool-results", "out.txt"), "ab") as f:
            f.write(domain[4:].encode() + b"\n")
        rep = engine.sync(self.ctx)
        self.assertEqual(rep["result"], "hold", rep)
        self.assertIn("email", rules(rep["holds"]))


class PolicyRegressionTests(unittest.TestCase):
    def setUp(self):
        self.policy = Policy(base_entries(), repo_allowlist())

    # f. thumbnail_url 키 이름만으로 무작위 문자열 면제
    def test_f_thumbnail_url_requires_reviewed_url_context(self):
        ok = json.dumps({"thumbnail_url": "https://images.openai.com/thumbnails/" + RANDOM_40}) + "\n"
        bad_host = json.dumps({"thumbnail_url": "https://files.example.net/thumbnails/" + RANDOM_40}) + "\n"
        bad_query = json.dumps({"thumbnail_url": "https://images.openai.com/thumbnails/x?sig=" + RANDOM_40}) + "\n"
        not_url = json.dumps({"thumbnail_url": RANDOM_40}) + "\n"
        self.assertEqual(self.policy.detect(ok.encode()), [])
        for data in (bad_host, bad_query, not_url):
            self.assertEqual([h.rule for h in self.policy.detect(data.encode())], ["random_string"], data[:40])

    # g. 일부만 마스킹된 비밀번호 후보
    def test_g_partially_masked_value_is_rechecked(self):
        full = json.dumps({"cmd": "password=****"}) + "\n"
        partial = json.dumps({"cmd": "password=****Secr" + "et99x"}) + "\n"
        self.assertEqual(self.policy.detect(full.encode()), [])
        self.assertEqual([h.rule for h in self.policy.detect(partial.encode())], ["keyword:password"])


class LockRegressionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Env()
        self.addCleanup(self.tmp.cleanup)
        self.path = os.path.join(self.tmp.tmp, "x.lock")

    # i-1. 살아 있는 소유자의 오래된 잠금을 나이만으로 회수
    def test_i_live_owner_lock_not_reclaimed_by_age(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        self.addCleanup(child.kill)
        with open(self.path, "w", encoding="utf-8") as f:
            f.write(json.dumps({"pid": child.pid, "start": common.process_start_token(child.pid), "nonce": "other"}))
        old = time.time() - 3600
        os.utime(self.path, (old, old))
        with self.assertRaises(common.LockTimeout):
            with common.file_lock(self.path, timeout=0.5):
                pass
        self.assertTrue(os.path.exists(self.path))

    # i-2. 이전 소유자가 새 소유자의 잠금을 삭제
    def test_i_previous_owner_cannot_delete_new_lock(self):
        with common.file_lock(self.path, timeout=1):
            with open(self.path, "w", encoding="utf-8") as f:  # 다른 실행이 (정당하게) 잠금을 새로 잡은 상황
                f.write(json.dumps({"pid": os.getpid(), "start": "x", "nonce": "new-owner"}))
        self.assertTrue(os.path.exists(self.path))
        with open(self.path, encoding="utf-8") as f:
            self.assertEqual(json.load(f)["nonce"], "new-owner")

    def test_i_dead_owner_lock_is_reclaimed(self):
        child = subprocess.Popen([sys.executable, "-c", "pass"])
        child.wait()
        with open(self.path, "w", encoding="utf-8") as f:
            f.write(json.dumps({"pid": child.pid, "start": "gone", "nonce": "dead"}))
        with common.file_lock(self.path, timeout=2):
            with open(self.path, encoding="utf-8") as f:
                self.assertNotEqual(json.load(f)["nonce"], "dead")
        self.assertFalse(os.path.exists(self.path))


class SubmitRegressionTests(unittest.TestCase):
    def setUp(self):
        self.env = Env(git_repo=True)
        self.addCleanup(self.env.cleanup)
        self.env.write_mask()

    # j. 검사 이후 사용자가 스테이징한 변경을 git reset으로 되돌림
    def test_j_concurrent_user_staging_is_preserved(self):
        e = self.env
        e.claude_session("proj-root", "sess-j", claude_records(e.root))
        user_file = os.path.join(e.root, "user.txt")
        real = snapshot.snapshot_all

        def snapshot_then_user_stages(ctx, *a, **k):  # 사전 검사 뒤, 커밋 전에 사용자가 스테이징
            r = real(ctx, *a, **k)
            with open(user_file, "w") as f:
                f.write("user work\n")
            e.git("add", "user.txt")
            return r

        with mock.patch.object(snapshot, "snapshot_all", side_effect=snapshot_then_user_stages):
            r = engine.submit(e.ctx, task="AI-LOG-002")
        self.assertTrue(r.get("committed"), r)
        staged = e.git("diff", "--cached", "--name-only").split()
        self.assertIn("user.txt", staged)  # 사용자 index 변경은 그대로
        self.assertNotIn("user.txt", e.git("show", "--name-only", "--format=", "HEAD").split())
        self.assertFalse([p for p in staged if p.startswith("ai-logs/")])  # 로그 경로는 index도 HEAD와 같다


if __name__ == "__main__":
    unittest.main()
