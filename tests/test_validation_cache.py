"""증분 검사가 전수 검사와 같고, 원본·정책 변경을 놓치지 않는지 검증한다."""
import json
import os
import sqlite3
import unittest
from pathlib import Path
from unittest import mock

from test_ai_logging import Env, OTHER_EMAIL, repo_allowlist
from ailog import snapshot, gitops
from ailog.common import sha256
from ailog.policy import Policy, Allowlist


class CacheTests(unittest.TestCase):
    def setUp(self):
        self.env = Env()
        self.addCleanup(self.env.cleanup)
        self.ctx = self.env.ctx
        self.directory = self.ctx.p("verification-cache")

    def policy(self, entries=None, allow=None):
        return Policy(entries or [], allow or repo_allowlist(), cache_dir=self.directory)

    def test_append_scans_only_new_line_across_instances(self):
        old = b'{"text":"first"}\n{"text":"second"}\n'
        first = self.policy()
        self.assertEqual(first.detect(old), [])
        second = self.policy()
        with mock.patch.object(second, "_detect_line", wraps=second._detect_line) as scan:
            self.assertEqual(second.detect(old + b'{"text":"third"}\n'), [])
            self.assertEqual(scan.call_count, 1)
        self.assertGreater(second.cache.hits, 0)

    def test_modified_line_and_new_failure_have_current_line_numbers(self):
        self.policy().detect(b"old\nend\n")
        buf = b"new\n" + OTHER_EMAIL.encode() + b"\nend\n" + OTHER_EMAIL.encode()
        pol = self.policy()
        expected = Policy([], repo_allowlist()).detect(buf)
        self.assertEqual(pol.detect(buf), expected)
        self.assertEqual(pol.detect(buf), expected)
        self.assertEqual([h.line for h in expected], [2, 4])

    def test_policy_allow_mask_and_code_invalidate_markers(self):
        buf = b"ordinary line\n"
        self.policy().detect(buf)
        raw = json.dumps({"emails": {"exact": [OTHER_EMAIL]}}).encode()
        variants = [self.policy(entries=[{"value": "ordinary"}]),
                    self.policy(allow=Allowlist(json.loads(raw), raw))]
        with mock.patch("ailog.validation_cache.scanner_digest", return_value="new-code"):
            variants.append(self.policy())
        for pol in variants:
            with mock.patch.object(pol, "_detect_line", wraps=pol._detect_line) as scan:
                pol.detect(buf)
                self.assertEqual(scan.call_count, 1)

    def test_corrupt_database_falls_back_to_full_scan(self):
        pol = self.policy()
        pol.detect(b"clean\n")
        Path(pol.cache.path).write_bytes(b"not a database")
        pol = self.policy()
        with mock.patch.object(pol, "_detect_line", wraps=pol._detect_line) as scan:
            self.assertEqual(pol.detect(b"clean\n"), [])
            self.assertEqual(scan.call_count, 1)
        self.assertTrue(pol.cache.disabled)

    def test_corrupt_row_is_not_a_pass(self):
        pol = self.policy()
        pol.detect(b"clean\n")
        with sqlite3.connect(pol.cache.path) as conn:
            conn.execute("UPDATE passed SET proof='broken'")
        with mock.patch.object(pol, "_detect_line", wraps=pol._detect_line) as scan:
            pol.detect(b"clean\n")
            self.assertEqual(scan.call_count, 1)

    def test_database_lock_falls_back_to_full_scan(self):
        pol = self.policy()
        pol.detect(b"clean\n")
        with sqlite3.connect(pol.cache.path) as conn:
            conn.execute("BEGIN EXCLUSIVE")
            with mock.patch.object(pol, "_detect_line", wraps=pol._detect_line) as scan:
                pol.detect(b"clean\n")
                self.assertEqual(scan.call_count, 1)
            conn.rollback()

    def test_residue_across_lines_is_always_checked(self):
        pol = self.policy(entries=[{"value": "first\nsecond"}])
        buf = b"first\nsecond"
        pol.detect(buf)
        self.assertEqual(pol.detect(buf), [])
        self.assertGreater(pol.residue(buf), 0)

    def test_unterminated_tail_join_does_not_reuse_wrong_line(self):
        pol = self.policy()
        prefix, suffix = OTHER_EMAIL.split("@")
        pol.detect(prefix.encode())
        buf = (prefix + "@" + suffix).encode()
        self.assertEqual(pol.detect(buf), Policy([], repo_allowlist()).detect(buf))
        self.assertTrue(pol.detect(buf))

    def test_cache_stores_only_hashes_never_input_values(self):
        pol = self.policy()
        pol.detect(b"private conversation body\n" + OTHER_EMAIL.encode())
        data = Path(pol.cache.path).read_bytes()
        self.assertNotIn(b"private conversation body", data)
        self.assertNotIn(OTHER_EMAIL.encode(), data)

    def test_prefix_hash_checkpoints_detect_rewrite_and_truncation(self):
        data = b"one\ntwo\nthree\n"
        points = [(n, sha256(data[:n])) for n in (0, 4, 8, len(data))]
        self.assertEqual(snapshot._prefix_problems("x", data, points), [])
        self.assertIn("original_rewritten", snapshot._prefix_problems("x", b"ONE" + data[3:], points))
        self.assertIn("original_truncated", snapshot._prefix_problems("x", data[:-1], points))
        self.assertEqual(snapshot._prefix_problems("x", data, points + [points[1]]), [])
        self.assertIn("original_rewritten", snapshot._prefix_problems("x", data, [(4, "bad"), points[1]]))

    def scan_tree(self, pol, content):
        paths = {f"ai-logs/snapshots/t/s/p/{n}/files/main.jsonl": ("100644", "oid") for n in range(2)}
        with mock.patch.object(gitops, "tree_files", return_value=paths), \
             mock.patch.object(gitops, "blob_batches", side_effect=lambda root, ids: [list(ids)] if ids else []), \
             mock.patch.object(gitops, "cat_blobs", return_value=content) as reader:
            result = snapshot.scan_tree_secrets(self.ctx, "tree", pol)
        return result, reader.call_count

    def test_duplicate_blobs_scanned_once_and_next_run_reads_none(self):
        pol = self.policy()
        with mock.patch.object(pol, "residue", wraps=pol.residue) as scan:
            result, reads = self.scan_tree(pol, {"oid": b"clean\n"})
            self.assertEqual(result, [])
            self.assertEqual((reads, scan.call_count), (1, 1))
        result, reads = self.scan_tree(self.policy(), {"oid": b"clean\n"})
        self.assertEqual((result, reads), ([], 0))

    def test_blob_failure_reported_for_all_paths_not_cached_as_clean(self):
        for _ in range(2):
            result, reads = self.scan_tree(self.policy(), {"oid": OTHER_EMAIL.encode()})
            self.assertEqual(reads, 1)
            self.assertEqual(len(result), 2)
            self.assertTrue(all(x["rule"] == "committed_email" for x in result))

    def test_missing_blob_is_a_failure(self):
        result, reads = self.scan_tree(self.policy(), {})
        self.assertEqual(reads, 1)
        self.assertEqual(len(result), 2)
        self.assertTrue(all(x["rule"] == "committed_blob_unreadable" for x in result))

    def test_same_content_new_mask_rechecks_full_blob(self):
        self.scan_tree(self.policy(), {"oid": b"first\nsecond"})
        result, reads = self.scan_tree(self.policy(entries=[{"value": "first\nsecond"}]), {"oid": b"first\nsecond"})
        self.assertEqual(reads, 1)
        self.assertTrue(all(x["rule"] == "committed_mask_residue" for x in result))


if __name__ == "__main__":
    unittest.main()
