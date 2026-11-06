"""검증된 Claude 종료 표시 추가와 과거 메타데이터의 불변 보존 회귀."""
import json
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest import mock

from test_guard_snapshots import Base
from test_ai_logging import base_entries, rb
from ailog import engine, metadata, snapshot
from ailog.common import dumps_json


class MetadataHistoryTests(Base):
    def setUp(self):
        super().setUp()
        self.main = self.claude("meta1")
        self.rel = "meta1/subagents/agent-a123.meta.json"
        self.original = Path(self.main).parent / self.rel
        self.original.parent.mkdir(parents=True)
        self.before = b'{"agentType":"Scan","description":"Review"}'
        self.after = self.before[:-1] + b',"finished":true}'
        self.original.write_bytes(self.before)
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        self.live = Path(self.ctx.live_dir) / "claude" / "meta1"

    def finished(self):
        self.original.write_bytes(self.after)

    def history(self, manifest=None):
        manifest = manifest or json.loads((self.live / "manifest.json").read_bytes())
        return [e for e in manifest["files"] if e.get("role") == metadata.ROLE]

    def archive_copies(self):
        return sorted((Path(self.ctx.state_dir) / "metadata-history").glob("claude/meta1/*/copy"))

    def test_sync_preserves_previous_copy_and_snapshot_covers_both_versions(self):
        self.finished()
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        self.assertEqual(self.archive_copies()[0].read_bytes(), self.before)
        entries = self.history()
        self.assertEqual(len(entries), 1)
        self.assertEqual((self.live / "files" / entries[0]["path"]).read_bytes(), self.before)
        self.assertEqual((self.live / "files" / self.rel).read_bytes(), self.after)
        self.assertTrue(engine.verify(self.ctx, final=True)["ok"])
        first = snapshot.snapshot_all(self.ctx, refs=())
        self.assertEqual(first["result"], "ok", first.get("holds"))
        self.assertTrue(snapshot.completeness(self.ctx, None)["complete"])
        self.assertTrue(snapshot.live_covered(self.ctx)["covered"])
        second = snapshot.snapshot_all(self.ctx, refs=())
        self.assertEqual(first["sessions"][0]["base"], second["sessions"][0]["base"])
        self.assertEqual(second["sessions"][0]["status"], "exists")
        self.assertEqual(self.original.read_bytes(), self.after)

    def test_snapshot_before_sync_preserves_live_and_older_snapshot(self):
        old = snapshot.snapshot_all(self.ctx, refs=())
        old_root = Path(self.env.root) / old["sessions"][0]["base"]
        old_files = {str(p.relative_to(old_root)): p.read_bytes() for p in old_root.rglob("*") if p.is_file()}
        self.finished()
        self.assertEqual(snapshot.snapshot_all(self.ctx, refs=())["result"], "ok")
        self.assertEqual((self.live / "files" / self.rel).read_bytes(), self.before)
        self.assertTrue(snapshot.live_covered(self.ctx)["covered"])
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        for path, content in old_files.items():
            self.assertEqual((old_root / path).read_bytes(), content)
        self.assertTrue(snapshot.completeness(self.ctx, None)["complete"])

    def test_other_changes_invalid_json_and_duplicate_keys_remain_held(self):
        bad_versions = [
            self.after.replace(b"Review", b"Changed"),
            self.before[:-1] + b',"finished":false}',
            self.before[:-1] + b',"finished":1}',
            self.before[:-1] + b',"finished":false,"finished":true}',
            self.before[:-1] + b',"agentType":"Other","finished":true}',
            self.after[:-1],
        ]
        for index, data in enumerate(bad_versions):
            with self.subTest(case=index):
                self.original.write_bytes(data)
                result = engine.sync(self.ctx)
                self.assertEqual(result["result"], "hold")
                self.assertEqual((self.live / "files" / self.rel).read_bytes(), self.before)
                self.assertEqual(snapshot.snapshot_all(self.ctx, refs=())["result"], "hold")

    def test_changed_private_field_is_not_hidden_by_masking(self):
        first = "private-" + "one"
        second = "private-" + "two"
        self.env.write_mask(base_entries() + [{"kind": "literal", "value": first}, {"kind": "literal", "value": second}])
        # 두 값이 모두 ****로 치환되어도 원본 해시가 다르면 갱신을 허용하지 않는다.
        extra = self.original.with_name("agent-private.meta.json")
        extra.write_bytes(json.dumps({"description": first}, separators=(",", ":")).encode())
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        extra.write_bytes(json.dumps({"description": second, "finished": True}, separators=(",", ":")).encode())
        self.assertEqual(engine.sync(self.ctx)["result"], "hold")

    def test_corrupt_old_live_copy_blocks_transition(self):
        (self.live / "files" / self.rel).write_bytes(b"damaged")
        self.finished()
        result = engine.sync(self.ctx)
        self.assertEqual(result["result"], "hold")
        self.assertIn("metadata_previous_copy_mismatch", {p["rule"] for p in result["holds"]})
        self.assertEqual(self.archive_copies(), [])

    def test_policy_change_remasks_history_and_keeps_archived_bytes(self):
        old_copy = (self.live / "files" / self.rel).read_bytes()
        self.env.write_mask(base_entries() + [{"kind": "literal", "value": "Review"}])
        self.finished()
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        self.assertEqual(self.archive_copies()[0].read_bytes(), old_copy)
        entry = self.history()[0]
        self.assertEqual((self.live / "files" / entry["path"]).read_bytes(), self.before.replace(b"Review", b"****"))
        self.assertEqual(snapshot.snapshot_all(self.ctx, refs=())["result"], "ok")
        self.assertTrue(engine.verify(self.ctx, final=True)["ok"])

    def test_archive_and_export_tampering_are_detected(self):
        self.finished()
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        created = snapshot.snapshot_all(self.ctx, refs=())
        base = Path(self.env.root) / created["sessions"][0]["base"]
        entry = self.history()[0]
        path = base / "files" / entry["path"]
        old = path.read_bytes()
        path.write_bytes(b"damaged")
        self.assertFalse(snapshot.completeness(self.ctx, None)["complete"])
        self.assertEqual(snapshot.snapshot_all(self.ctx, refs=())["result"], "hold")
        path.write_bytes(old)
        self.archive_copies()[0].write_bytes(b"damaged")
        self.assertEqual(engine.sync(self.ctx)["result"], "hold")
        self.assertFalse(engine.verify(self.ctx, final=True)["ok"])

    def test_missing_history_cannot_pass_completeness(self):
        self.finished()
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        result = snapshot.snapshot_all(self.ctx, refs=())
        path = Path(self.env.root) / result["sessions"][0]["base"] / "manifest.json"
        manifest = json.loads(path.read_bytes())
        manifest["files"] = [e for e in manifest["files"] if e.get("role") != metadata.ROLE]
        manifest["snapshot_sha256"] = snapshot.snapshot_sha256(manifest)
        manifest["snapshot_id"] = snapshot.snapshot_id(manifest)
        path.write_bytes(dumps_json(manifest))
        result = snapshot.completeness(self.ctx, None)
        self.assertIn("metadata_history_not_in_snapshot", {p["rule"] for p in result["failures"]})

    def test_history_survives_lost_state_and_source_rollback_is_rejected(self):
        self.finished()
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        Path(self.ctx.state_path).write_bytes(b"broken")
        Path(self.ctx.state_prev_path).write_bytes(b"broken")
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        self.assertEqual(len(self.history()), 1)
        self.original.write_bytes(self.before)
        self.assertEqual(engine.sync(self.ctx)["result"], "hold")

    def test_interrupted_live_write_recovers_from_committed_archive(self):
        self.finished()
        write = engine.atomic_write
        live_current = os.path.normpath(str(self.live / "files" / self.rel))

        def interrupted(path, data):
            if os.path.normpath(path) == live_current:
                raise OSError("simulated interruption")
            return write(path, data)

        with mock.patch.object(engine, "atomic_write", side_effect=interrupted):
            self.assertEqual(engine.sync(self.ctx)["result"], "error")
        self.assertEqual(self.archive_copies()[0].read_bytes(), self.before)
        self.assertEqual((self.live / "files" / self.rel).read_bytes(), self.before)
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        self.assertEqual(len(self.archive_copies()), 1)
        self.assertTrue(engine.verify(self.ctx, final=True)["ok"])

    def test_concurrent_collectors_keep_one_previous_version(self):
        self.finished()
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: engine.sync(self.ctx), range(2)))
        self.assertTrue(all(r["result"] == "ok" for r in results))
        self.assertEqual(len(self.archive_copies()), 1)
        self.assertEqual(len(self.history()), 1)

    def test_jsonl_rewrite_and_metadata_outside_subagents_still_fail(self):
        wrong = Path(self.main).parent / "meta1" / "agent-outside.meta.json"
        wrong.write_bytes(self.before)
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        wrong.write_bytes(self.after)
        self.assertEqual(engine.sync(self.ctx)["result"], "hold")
        wrong.write_bytes(self.before)
        data = rb(self.main)
        Path(self.main).write_bytes(data.replace(b"user", b"USER", 1))
        self.assertEqual(engine.sync(self.ctx)["result"], "hold")


class MetadataCommitTests(Base):
    git_repo = True

    def test_history_is_in_committed_tree_and_passes_final_checks(self):
        main = self.claude("commit-meta")
        rel = "commit-meta/subagents/agent-a1.meta.json"
        path = Path(main).parent / rel
        path.parent.mkdir(parents=True)
        before = b'{"description":"Review"}'
        path.write_bytes(before)
        self.assertEqual(engine.sync(self.ctx)["result"], "ok")
        path.write_bytes(before[:-1] + b',"finished":true}')
        result = snapshot.commit_snapshots(self.ctx, "REVIEW-002", "메타데이터 검증")
        self.assertTrue(result["ok"], result.get("reason"))
        self.assertTrue(snapshot.completeness(self.ctx, "HEAD")["complete"])
        self.assertEqual(snapshot.scan_tree_secrets(self.ctx, "HEAD"), [])
        manifests = snapshot.tree_manifests(self.ctx.root, "HEAD")
        self.assertEqual(sum(e.get("role") == metadata.ROLE for m in manifests.values() for e in m["files"]), 1)
