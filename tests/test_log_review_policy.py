"""GROUP-001 로그 오탐 허용 범위와 개인정보 마스킹 회귀 검사."""
import json
import os
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
if ROOT.name == ".ai-logging":
    ROOT = ROOT.parent
sys.path.insert(0, str(ROOT / "scripts/ai_logging"))
from ailog.policy import Policy, load_allowlist


class LogReviewPolicyTests(unittest.TestCase):
    def setUp(self):
        allowlist = os.environ.get("AI_LOG_REVIEW_ALLOWLIST") or ROOT / "config/ai-logs-allowlist.json"
        self.policy = Policy([], load_allowlist(str(allowlist)))

    def test_kotlin_receiver_expressions_are_preserved(self):
        source = b"this@HttpGroupMembershipTests.executor\nthis@GroupRetryHttpTests.executor\n"
        self.assertEqual(self.policy.detect(source), [])
        self.assertEqual(self.policy.mask(source)[0], source)

    def test_reviewed_emails_do_not_allow_other_contacts(self):
        source = ("another-person" + "@" + "example.invalid").encode()
        self.assertIn("email", {h.rule for h in self.policy.detect(source)})

    def test_type_exception_does_not_allow_longer_secret(self):
        self.assertEqual(self.policy.detect(b"token: String"), [])
        value = b"String" + b"PrivateValue9"
        self.assertIn("keyword:token", {h.rule for h in self.policy.detect(b"token=" + value)})

    def test_wrapper_checksum_is_not_a_general_password_exception(self):
        lines = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_bytes().splitlines()
        assignment = next(line for line in lines if line.startswith(b"distributionSha256Sum="))
        self.assertEqual(self.policy.detect(assignment), [])
        digest = assignment.split(b"=", 1)[1]
        self.assertIn("keyword:password", {h.rule for h in self.policy.detect(b"password=" + digest)})

    def test_private_email_masking_preserves_record_order(self):
        contact = "private-person" + "@" + "mail.invalid"
        policy = Policy([{"kind":"literal", "value":contact}], self.policy.allow)
        source = (json.dumps({"order":1,"text":contact}) + "\r\n" + json.dumps({"order":2,"text":"unchanged"}) + "\r\n").encode()
        masked, _ = policy.mask(source)
        self.assertEqual(masked, source.replace(contact.encode(), b"****"))
        self.assertEqual([json.loads(line)["order"] for line in masked.splitlines()], [1,2])
        self.assertEqual(policy.residue(masked), 0)

    def test_new_opaque_url_value_still_requires_review(self):
        value = b"Qx7Ab9Cd2Ef8" + b"Gh3Jk6Lm1Np5" + b"Rs4Tu0Vw8Xy6"
        self.assertIn("random_string", {h.rule for h in self.policy.detect(b"https://public.invalid/view/" + value)})

    def test_approved_pdf_identifier_is_preserved_but_other_ids_are_held(self):
        identifier = b"832470d42097c20b28e60aa4a830b4502f1d5630"
        prefix = b"https://ydb.tech/docs/_/api/pdf?pdfRoot="
        source = prefix + identifier + b"%2Fen"
        self.assertEqual(self.policy.mask(source)[0], source)
        self.assertEqual(self.policy.detect(source), [])
        decoded = prefix + identifier + b"/en"
        self.assertEqual(self.policy.mask(decoded)[0], decoded)
        self.assertEqual(self.policy.detect(decoded), [])
        other = prefix + b"0" + identifier[1:] + b"%2Fen"
        self.assertIn("random_string", {h.rule for h in self.policy.detect(other)})


if __name__ == "__main__":
    unittest.main()
