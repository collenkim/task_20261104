"""문서 검사기가 실제 깨진 링크·정책과 정상 코드 예제를 구분하는지 검사한다."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("docs_check", Path(__file__).resolve().parents[1] / "scripts/verification/docs-check.py")
docs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(docs)


class DocumentTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "docs/common").mkdir(parents=True)

    def page(self, name, body):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding="utf-8")

    def test_korean_and_duplicate_headers_and_html_anchors(self):
        self.page("README.md", '[한글](#한글-제목) [중복](#한글-제목-1) [HTML](#named)\n# 한글 제목\n# 한글 제목\n<a id="named"></a>')
        self.assertEqual(docs.check(self.root)["오류"], [])

    def test_missing_file_and_anchor_are_rejected(self):
        self.page("README.md", '[파일](missing.md) [앵커](#없음)')
        self.assertEqual(len(docs.check(self.root)["오류"]), 2)

    def test_code_examples_and_external_links_are_not_local_links(self):
        self.page("README.md", '```md\n[예제](missing.md) POL-TEST-999\n```\n[외부](https://example.invalid)')
        self.assertEqual(docs.check(self.root)["오류"], [])

    def test_unresolved_policy_is_rejected(self):
        self.page("README.md", 'POL-TEST-999')
        self.assertIn('정책 정의 없음', docs.check(self.root)["오류"][0])

    def test_historical_policy_references_do_not_redefine_current_contract(self):
        self.page("docs/tasks/OLD/request.md", 'POL-TEST-999')
        self.assertEqual(docs.check(self.root)["오류"], [])

    def test_classification_and_policy_definition_are_checked(self):
        self.page("docs/common/policy.md", '| 분류 | 기준 |\n| 상태 | 현재 |\n## POL-TEST-001 규칙')
        self.page("README.md", 'POL-TEST-001')
        self.assertEqual(docs.check(self.root)["오류"], [])
        self.page("docs/common/second.md", '## POL-TEST-001 중복')
        self.assertEqual(len(docs.check(self.root)["오류"]), 3)


if __name__ == "__main__":
    unittest.main()
