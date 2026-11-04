"""3단계 통합 테스트: 임시 저장소 + 임시 원격(bare)에서 전체 브랜치 흐름.

master → feature → develop(검토 실패) → 로그 게시 → resume → 보완 → develop → 로그 게시 → 최종 병합 → master push
실패 주입: 진행 중인 턴, 작업 폴더 변경, 원격 거부(atomic), master의 로그 밖 변경, 병합 안 된 다른 feature,
로깅 설정 없는 master, 이미 커밋된 비밀값.

실제 Claude/Codex·GitHub 원격 대신 가짜 원본과 로컬 bare 저장소를 쓴다(README '검증 기록'에서 구분).
"""
import json
import os
import shutil
import subprocess
import unittest
from unittest import mock

from test_ai_logging import OTHER_EMAIL, REPO, Env, claude_records, jl  # noqa: E402

from ailog import config, engine, flows, githooks, gitops, snapshot, tasks, turns  # noqa: E402

PROMPT_N = [0]


def prompt(text):
    PROMPT_N[0] += 1
    n = PROMPT_N[0]
    return {"type": "user", "promptSource": "typed", "turnPosition": {"promptIndex": n, "turnIndex": n},
            "timestamp": "2026-11-04T02:%02d:00.000Z" % (n % 60), "message": {"role": "user", "content": text}}


def answer(text):
    return {"type": "assistant", "timestamp": "2026-11-04T02:00:30.000Z",
            "message": {"role": "assistant", "content": [{"type": "text", "text": text}]}}


END = {"type": "system", "subtype": "turn_duration", "durationMs": 5}
MSG = "[{task}] {title}\n\n  {body}\n"


class FlowEnv(Env):
    def __init__(self, with_config=True, config_data=None):
        super().__init__(git_repo=False)
        r = self.root
        self.remote = os.path.join(self.tmp, "remote.git")
        subprocess.run(["git", "init", "-q", "--bare", self.remote], check=True)
        self.git("init", "-q", "-b", "master")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test" + "@" + "example.com")
        self.git("config", "core.autocrlf", "true")  # 실제 저장소와 같은 조건
        shutil.copy(os.path.join(REPO, ".gitattributes"), r)
        with open(os.path.join(r, ".gitignore"), "w") as f:
            f.write(".ai-logging/\n")
        with open(os.path.join(r, "README.md"), "w") as f:
            f.write("app\n")
        if with_config:
            with open(os.path.join(r, "config", "ai-logging.json"), "w") as f:
                json.dump(config_data or {"service": "AI", "main": "master", "develop": "develop", "remote": "origin"}, f)
            self.git("add", "config")
        else:
            shutil.rmtree(os.path.join(r, "config"))
        self.git("add", ".gitattributes", ".gitignore", "README.md")
        self.commit_msg("AI-BASE-000", "초기 구성", "저장소 시작")
        self.git("branch", "develop")
        self.git("remote", "add", "origin", self.remote)
        self.git("push", "-q", "origin", "master", "develop")
        if not with_config:
            os.makedirs(os.path.join(r, "config"))
            shutil.copy(os.path.join(REPO, "config", "ai-logs-allowlist.json"), os.path.join(r, "config"))
        self.write_mask()
        engine.ensure_local_exclude(self.ctx)
        self.hooks = githooks.install_runtime(self.ctx)
        self.session = self.claude_session("proj", "flow-sess", claude_records(r) + jl(prompt("시작"), answer("네"), END))

    def commit_msg(self, task, title, body, *, allow_fail=False):
        msg = MSG.format(task=task, title=title, body=body)
        r = subprocess.run(["git", "-C", self.root, "commit", "-q", "-F", "-"], input=msg.encode(), capture_output=True)
        if not allow_fail and r.returncode != 0:
            raise AssertionError(r.stderr.decode("utf-8", "replace"))
        return r

    def talk(self, *pairs, open_turn=False):
        recs = []
        for q, a in pairs:
            recs += [prompt(q), answer(a), END]
        if open_turn:
            recs.append(prompt("진행 중"))
        with open(self.session, "ab") as f:
            f.write(jl(*recs))

    def close_turn(self):
        with open(self.session, "ab") as f:
            f.write(jl(answer("끝"), END))

    def edit(self, rel, text):
        p = os.path.join(self.root, *rel.split("/"))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as f:
            f.write(text)
        self.git("add", rel)

    def rev(self, ref):
        return gitops.rev(self.root, ref)

    def remote_rev(self, ref):
        out = subprocess.run(["git", "-C", self.remote, "rev-parse", ref], capture_output=True).stdout.decode().strip()
        return out or None

    def merge_into_develop(self, feature, task):
        cur = self.git("symbolic-ref", "--short", "HEAD").strip()
        self.git("switch", "-q", "develop")
        msg = MSG.format(task=task, title=f"{feature} develop 병합", body="검토용 병합")
        path = os.path.join(self.tmp, "merge-msg.txt")
        with open(path, "wb") as f:
            f.write(msg.encode("utf-8"))
        r = subprocess.run(["git", "-C", self.root, "merge", "--no-ff", "-q", "-F", path, feature], capture_output=True)
        assert r.returncode == 0, r.stderr.decode("utf-8", "replace")
        self.git("switch", "-q", cur)


class FlowTests(unittest.TestCase):
    def setUp(self):
        self.env = FlowEnv()
        self.addCleanup(self.env.cleanup)
        env = {"AI_LOGGING_CLAUDE_HOME": self.env.claude, "AI_LOGGING_CODEX_HOME": self.env.codex}
        p = mock.patch.dict(os.environ, env)
        p.start()
        self.addCleanup(p.stop)
        self.ctx = self.env.ctx

    def nonlog(self, ref):
        return {k: v for k, v in gitops.tree_files(self.env.root, ref).items() if not k.startswith("ai-logs/")}

    def test_full_branch_flow(self):
        e, ctx = self.env, self.ctx
        self.assertTrue(e.hooks["ok"], e.hooks)
        # 1) 신규 feature (master에서 생성)
        r = flows.new_feature(ctx, "LOG-002")
        self.assertTrue(r["ok"], r)
        self.assertEqual(gitops.current_branch(e.root), "feature/LOG-002")
        e.edit("src/app.py", "print('v1')\n")
        e.commit_msg("AI-LOG-002", "기능 구현", "app v1")
        self.assertEqual(flows.new_feature(ctx, "LOG-003")["reason"], "unmerged_feature_exists")
        # 2) develop 병합 → 검토(실패) 대화 → develop push + 로그 동기화
        e.merge_into_develop("feature/LOG-002", "AI-LOG-002")
        e.talk(("develop 검토해줘", "버그 발견: 검증 실패"))
        master0 = e.rev("master")
        r = flows.push_develop(ctx, "LOG-002")
        self.assertTrue(r["ok"], r)
        self.assertTrue(r["pushed"])
        self.assertEqual(r["picked"], 1)
        self.assertEqual(e.remote_rev("refs/heads/master"), e.rev("master"))
        self.assertEqual(e.remote_rev("refs/heads/develop"), e.rev("develop"))
        self.assertEqual(self.nonlog("master"), self.nonlog(master0))  # master에는 로그만
        self.assertNotIn("src/app.py", gitops.tree_files(e.root, "master"))
        msg = e.git("log", "-1", "--format=%B", "master")
        self.assertIn("(cherry picked from commit ", msg)
        self.assertEqual(len(gitops.parents(e.root, e.rev("master"))), 1)
        self.assertTrue(snapshot.completeness(ctx, "master")["complete"])
        self.assertEqual(gitops.current_branch(e.root), "feature/LOG-002")  # 원래 브랜치 유지
        # 3) 보완 시작: 최신 master를 feature에 일반 병합
        r = flows.resume(ctx, "LOG-002")
        self.assertTrue(r["ok"] and r["merged"], r)
        self.assertTrue(gitops.is_ancestor(e.root, e.rev("master"), e.rev("feature/LOG-002")))
        self.assertEqual(len(gitops.parents(e.root, e.rev("feature/LOG-002"))), 2)
        # 4) 보완 → develop 재병합 → 재검토 대화 → push (이미 반영된 스냅샷은 다시 고르지 않음)
        e.edit("src/app.py", "print('v2')\n")
        e.commit_msg("AI-LOG-002", "버그 수정", "app v2")
        e.merge_into_develop("feature/LOG-002", "AI-LOG-002")
        e.talk(("재검토", "통과"))
        r = flows.push_develop(ctx, "LOG-002")
        self.assertTrue(r["ok"], r)
        self.assertEqual(r["picked"], 1)
        r = flows.push_develop(ctx, "LOG-002")  # 같은 명령 재실행: 새 로그·커밋 없음
        self.assertTrue(r["ok"], r)
        self.assertEqual(r["picked"], 0)
        self.assertFalse(r["develop_log_commit"])
        # 5) 최종 병합 (push 안 함) → 사용자가 master push (pre-push 검사 통과)
        before_remote = e.remote_rev("refs/heads/master")
        r = flows.merge_feature(ctx, "LOG-002")
        self.assertTrue(r["ok"] and r["merged"], r)
        m = e.rev("master")
        self.assertEqual(len(gitops.parents(e.root, m)), 2)
        self.assertEqual(self.nonlog(m), self.nonlog("feature/LOG-002"))
        self.assertEqual(e.remote_rev("refs/heads/master"), before_remote)  # push하지 않음
        self.assertTrue(e.git("log", "-1", "--format=%s", m).startswith("[AI-LOG-002] feature/LOG-002 master 병합"))
        p = subprocess.run(["git", "-C", e.root, "push", "-q", "origin", "master"], capture_output=True)
        self.assertEqual(p.returncode, 0, p.stderr.decode("utf-8", "replace"))
        self.assertEqual(e.remote_rev("refs/heads/master"), m)
        r = flows.merge_feature(ctx, "LOG-002")  # 재실행: 이미 병합됨
        self.assertTrue(r["ok"] and not r["merged"], r)
        # 이후 새 feature 생성 가능(병합 완료)
        self.assertTrue(flows.new_feature(ctx, "LOG-003")["ok"])

    def test_refusals_leave_refs_untouched(self):
        e, ctx = self.env, self.ctx
        flows.new_feature(ctx, "LOG-002")
        e.edit("src/app.py", "x\n")
        e.commit_msg("AI-LOG-002", "기능", "x")
        e.merge_into_develop("feature/LOG-002", "AI-LOG-002")
        refs = e.git("for-each-ref")
        # 진행 중인 턴
        e.talk(open_turn=True)
        self.assertEqual(flows.push_develop(ctx, "LOG-002")["reason"], "ai_turn_not_idle")
        self.assertEqual(flows.merge_feature(ctx, "LOG-002")["reason"], "ai_turn_not_idle")
        e.close_turn()
        # 작업 폴더 변경
        with open(os.path.join(e.root, "README.md"), "a") as f:
            f.write("dirty\n")
        self.assertEqual(flows.push_develop(ctx, "LOG-002")["reason"], "worktree_dirty")
        e.git("checkout", "--", "README.md")  # 테스트 준비 단계(사용자 역할)
        self.assertEqual(e.git("for-each-ref"), refs)
        # develop 검증 전 최종 병합 거부: feature에 develop에 없는 새 코드
        e.edit("src/app.py", "y\n")
        e.commit_msg("AI-LOG-002", "추가 수정", "y")
        self.assertEqual(flows.merge_feature(ctx, "LOG-002")["reason"], "feature_code_not_in_develop")

    def test_atomic_push_rejection_then_resume(self):
        e, ctx = self.env, self.ctx
        flows.new_feature(ctx, "LOG-002")
        e.edit("src/app.py", "x\n")
        e.commit_msg("AI-LOG-002", "기능", "x")
        e.merge_into_develop("feature/LOG-002", "AI-LOG-002")
        hook = os.path.join(e.remote, "hooks", "pre-receive")
        with open(hook, "w", newline="\n") as f:
            f.write("#!/bin/sh\nwhile read o n r; do [ \"$r\" = refs/heads/develop ] && exit 1; done\nexit 0\n")
        os.chmod(hook, 0o755)
        rm0, rd0 = e.remote_rev("refs/heads/master"), e.remote_rev("refs/heads/develop")
        r = flows.push_develop(ctx, "LOG-002")
        self.assertEqual(r["reason"], "push_rejected", r)
        self.assertEqual((e.remote_rev("refs/heads/master"), e.remote_rev("refs/heads/develop")), (rm0, rd0))  # atomic
        os.remove(hook)
        r = flows.push_develop(ctx, "LOG-002")  # 이어서 진행
        self.assertTrue(r["ok"] and r["pushed"], r)
        self.assertEqual(r["picked"], 0)  # 앞 실행에서 이미 master에 반영(중복 커밋 없음)
        self.assertEqual(e.remote_rev("refs/heads/master"), e.rev("master"))

    def test_resume_refuses_non_log_master_change(self):
        e, ctx = self.env, self.ctx
        flows.new_feature(ctx, "LOG-002")
        clone = os.path.join(e.tmp, "other")
        subprocess.run(["git", "clone", "-q", e.remote, clone], check=True)
        with open(os.path.join(clone, "hotfix.txt"), "w") as f:
            f.write("h\n")
        subprocess.run(["git", "-C", clone, "add", "hotfix.txt"], check=True)
        subprocess.run(["git", "-C", clone, "-c", "user.name=O", "-c", "user.email=o@example.com", "commit", "-q",
                        "-m", "[AI-HOT-001] hotfix\n\n  x"], check=True)
        subprocess.run(["git", "-C", clone, "push", "-q", "origin", "master"], check=True)
        r = flows.resume(ctx, "LOG-002")
        self.assertEqual(r["reason"], "master_has_non_log_changes")

    def test_committed_secret_stops_publish(self):
        e, ctx = self.env, self.ctx
        flows.new_feature(ctx, "LOG-002")
        e.edit("src/app.py", "x\n")
        e.commit_msg("AI-LOG-002", "기능", "x")
        e.merge_into_develop("feature/LOG-002", "AI-LOG-002")
        e.talk(("내부 코드명 phoenix-kappa 알려줘", "네"))
        self.assertTrue(flows.push_develop(ctx, "LOG-002", push=False)["ok"])
        with open(ctx.mask_path, "a", encoding="utf-8") as f:
            f.write(json.dumps({"kind": "literal", "value": "phoenix-kappa"}) + "\n")
        r = flows.push_develop(ctx, "LOG-002")
        self.assertEqual(r["reason"], "committed_secret_found", r)
        self.assertNotIn("phoenix-kappa", json.dumps(r, ensure_ascii=False))
        self.assertNotEqual(e.remote_rev("refs/heads/master"), e.rev("master"))  # 게시하지 않음


class BootstrapTests(unittest.TestCase):
    """master에 로깅 설정이 없는 최초 도입 상태."""

    def setUp(self):
        self.env = FlowEnv(with_config=False)
        self.addCleanup(self.env.cleanup)
        p = mock.patch.dict(os.environ, {"AI_LOGGING_CLAUDE_HOME": self.env.claude,
                                         "AI_LOGGING_CODEX_HOME": self.env.codex})
        p.start()
        self.addCleanup(p.stop)

    def test_bootstrap_only_via_merge_feature(self):
        e, ctx = self.env, self.env.ctx
        e.git("switch", "-q", "-c", "feature/LOG-001")
        e.edit("config/ai-logging.json", json.dumps({"main": "master"}))
        e.git("add", "config/ai-logs-allowlist.json")  # FlowEnv가 작업 폴더에 복사해 둔 허용 목록
        e.commit_msg("AI-LOG-001", "로깅 도입", "설정")
        self.assertEqual(flows.new_feature(ctx, "LOG-002")["reason"], "master_without_logging_config")
        e.merge_into_develop("feature/LOG-001", "AI-LOG-001")
        self.assertEqual(flows.push_develop(ctx, "LOG-001")["reason"], "master_without_logging_config")
        r = flows.merge_feature(ctx, "LOG-001")
        self.assertTrue(r["ok"] and r["bootstrap"], r)
        self.assertEqual(r["develop_check"], "included")
        p = subprocess.run(["git", "-C", e.root, "push", "-q", "origin", "master"], capture_output=True)
        self.assertEqual(p.returncode, 0, p.stderr.decode("utf-8", "replace"))
        e.git("switch", "-q", "master")  # 수집기·설정이 없던 브랜치로 바꿔도 hook 유지
        r = subprocess.run(["git", "-C", e.root, "commit", "--allow-empty", "-q", "-m", "bad"], capture_output=True)
        self.assertNotEqual(r.returncode, 0)

    def _bootstrap_feature(self):
        e, ctx = self.env, self.env.ctx
        e.git("switch", "-q", "-c", "feature/LOG-001")
        e.edit("config/ai-logging.json", json.dumps({"main": "master"}))
        e.git("add", "config/ai-logs-allowlist.json")
        e.commit_msg("AI-LOG-001", "로깅 도입", "설정")
        e.talk(("구성 완료", "네"))
        snap = snapshot.commit_snapshots(ctx, "AI-LOG-001", "세션 로그 스냅샷 (구성 완료)",
                                         states=turns.effective_states(ctx))
        self.assertTrue(snap["ok"] and snap["committed"], snap)
        return snap["commit"]

    def push_master(self):
        p = subprocess.run(["git", "-C", self.env.root, "push", "-q", "origin", "master"], capture_output=True)
        return p.returncode, p.stderr.decode("utf-8")  # 한글 안내가 UTF-8이어야 IDE에서 깨지지 않는다

    def test_bootstrap_merge_feature_without_develop(self):
        """최초 도입은 develop 병합 없이 merge-feature로 병합되고 생략 사실을 남긴다."""
        e, ctx = self.env, self.env.ctx
        self._bootstrap_feature()
        r = flows.merge_feature(ctx, "LOG-001")
        self.assertTrue(r["ok"] and r["merged"] and r["bootstrap"], r)
        self.assertEqual(r["develop_check"], "skipped_bootstrap")
        self.assertEqual(r["warnings"], [])
        m = e.rev("master")
        self.assertEqual(len(gitops.parents(e.root, m)), 2)
        self.assertIn("develop 검증 생략(최초 도입: master에 검증 절차 없음)", e.git("log", "-1", "--format=%B", m))
        rc, err = self.push_master()
        self.assertEqual(rc, 0, err)
        self.assertEqual(err, "")  # 경고 없음
        self.assertEqual(e.remote_rev("refs/heads/master"), m)

    def test_push_never_blocked_by_log_lag_and_logs_accumulate(self):
        """IDE fast-forward·스냅샷 뒤 대화가 있어도 push는 진행(경고), 다음 push에 더 긴 스냅샷이 이어서 쌓인다."""
        e, ctx = self.env, self.env.ctx
        first = self._bootstrap_feature()
        e.talk(("스냅샷 뒤 대화", "네"))  # 마지막 스냅샷 이후 대화
        # 사용자가 IDE에서 master로 바꿔 feature를 병합(fast-forward)하고 push
        e.git("switch", "-q", "master")
        e.git("merge", "-q", "--ff-only", "feature/LOG-001")
        rc, err = self.push_master()
        self.assertEqual(rc, 0, err)
        self.assertIn("push 진행(참고)", err)
        self.assertIn("master_direct_source_commit", err)
        self.assertIn("logs:snapshot_behind_original", err)
        self.assertEqual(e.remote_rev("refs/heads/master"), e.rev("master"))
        # 다음 스냅샷: 같은 세션의 더 긴 전체본이 추가된다(기존 스냅샷은 그대로)
        e.git("switch", "-q", "feature/LOG-001")
        old = set(snapshot.tree_manifests(e.root, first))
        r = snapshot.submit_snapshots(ctx, task="AI-LOG-001", title="세션 로그 스냅샷 (이어서)")
        self.assertTrue(r["ok"] and r["committed"], r)
        new = set(snapshot.tree_manifests(e.root, r["commit"]))
        self.assertTrue(old < new)
        self.assertTrue(snapshot.completeness(ctx, r["commit"])["complete"])
        # git 명령으로 직접 --no-ff 병합 → push: 경고 없이 통과
        e.git("switch", "-q", "master")
        e.git("merge", "-q", "--no-ff", "-m", "[AI-LOG-001] feature/LOG-001 master 병합", "-m", "  로그 이어서 반영",
              "feature/LOG-001")
        rc, err = self.push_master()
        self.assertEqual(rc, 0, err)
        self.assertEqual(err, "")
        self.assertEqual(e.remote_rev("refs/heads/master"), e.rev("master"))
        # 형식이 틀린 병합 메시지는 병합 단계에서 막힌다(커밋 규칙)
        e.git("switch", "-q", "feature/LOG-001")
        e.talk(("또 대화", "네"))
        snapshot.submit_snapshots(ctx, task="AI-LOG-001", title="세션 로그 스냅샷 (추가)")
        e.git("switch", "-q", "master")
        bad = subprocess.run(["git", "-C", e.root, "merge", "-q", "--no-ff", "-m", "Merge branch", "feature/LOG-001"],
                             capture_output=True)
        self.assertNotEqual(bad.returncode, 0)
        self.assertIn("title_format", bad.stderr.decode("utf-8"))

class TaskIdHistoryTests(unittest.TestCase):
    """작업 ID 매핑: feature/SETUP-001의 모든 커밋(직접·예약·로그·병합)은 [SETUP-001], LOG-xxx는 [AI-LOG-xxx]."""

    CFG = {"service": "", "task_prefixes": {"LOG-": "AI"}, "main": "master", "develop": "develop", "remote": "origin"}

    def setUp(self):
        self.env = FlowEnv(config_data=self.CFG)
        self.addCleanup(self.env.cleanup)
        p = mock.patch.dict(os.environ, {"AI_LOGGING_CLAUDE_HOME": self.env.claude,
                                         "AI_LOGGING_CODEX_HOME": self.env.codex})
        p.start()
        self.addCleanup(p.stop)

    def test_task_id_mapping(self):
        cfg = config.load(self.env.ctx)
        self.assertEqual(config.task_id(cfg, "SETUP-001"), "SETUP-001")
        self.assertEqual(config.task_id(cfg, "LOG-002"), "AI-LOG-002")
        self.assertEqual(config.task_id(dict(cfg, task_prefixes={"LOG-": "AI", "LOG-UI-": "WEB"}), "LOG-UI-1"), "WEB-LOG-UI-1")
        self.assertEqual(config.task_id(dict(cfg, service="AI", task_prefixes={}), "SETUP-001"), "AI-SETUP-001")  # 이전 설정 호환
        self.assertIsNone(config.check_branch_task(self.env.ctx, "SETUP-001", "develop"))  # feature 아닌 브랜치 제외

    def titles(self, rng):
        return self.env.git("log", "--format=%s", rng).splitlines()

    def test_setup_feature_history_uses_setup_task_id(self):
        e, ctx = self.env, self.env.ctx
        self.assertTrue(flows.new_feature(ctx, "SETUP-001")["ok"])
        master0 = e.rev("master")
        e.edit("src/app.kt", "fun main() {}\n")
        # 브랜치와 다른 작업 ID는 commit-msg가 거부(AI 접두어·다른 작업)
        for bad in ("AI-SETUP-001", "AI-LOG-001"):
            r = e.commit_msg(bad, "구성", "내용", allow_fail=True)
            self.assertNotEqual(r.returncode, 0)
            self.assertIn("task_branch_mismatch", r.stderr.decode("utf-8"))
        e.commit_msg("SETUP-001", "프로젝트 구성", "골격")
        # 예약 작업·로그 커밋도 같은 규칙
        self.assertEqual(tasks.reserve(ctx, "stage-log", {"task": "AI-SETUP-001", "stage": "1단계"})["reason"],
                         "task_branch_mismatch")
        self.assertEqual(tasks.reserve(ctx, "commit", {"message": "[AI-LOG-001] x\n\n  y\n", "paths": ["README.md"]})
                         ["reason"], "task_branch_mismatch")
        self.assertEqual(snapshot.commit_snapshots(ctx, "AI-LOG-001", "스냅샷")["reason"], "task_branch_mismatch")
        self.assertTrue(tasks.reserve(ctx, "stage-log", {"task": "SETUP-001", "stage": "1단계"})["ok"])
        tasks.cancel(ctx, tasks.listing(ctx)[-1]["id"])
        # develop 검증 → 로그 게시 → 최종 병합: 도구가 만드는 커밋도 [SETUP-001]
        e.merge_into_develop("feature/SETUP-001", "SETUP-001")
        e.talk(("develop 검토", "통과"))
        r = flows.push_develop(ctx, "SETUP-001")
        self.assertTrue(r["ok"] and r["pushed"], r)
        self.assertTrue(e.git("log", "-1", "--format=%s", "develop").startswith("[SETUP-001] 세션 로그 스냅샷"))
        self.assertTrue(e.git("log", "-1", "--format=%s", "master").startswith("[SETUP-001] 세션 로그 스냅샷"))
        e.talk(("마무리", "네"))
        r = flows.merge_feature(ctx, "SETUP-001")
        self.assertTrue(r["ok"] and r["merged"], r)
        m = e.rev("master")
        self.assertEqual(e.git("log", "-1", "--format=%s", m).split(" ", 1)[0], "[SETUP-001]")
        self.assertIn("feature/SETUP-001 master 병합", e.git("log", "-1", "--format=%s", m))
        feature_titles = self.titles(f"{master0}..feature/SETUP-001")
        self.assertTrue(feature_titles)
        self.assertTrue(all(t.startswith("[SETUP-001] ") for t in feature_titles), feature_titles)
        self.assertTrue(all(t.startswith("[SETUP-001] ") for t in self.titles(f"{master0}..{m}")))
        p = subprocess.run(["git", "-C", e.root, "push", "-q", "origin", "master"], capture_output=True)
        self.assertEqual(p.returncode, 0, p.stderr.decode("utf-8", "replace"))

    def test_held_session_does_not_block_push_develop_and_joins_later(self):
        """한 세션이 보류여도 push-develop은 진행(경고). 보류를 해소하면 다음 게시에 그 세션이 이어서 쌓인다."""
        e, ctx = self.env, self.env.ctx
        self.assertTrue(flows.new_feature(ctx, "SETUP-001")["ok"])
        e.edit("src/app.kt", "fun main() {}\n")
        e.commit_msg("SETUP-001", "프로젝트 구성", "골격")
        e.merge_into_develop("feature/SETUP-001", "SETUP-001")
        e.claude_session("proj", "held-sess", claude_records(e.root) + jl(prompt("mail " + OTHER_EMAIL), answer("네"), END))
        r = flows.push_develop(ctx, "SETUP-001")
        self.assertTrue(r["ok"] and r["pushed"], r)
        self.assertIn("logs:snapshot_hold claude/held-sess 1건", " ".join(r["warnings"]))

        def keys(rev):
            return {f"{m['tool']}/{m['session_id']}" for m in snapshot.tree_manifests(e.root, rev).values()}

        self.assertNotIn("claude/held-sess", keys("master"))
        self.assertIn("claude/flow-sess", keys("master"))
        first = set(snapshot.tree_manifests(e.root, "master"))
        # 사용자가 검토 후 치환 목록에 추가(mask-held와 같은 효과) → 다음 게시에 이어서 반영
        with open(ctx.mask_path, "a", encoding="utf-8") as f:
            f.write(json.dumps({"kind": "literal", "value": OTHER_EMAIL}) + "\n")
        e.talk(("보류 해소 뒤", "네"))
        r = flows.push_develop(ctx, "SETUP-001")
        self.assertTrue(r["ok"] and r["pushed"], r)
        self.assertFalse([w for w in r["warnings"] if "snapshot_hold" in w], r["warnings"])
        self.assertIn("claude/held-sess", keys("master"))
        self.assertTrue(first < set(snapshot.tree_manifests(e.root, "master")))  # 이전 스냅샷은 그대로, 새것 추가
        self.assertEqual(snapshot.scan_tree_secrets(ctx, "master", engine.build_policy(ctx)), [])


if __name__ == "__main__":
    unittest.main()
