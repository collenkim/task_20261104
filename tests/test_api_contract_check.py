"""실제 공유 계약을 복사한 뒤 의도적인 서비스 간 차이를 검출하는 회귀 검사."""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("api_contract_check", ROOT / "scripts/verification/api-contract-check.py")
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class ApiContractCheckTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for source in CHECKER.inputs(ROOT):
            target = self.root / source.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)

    def change(self, mutation, service="member"):
        path = self.root / f"services/{service}-service/docs/openapi.json"
        document = json.loads(path.read_text(encoding="utf-8"))
        mutation(document)
        path.write_text(json.dumps(document), encoding="utf-8")
        self.assertTrue(CHECKER.check(self.root))

    def test_current_contract(self):
        self.assertEqual([], CHECKER.check(self.root))

    def test_zero_based_page(self):
        self.change(lambda d: d["paths"]["/api/members"]["get"]["parameters"][0]["schema"].update(default=0))

    def test_page_object_removed(self):
        self.change(lambda d: d["components"]["schemas"]["MemberSummaryPageResponse"]["properties"].pop("page"))

    def test_problem_media_type(self):
        def mutation(d):
            content = d["paths"]["/api/members"]["get"]["responses"]["400"]["content"]
            content["application/problem+json"] = content.pop("application/json")
        self.change(mutation)

    def test_undefined_error_field(self):
        self.change(lambda d: d["components"]["schemas"]["MemberProblemResponse"]["properties"].update(debug={"type": "string"}))

    def test_group_status_code_drift(self):
        path = self.root / "services/group-service/src/main/kotlin/task/group/presentation/error/ErrorCode.kt"
        path.write_text(path.read_text(encoding="utf-8").replace('"INVALID_PARAMETER", 400', '"WRONG_CODE", 400'), encoding="utf-8")
        self.assertTrue(CHECKER.check(self.root))

    def test_service_document_drift(self):
        path = self.root / "services/group-service/docs/api.md"
        path.write_text(path.read_text(encoding="utf-8").replace("`BAD_GATEWAY`", "`INTERNAL_ERROR`"), encoding="utf-8")
        self.assertTrue(CHECKER.check(self.root))


if __name__ == "__main__":
    unittest.main()
