"""AI 세션 로그 수집기 테스트 (표준 unittest).

실행: python -I -m unittest discover -s tests -v

- 실제 ~/.claude, ~/.codex 대신 임시 폴더에 가짜 원본을 만든다.
- 가짜 비밀값은 실행 중에 조립한다. 소스에 완성된 형태로 쓰면 이 파일을 작성한 AI 세션 로그에
  그대로 남아 실제 검증의 검출 대상이 되기 때문이다.
"""
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(REPO, "scripts", "ai_logging"))

import cli  # noqa: E402
from ailog import engine  # noqa: E402
from ailog.common import Context, sha256  # noqa: E402
from ailog.policy import Allowlist, Policy, load_allowlist  # noqa: E402

AT = "@"
FAKE_LOCAL = "tester.fake" + "01"
FAKE_EMAIL = FAKE_LOCAL + AT + "mail-test.co"
OTHER_EMAIL = "someone.else" + AT + "corp-mail.co"
FAKE_ACCOUNT = "0f0f0f0f-1111-2222-3333-" + "444455556666"
FAKE_ORG = "0e0e0e0e-1111-2222-3333-" + "777788889999"
FAKE_USER = "tstuser"
FAKE_ANTHROPIC = "sk-" + "ant-" + "api03-" + "Ab3dEf5hIj7lMn9pQr1tUv3x" + "Yz0Ab3dEf5h"
FAKE_GH = "gh" + "p_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8"
PEM_HEAD = "-----BEGIN " + "RSA PRIVATE KEY-----"
PEM_TAIL = "-----END " + "RSA PRIVATE KEY-----"
RANDOM_40 = "Qx7" + "Lm2Rt9Vb4Nc8Zp1Kd6Hs3Wf5Jg0Ty" + "Ue2Ai9Ob"


def rb(path) -> bytes:
    with open(path, "rb") as f:
        return f.read()


def rt(path) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def jl(*records) -> bytes:
    return b"".join(json.dumps(r, ensure_ascii=False).encode("utf-8") + b"\n" for r in records)


def base_entries():
    return [
        {"kind": "literal", "value": FAKE_EMAIL},
        {"kind": "literal", "value": FAKE_LOCAL},
        {"kind": "literal", "value": FAKE_ACCOUNT},
        {"kind": "home_user", "value": FAKE_USER},
    ]


def repo_allowlist() -> Allowlist:
    return load_allowlist(os.path.join(REPO, "config", "ai-logs-allowlist.json"))


class Env:
    """임시 프로젝트 루트 + 가짜 Claude/Codex 홈."""

    def __init__(self, git_repo=False):
        self.tmp = tempfile.mkdtemp(prefix="ailog-test-")
        self.root = os.path.join(self.tmp, "proj_root")
        self.claude = os.path.join(self.tmp, "claude-home")
        self.codex = os.path.join(self.tmp, "codex-home")
        for d in (self.root, self.claude, self.codex):
            os.makedirs(d)
        os.makedirs(os.path.join(self.root, "config"))
        shutil.copy(os.path.join(REPO, "config", "ai-logs-allowlist.json"), os.path.join(self.root, "config"))
        if git_repo:
            self.git("init", "-q")
            self.git("config", "user.name", "Test")
            self.git("config", "user.email", "test" + AT + "example.com")
            self.git("config", "core.autocrlf", "false")
            with open(os.path.join(self.root, "README.md"), "w") as f:
                f.write("x\n")
            self.git("add", "README.md")
            self.git("commit", "-q", "-m", "[AI-TEST-000] init\n\n  테스트 저장소")
        self.ctx = Context(self.root, self.claude, self.codex)

    def git(self, *args):
        return subprocess.run(["git", "-C", self.root, *args], capture_output=True, check=True).stdout.decode()

    def cleanup(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def write_mask(self, entries=None):
        os.makedirs(self.ctx.state_dir, exist_ok=True)
        with open(self.ctx.mask_path, "w", encoding="utf-8") as f:
            for e in entries or base_entries():
                f.write(json.dumps(e) + "\n")

    def claude_session(self, slug, sid, data: bytes, attachments=None):
        d = os.path.join(self.claude, "projects", slug)
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, sid + ".jsonl")
        with open(p, "wb") as f:
            f.write(data)
        for rel, content in (attachments or {}).items():
            ap = os.path.join(d, sid, *rel.split("/"))
            os.makedirs(os.path.dirname(ap), exist_ok=True)
            with open(ap, "wb") as f:
                f.write(content)
        return p

    def codex_session(self, sub, sid, cwd, more: bytes = b"", meta_extra=None):
        d = os.path.join(self.codex, sub, "2026", "11", "04")
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, f"rollout-2026-11-04T08-00-00-{sid}.jsonl")
        payload = {"id": sid, "timestamp": "2026-11-03T23:00:00.000Z", "creator_account_id": FAKE_ACCOUNT}
        if cwd is not None:
            payload["cwd"] = cwd
        payload.update(meta_extra or {})
        with open(p, "wb") as f:
            f.write(jl({"timestamp": "2026-11-03T23:00:01.000Z", "type": "session_meta", "payload": payload}) + more)
        return p

    def copy_of(self, tool, sid, rel):
        return os.path.join(self.ctx.live_dir, tool, sid, "files", *rel.split("/"))


def claude_records(cwd, extra=()):
    return jl(
        {"type": "session-owner", "accountUuid": FAKE_ACCOUNT, "organizationUuid": FAKE_ORG, "sessionId": "s"},
        {
            "type": "attachment",
            "attachment": {"type": "session_context", "context": {"userEmail": FAKE_EMAIL}},
            "cwd": cwd,
            "timestamp": "2026-11-03T23:28:25.195Z",
        },
        {
            "type": "user",
            "cwd": cwd,
            "timestamp": "2026-11-03T23:28:25.196Z",
            "message": {"role": "user", "content": "헤딩 프로젝트 첫 요청 C:\\Users\\" + FAKE_USER + "\\x"},
        },
        *extra,
    )


# =============================================================== policy tests
class PolicyTests(unittest.TestCase):
    def setUp(self):
        self.policy = Policy(base_entries(), repo_allowlist())

    def test_listed_values_masked_in_all_forms(self):
        nested = json.dumps(json.dumps({"email": FAKE_EMAIL, "path": "C:\\Users\\" + FAKE_USER + "\\a"}))
        lines = [
            json.dumps({"t": f"mail {FAKE_EMAIL} upper {FAKE_EMAIL.upper()} local {FAKE_LOCAL}"}),
            json.dumps({"t": nested}),
            json.dumps({"p": "C:\\Users\\" + FAKE_USER + "\\AppData", "q": "/c/Users/" + FAKE_USER + "/x"}),
            json.dumps({"p": "c:/users/" + FAKE_USER.upper() + "/y", "acct": FAKE_ACCOUNT.upper()}),
            json.dumps({"keep": FAKE_USER + "x", "alone": FAKE_USER}),
        ]
        data = ("\n".join(lines) + "\n").encode()
        masked, counts = self.policy.mask(data)
        self.assertEqual(self.policy.residue(masked), 0)
        self.assertNotIn(FAKE_EMAIL.encode(), masked)
        self.assertNotIn(FAKE_ACCOUNT.upper().encode(), masked)
        self.assertIn(b"Users\\\\****\\\\AppData", masked)  # JSON 안의 C:\Users\<이름>
        self.assertIn(b"/c/Users/****/x", masked)
        # 더 긴 단어의 일부는 건드리지 않고, 단독으로 쓰인 사용자 이름은 경로 밖에서도 가린다.
        self.assertIn(f'"keep": "{FAKE_USER}x"'.encode(), masked)
        self.assertIn(b'"alone": "****"', masked)
        for line in masked.splitlines():
            json.loads(line)  # JSON 유효성 유지
        self.assertGreater(counts["listed_value"], 0)

    def test_os_user_name_masked_as_word_everywhere(self):
        u = FAKE_USER
        texts = {
            "ls": f"drwxr-xr-x 1 {u} 197121 0 Nov  4 10:02 .claude",
            "whoami": f"desktop-abc\\{u}",
            "prompt": f"{u}@DESKTOP-ABC MINGW64 ~",
            "escaped_newline": f"line\n{u} 197121",
            "korean": f"{u.upper()}님 작업",
            "sentence": f"owner is {u.capitalize()}.",
        }
        keep = {"suffix": f"{u}_cfg", "prefix": f"x{u}", "longer": f"{u}2"}
        line = json.dumps({**texts, **keep, "signature": f"AbC/{u}+Zz"}, ensure_ascii=False).encode() + b"\n"
        masked, counts = self.policy.mask(line)
        obj = json.loads(masked)
        self.assertEqual(counts["home_user"], len(texts))
        for k in texts:
            self.assertNotIn(u, obj[k].lower(), k)
            self.assertIn("****", obj[k], k)
        self.assertEqual(obj["ls"], "drwxr-xr-x 1 **** 197121 0 Nov  4 10:02 .claude")
        for k, v in keep.items():
            self.assertEqual(obj[k], v, k)
        self.assertEqual(obj["signature"], f"AbC/{u}+Zz")  # 불투명 서명 필드는 그대로
        self.assertEqual(self.policy.residue(masked), 0)

    def test_only_masked_spans_change_and_crlf_kept(self):
        data = f"line one {FAKE_EMAIL}\r\nline two\r\n".encode()
        masked, _ = self.policy.mask(data)
        self.assertEqual(masked, data.replace(FAKE_EMAIL.encode(), b"****"))
        self.assertEqual(masked.count(b"\r\n"), 2)

    def test_tokens_masked_outside_struct_fields_only(self):
        line = json.dumps(
            {
                "text": f"key {FAKE_ANTHROPIC} gh {FAKE_GH}",
                "signature": FAKE_GH + "AAAA",
                "encrypted_content": "gAAAA" + FAKE_ANTHROPIC,
            }
        ).encode() + b"\n"
        masked, counts = self.policy.mask(line)
        obj = json.loads(masked)
        self.assertEqual(obj["text"], "key **** gh ****")
        self.assertEqual(obj["signature"], FAKE_GH + "AAAA")
        self.assertEqual(obj["encrypted_content"], "gAAAA" + FAKE_ANTHROPIC)
        self.assertEqual(counts["anthropic_key"] + counts["github_token"], 2)
        self.assertEqual(self.policy.detect(masked), [])

    def test_listed_value_wins_inside_struct_field(self):
        line = json.dumps({"signature": "abc" + FAKE_ACCOUNT}).encode() + b"\n"
        masked, _ = self.policy.mask(line)
        self.assertEqual(json.loads(masked)["signature"], "abc****")

    def test_false_positives_not_touched(self):
        data = json.dumps(
            {"t": "task-20261104 ask-for-permission-before-running-commands sk- desk-lamp-with-long-cable-name"}
        ).encode() + b"\n"
        masked, counts = self.policy.mask(data)
        self.assertEqual(masked, data)
        self.assertEqual(sum(counts.values()), 0)
        self.assertEqual(self.policy.detect(masked), [])

    def test_private_key_block_masked_and_bare_header_held(self):
        block = json.dumps({"t": f"{PEM_HEAD}\nMIIEabc123\n{PEM_TAIL}"}).encode() + b"\n"
        masked, counts = self.policy.mask(block)
        self.assertEqual(json.loads(masked)["t"], "****")
        self.assertEqual(counts["private_key"], 1)
        bare = json.dumps({"t": PEM_HEAD + "\nMIIE"}).encode() + b"\n"
        rules = {h.rule for h in self.policy.detect(self.policy.mask(bare)[0])}
        self.assertIn("private_key_header", rules)

    def test_detect_emails_with_allowlist(self):
        data = (
            f"a {OTHER_EMAIL}\nb user{AT}example.com\nc noreply{AT}anthropic.com\nd react{AT}18.3.1\n"
        ).encode()
        hits = self.policy.detect(data)
        self.assertEqual([(h.rule, h.line) for h in hits], [("email", 1)])

    def test_detect_keywords(self):
        secret_like = "S3cr" + "etVal9"
        data = (
            json.dumps({"code": f'password = "{secret_like}"'})
            + "\n"
            + json.dumps({"code": "password: ****"})
            + "\n"
        ).encode()
        hits = self.policy.detect(data)
        self.assertEqual([(h.rule, h.line) for h in hits], [("keyword:password", 1)])

    def test_detect_random_strings(self):
        uuid = "29f7d66a-3d57-4f2b-abfc-f8eb69f2ccc0"
        data = (
            json.dumps({"x": RANDOM_40}) + "\n"
            + json.dumps({"signature": RANDOM_40}) + "\n"
            + json.dumps({"h": "a" * 10 + sha256(b"x"), "u": uuid}) + "\n"
        ).encode()
        hits = self.policy.detect(data)
        self.assertEqual([(h.rule, h.line) for h in hits], [("random_string", 1)])
        self.assertIn("key=x", hits[0].detail)
        self.assertNotIn(RANDOM_40, hits[0].detail)
        allow = Allowlist({"random_strings": {"patterns": [{"pattern": "Qx7[A-Za-z0-9]+"}]}}, b"r")
        self.assertEqual(Policy(base_entries(), allow).detect(data), [])
        with self.assertRaises(ValueError):  # 키 이름만으로 면제하는 설정은 거부(회귀 f)
            Allowlist({"random_strings": {"json_keys": [{"key": "x"}]}}, b"k")

    def test_escaped_struct_field_inside_other_record(self):
        # Claude가 Bash로 바뀐 파일의 diff(bashEditDiff)에 Codex 줄을 문자열로 담는 경우
        inner = json.dumps({"payload": {"type": "reasoning", "encrypted_content": "gAAAA" + RANDOM_40}})
        outer = json.dumps({"toolUseResult": {"bashEditDiff": {"files": [{"hunks": [{"lines": ["+" + inner]}]}]}}})
        data = outer.encode() + b"\n"
        self.assertIn(b'\\"encrypted_content\\"', data)
        self.assertEqual(self.policy.detect(data), [])
        self.assertEqual(self.policy.mask(data)[0], data)

    def test_email_after_json_escape(self):
        data = (
            json.dumps({"code": "x = 1\n@contextlib.contextmanager\ndef f(): pass"})
            + "\n"
            + json.dumps({"t": "line\n" + OTHER_EMAIL})
            + "\n"
        ).encode()
        hits = self.policy.detect(data)
        self.assertEqual([(h.rule, h.line) for h in hits], [("email", 2)])
        self.assertEqual(hits[0].value, OTHER_EMAIL.encode())  # JSON 이스케이프 글자(\n의 n)는 빼고 값만

    def test_detect_flags_env_names_and_prose(self):
        pw = "Pa55" + "word-x9"
        data = (
            json.dumps({"cmd": f"mysql --password {pw} -h db"}) + "\n"
            + json.dumps({"cmd": ["mysql", "--password", pw]}) + "\n"
            + json.dumps({"cmd": f"$env:APP_DB_PASS='{pw}'; run"}) + "\n"
            + json.dumps({"t": "CLI `--password value` 플래그, `token=` 키워드, 비밀번호=한글문장"}) + "\n"
            + json.dumps({"t": f"API_TOKEN={pw}"}) + "\n"
        ).encode()
        hits = self.policy.detect(data)
        self.assertEqual(
            [(h.rule, h.line) for h in hits],
            [("keyword:password", 1), ("keyword:password", 2), ("keyword:pass", 3), ("keyword:token", 5)],
        )
        self.assertTrue(all(h.value == pw.encode() for h in hits))
        self.assertTrue(all(pw not in h.detail for h in hits))

    def test_env_assignment_random_value_only(self):
        data = (json.dumps({"cmd": "--env SOME_SETTING_NAME=" + RANDOM_40}) + "\n").encode()
        hits = [h for h in self.policy.detect(data) if h.rule == "random_string"]
        self.assertEqual(len(hits), 1)
        self.assertEqual(hits[0].value, RANDOM_40.encode())  # 변수 이름은 치환 대상이 아님

    def test_policy_version_follows_rules(self):
        allow = repo_allowlist()
        self.assertEqual(Policy(base_entries(), allow).version, self.policy.version)
        self.assertEqual(Policy(list(reversed(base_entries())), allow).version, self.policy.version)  # 순서 무관
        self.assertNotEqual(Policy([], allow).version, self.policy.version)  # 치환 목록 변경 반영
        crlf = json.loads(rb(os.path.join(REPO, "config", "ai-logs-allowlist.json")).decode("utf-8"))
        same = Allowlist(crlf, b"different raw bytes\r\n")  # 개행·공백만 다른 파일은 같은 정책
        self.assertEqual(Policy(base_entries(), same).version, self.policy.version)
        other = Allowlist(dict(crlf, emails={"exact": ["x" + AT + "example.com"]}))
        self.assertNotEqual(Policy([], other).version, self.policy.version)


# =============================================================== engine tests
class EngineTests(unittest.TestCase):
    def setUp(self):
        self.env = Env()
        self.addCleanup(self.env.cleanup)
        self.ctx = self.env.ctx

    def sync(self):
        return engine.sync(self.ctx)

    def test_sync_copy_is_masked_original_and_verify_passes(self):
        e = self.env
        e.write_mask()
        orig = claude_records(e.root)
        e.claude_session("proj-root", "sess-a", orig)
        rep = self.sync()
        self.assertEqual(rep["result"], "ok", rep)
        with open(e.copy_of("claude", "sess-a", "sess-a.jsonl"), "rb") as f:
            copy = f.read()
        self.assertEqual(copy, Policy(base_entries(), repo_allowlist()).mask(orig)[0])
        self.assertNotIn(FAKE_EMAIL.encode(), copy)
        self.assertNotIn(FAKE_ACCOUNT.encode(), copy)
        self.assertIn("헤딩 프로젝트 첫 요청".encode(), copy)
        m = json.loads(rt(os.path.join(self.ctx.live_dir, "claude", "sess-a", "manifest.json")))
        self.assertEqual(m["main_lines"], 3)
        self.assertEqual(m["first_timestamp"], "2026-11-03T23:28:25.195Z")
        self.assertEqual(m["files"][0]["original_sha256"], sha256(orig))
        self.assertTrue(engine.verify(self.ctx, final=True)["ok"])

    def test_other_project_in_same_folder_excluded(self):
        e = self.env
        e.write_mask()
        e.claude_session("shared-slug", "mine", claude_records(e.root))
        e.claude_session("shared-slug", "theirs", claude_records(os.path.join(e.tmp, "proj-root")))
        self.sync()
        self.assertTrue(os.path.exists(e.copy_of("claude", "mine", "mine.jsonl")))
        self.assertFalse(os.path.exists(os.path.join(self.ctx.live_dir, "claude", "theirs")))

    def test_subfolder_cwd_and_attachments_included(self):
        e = self.env
        e.write_mask()
        att = {
            "subagents/agent-1.jsonl": jl({"type": "assistant", "t": "sub"}),
            "tool-results/out.txt": "large tool output\n".encode(),
        }
        e.claude_session("proj-root-sub", "sess-b", claude_records(os.path.join(e.root, "sub")), att)
        rep = self.sync()
        self.assertEqual(rep["result"], "ok", rep)
        for rel in ("sess-b/subagents/agent-1.jsonl", "sess-b/tool-results/out.txt"):
            self.assertTrue(os.path.exists(e.copy_of("claude", "sess-b", rel)), rel)
        self.assertTrue(engine.verify(self.ctx, final=True)["ok"])

    def test_codex_sessions_archived_and_child_rollouts(self):
        e = self.env
        e.write_mask()
        parent = "11111111-1111-1111-1111-111111111111"
        e.codex_session("sessions", parent, e.root, jl({"timestamp": "2026-11-03T23:18:54Z", "type": "x"}))
        e.codex_session("archived_sessions", "22222222-2222-2222-2222-222222222222", e.root)
        e.codex_session("sessions", "33333333-3333-3333-3333-333333333333", os.path.join(e.tmp, "elsewhere"))
        e.codex_session("sessions", "44444444-4444-4444-4444-444444444444", None, meta_extra={"source": {"subagent": {"parent_thread_id": parent}}})
        rep = self.sync()
        self.assertEqual(rep["result"], "ok", rep)
        got = sorted(s["session_id"] for s in rep["sessions"])
        self.assertEqual(got, [parent, "22222222-2222-2222-2222-222222222222", "44444444-4444-4444-4444-444444444444"])
        with open(e.copy_of("codex", parent, f"rollout-2026-11-04T08-00-00-{parent}.jsonl"), "rb") as f:
            self.assertNotIn(FAKE_ACCOUNT.encode(), f.read())

    def test_incomplete_last_line_waits(self):
        e = self.env
        e.write_mask()
        orig = claude_records(e.root)
        p = e.claude_session("proj-root", "sess-c", orig + b'{"type":"assistant","partial')
        self.sync()
        with open(e.copy_of("claude", "sess-c", "sess-c.jsonl"), "rb") as f:
            self.assertEqual(f.read().count(b"\n"), 3)
        with open(p, "ab") as f:
            f.write(b'":1}\n')
        self.sync()
        with open(e.copy_of("claude", "sess-c", "sess-c.jsonl"), "rb") as f:
            self.assertEqual(f.read().count(b"\n"), 4)
        self.assertTrue(engine.verify(self.ctx, final=True)["ok"])

    def test_hold_keeps_previous_copy_and_status_has_no_value(self):
        e = self.env
        e.write_mask()
        p = e.claude_session("proj-root", "sess-d", claude_records(e.root))
        self.sync()
        cpath = e.copy_of("claude", "sess-d", "sess-d.jsonl")
        before = rb(cpath)
        with open(p, "ab") as f:
            f.write(jl({"type": "user", "message": f"contact {OTHER_EMAIL}"}))
        rep = self.sync()
        self.assertEqual(rep["result"], "hold")
        self.assertEqual(rb(cpath), before)
        self.assertEqual(rep["holds"][0]["rule"], "email")
        self.assertEqual(rep["holds"][0]["line"], 4)
        status_text = rt(self.ctx.last_sync_path)
        self.assertNotIn(OTHER_EMAIL, status_text)
        # 사용자가 치환 목록에 추가하면 다음 수집에서 반영된다.
        e.write_mask(base_entries() + [{"kind": "literal", "value": OTHER_EMAIL}])
        self.assertEqual(self.sync()["result"], "ok")
        self.assertNotIn(OTHER_EMAIL.encode(), rb(cpath))

    def test_holds_and_mask_held_never_print_values(self):
        e = self.env
        e.write_mask()
        p = e.claude_session("proj-root", "sess-h", claude_records(e.root))
        with open(p, "ab") as f:
            f.write(jl({"type": "user", "message": f"contact {OTHER_EMAIL}"}))
        self.assertEqual(self.sync()["result"], "hold")
        buf = io.StringIO()
        with mock.patch.object(cli, "default_context", lambda: self.ctx):
            with redirect_stdout(buf):
                self.assertEqual(cli.main(["holds"]), 0)
            out = buf.getvalue()
            self.assertNotIn(OTHER_EMAIL, out)
            self.assertIn("#1 [email]", out)
            list_id, items = engine.held_candidates(self.ctx)
            self.assertIn(list_id, out)
            with redirect_stdout(io.StringIO()):
                self.assertEqual(cli.main(["mask-held", "00000000", "1"]), 1)  # 목록이 바뀌었으면 거부
            buf = io.StringIO()
            with redirect_stdout(buf):
                self.assertEqual(cli.main(["mask-held", list_id, "1"]), 0)
            self.assertNotIn(OTHER_EMAIL, buf.getvalue())
        entries = [json.loads(x) for x in rt(self.ctx.mask_path).splitlines()]
        self.assertEqual(entries[-1], {"kind": "literal", "value": OTHER_EMAIL, "source": "review.email"})
        self.assertNotIn(OTHER_EMAIL.encode(), rb(e.copy_of("claude", "sess-h", "sess-h.jsonl")))
        self.assertEqual(engine.held_candidates(self.ctx)[1], [])

    def test_rewrite_and_truncation_detected(self):
        e = self.env
        e.write_mask()
        orig = claude_records(e.root)
        p = e.claude_session("proj-root", "sess-e", orig)
        self.sync()
        cpath = e.copy_of("claude", "sess-e", "sess-e.jsonl")
        before = rb(cpath)
        with open(p, "wb") as f:
            f.write(orig.replace(b"session-owner", b"session-0wner"))
        rep = self.sync()
        self.assertEqual([h["rule"] for h in rep["holds"]], ["original_rewritten"])
        self.assertFalse(engine.verify(self.ctx)["ok"])
        with open(p, "wb") as f:
            f.write(orig[:50])
        rep = self.sync()
        self.assertEqual([h["rule"] for h in rep["holds"]], ["original_truncated"])
        self.assertEqual(rb(cpath), before)

    def test_no_mask_list_writes_nothing(self):
        e = self.env
        e.claude_session("proj-root", "sess-f", claude_records(e.root))
        rep = self.sync()
        self.assertEqual(rep["result"], "error")
        self.assertFalse(os.path.exists(os.path.join(self.ctx.live_dir, "claude")))

    def test_idempotent(self):
        e = self.env
        e.write_mask()
        e.claude_session("proj-root", "sess-g", claude_records(e.root))
        self.sync()
        mpath = os.path.join(self.ctx.live_dir, "claude", "sess-g", "manifest.json")
        first = rb(mpath)
        rep = self.sync()
        self.assertEqual(rep["sessions"][0]["status"], "unchanged")
        self.assertEqual(rb(mpath), first)

    def test_verify_detects_tamper_and_lag(self):
        e = self.env
        e.write_mask()
        p = e.claude_session("proj-root", "sess-h", claude_records(e.root))
        self.sync()
        with open(p, "ab") as f:
            f.write(jl({"type": "assistant", "t": "later"}))
        v = engine.verify(self.ctx, final=True)
        self.assertEqual([x["rule"] for x in v["failures"]], ["behind_original"])
        self.assertTrue(engine.verify(self.ctx, final=False)["ok"])
        cpath = e.copy_of("claude", "sess-h", "sess-h.jsonl")
        with open(cpath, "ab") as f:
            f.write(b"x")
        self.assertIn("copy_hash_mismatch", [x["rule"] for x in engine.verify(self.ctx)["failures"]])

    def test_missing_original_keeps_copy(self):
        e = self.env
        e.write_mask()
        p = e.claude_session("proj-root", "sess-i", claude_records(e.root))
        self.sync()
        os.remove(p)
        rep = self.sync()
        self.assertIn({"key": "claude/sess-i", "status": "original_missing"}, rep["sessions"])
        self.assertTrue(os.path.exists(e.copy_of("claude", "sess-i", "sess-i.jsonl")))

    def test_hook_always_exit_zero_and_output_contract(self):
        e = self.env
        e.write_mask()
        e.claude_session("proj-root", "sess-j", claude_records(e.root))
        stdin = io.TextIOWrapper(io.BytesIO(b"not json"))
        out = io.StringIO()
        with mock.patch.object(sys, "stdin", stdin), redirect_stdout(out):
            self.assertEqual(engine.hook(self.ctx, "claude", "Stop"), 0)
        self.assertEqual(out.getvalue(), "")
        self.assertTrue(os.path.exists(e.copy_of("claude", "sess-j", "sess-j.jsonl")))
        payload = json.dumps({"session_id": "x", "cwd": e.root, "turn_id": "t1"}).encode()
        out = io.StringIO()
        with mock.patch.object(sys, "stdin", io.TextIOWrapper(io.BytesIO(payload))), redirect_stdout(out):
            self.assertEqual(engine.hook(self.ctx, "codex", "Stop"), 0)
        self.assertEqual(json.loads(out.getvalue()), {})
        secret_msg = "boom-" + FAKE_LOCAL
        out = io.StringIO()
        with mock.patch.object(engine, "sync", side_effect=ValueError(secret_msg)), mock.patch.object(
            sys, "stdin", io.TextIOWrapper(io.BytesIO(b"{}"))
        ), redirect_stdout(out):
            self.assertEqual(engine.hook(self.ctx, "claude", "SessionEnd"), 0)
        errors = rt(self.ctx.errors_path)
        self.assertIn("ValueError", errors)
        self.assertNotIn(secret_msg, errors)
        reg = [json.loads(x) for x in rt(self.ctx.registry_path).splitlines()]
        self.assertEqual(reg[0]["input"], "invalid_json")
        self.assertTrue(reg[1]["in_project"])

    def test_install_extracts_values_without_printing(self):
        e = self.env
        e.claude_session("proj-root", "sess-k", claude_records(e.root))
        e.codex_session("sessions", "55555555-5555-5555-5555-555555555555", e.root)
        out = io.StringIO()
        with mock.patch.dict(
            os.environ,
            {"AI_LOGGING_ROOT": e.root, "AI_LOGGING_CLAUDE_HOME": e.claude, "AI_LOGGING_CODEX_HOME": e.codex},
        ), redirect_stdout(out):
            code = cli.main(["install"])
        printed = out.getvalue()
        for v in (FAKE_EMAIL, FAKE_LOCAL, FAKE_ACCOUNT, FAKE_ORG):
            self.assertNotIn(v, printed)
        values = {json.loads(x)["value"] for x in rt(self.ctx.mask_path).splitlines()}
        self.assertTrue({FAKE_EMAIL, FAKE_LOCAL, FAKE_ACCOUNT, FAKE_ORG} <= values)
        self.assertEqual(code, 0, printed)


class SubmitTests(unittest.TestCase):
    def setUp(self):
        self.env = Env(git_repo=True)
        self.addCleanup(self.env.cleanup)

    def test_submit_commits_only_logs_with_message_format(self):
        e = self.env
        e.write_mask()
        e.claude_session("proj-root", "sess-s", claude_records(e.root))
        engine.install(e.ctx)
        self.assertNotIn("ai-logs", e.git("status", "--porcelain"))  # 로컬 제외로 작업 중엔 보이지 않음
        with open(os.path.join(e.root, "work.txt"), "w") as f:
            f.write("unrelated\n")
        r = engine.submit(e.ctx, task="AI-LOG-002")
        self.assertTrue(r["ok"] and r["committed"], r)
        files = e.git("show", "--name-only", "--format=", "HEAD").split()
        self.assertTrue(files and all(x.startswith("ai-logs/snapshots/claude/sess-s/") for x in files), files)
        msg = e.git("log", "-1", "--format=%B")
        self.assertTrue(msg.startswith("[AI-LOG-002] 세션 로그 제출\n\n  "), msg)
        self.assertIn("work.txt", e.git("status", "--porcelain"))  # 다른 변경은 건드리지 않음
        self.assertFalse(engine.submit(e.ctx, task="bad id")["ok"])
        self.assertEqual(engine.submit(e.ctx, task="AI-LOG-002")["reason"], "nothing_to_commit")
        self.assertEqual(engine.submit(e.ctx)["reason"], "task_id_required")  # feature 브랜치가 아님

    def test_submit_refuses_when_other_changes_staged(self):
        e = self.env
        e.write_mask()
        e.claude_session("proj-root", "sess-t", claude_records(e.root))
        with open(os.path.join(e.root, "staged.txt"), "w") as f:
            f.write("s\n")
        e.git("add", "staged.txt")
        self.assertEqual(engine.submit(e.ctx, task="AI-LOG-002")["reason"], "staged_changes_present")
        self.assertIn("staged.txt", e.git("diff", "--cached", "--name-only"))  # 되돌리지 않음


if __name__ == "__main__":
    unittest.main()
