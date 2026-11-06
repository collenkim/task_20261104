"""두 서비스의 공통 오류·미디어 타입·공개 페이지 계약을 읽기 전용으로 대조한다."""
from __future__ import annotations

import json
import re
from pathlib import Path

ERROR_FIELDS = {"type", "title", "status", "detail", "instance", "code"}


def inputs(root: Path) -> list[Path]:
    paths = [root / "docs/common/api-conventions.md"]
    for service in ("member", "group"):
        base = root / f"services/{service}-service"
        paths += [base / "docs/openapi.json", base / "docs/api.md",
                  base / f"src/main/kotlin/task/{service}/presentation/error/ErrorCode.kt"]
    return paths


def check(root: Path) -> list[str]:
    errors, definitions, documents = [], {}, {}
    for service in ("member", "group"):
        base = root / f"services/{service}-service"
        source = (base / f"src/main/kotlin/task/{service}/presentation/error/ErrorCode.kt").read_text(encoding="utf-8")
        codes = {int(status): code for code, status in re.findall(r'"([A-Z_]+)",\s*(\d{3})', source)}
        definitions[service] = codes
        document = json.loads((base / "docs/openapi.json").read_text(encoding="utf-8"))
        documents[service] = document
        schemas = document["components"]["schemas"]
        for path, item in document["paths"].items():
            methods = set(item) & {"get", "put", "post", "patch", "delete", "head", "options", "trace"}
            if methods != {"get"}:
                errors.append(f"{service} {path}: GET 전용 위반")
            for status, response in item.get("get", {}).get("responses", {}).items():
                content = response.get("content", {})
                if set(content) != {"application/json"}:
                    errors.append(f"{service} {path} {status}: JSON 미디어 타입 위반")
                    continue
                if not status.isdigit() or int(status) < 400:
                    continue
                media = content["application/json"]
                schema = media["schema"]
                if "$ref" in schema:
                    schema = schemas[schema["$ref"].rsplit("/", 1)[1]]
                if (set(schema.get("properties", {})) != ERROR_FIELDS
                        or set(schema.get("required", [])) != ERROR_FIELDS
                        or schema.get("additionalProperties") is not False):
                    errors.append(f"{service} {path} {status}: 오류 6필드 계약 위반")
                if set(schema.get("properties", {}).get("code", {}).get("enum", [])) != set(codes.values()):
                    errors.append(f"{service} {path} {status}: 오류 code 목록 불일치")
                examples = media.get("examples", {})
                if not examples:
                    errors.append(f"{service} {path} {status}: 오류 예제 없음")
                for example in examples.values():
                    value = example.get("value", {})
                    if (set(value) != ERROR_FIELDS or value.get("status") != int(status)
                            or value.get("code") != codes.get(int(status))):
                        errors.append(f"{service} {path} {status}: 오류 예제 상태·code 불일치")
        text = (base / "docs/api.md").read_text(encoding="utf-8")
        table = {int(status): code for status, code in re.findall(r'^\|\s*(\d{3})\s*\|\s*`([A-Z_]+)`', text, re.M)}
        if table != codes:
            errors.append(f"{service}: API 문서 공통 코드표 불일치")
    if not definitions["member"] or definitions["member"] != definitions["group"]:
        errors.append("두 서비스의 상태·오류 코드 불일치")
    policy = (root / "docs/common/api-conventions.md").read_text(encoding="utf-8")
    table = {int(status): code for status, code in re.findall(r'^\|\s*(\d{3})\s*\|\s*`([A-Z_]+)`', policy, re.M)}
    if table != definitions["member"]:
        errors.append("공통 정책의 상태·오류 코드표 불일치")
    member = documents["member"]
    operation = member["paths"]["/api/members"]["get"]
    page = next(p["schema"] for p in operation["parameters"] if p["name"] == "page")
    schemas = member["components"]["schemas"]
    if page.get("default") != 1 or page.get("minimum") != 1:
        errors.append("요청 page 기본값·최솟값은 1이어야 함")
    if schemas["MemberPageResponse"]["properties"]["number"].get("minimum") != 1:
        errors.append("응답 page.number는 1부터 시작해야 함")
    summary = schemas["MemberSummaryPageResponse"]
    if set(summary["properties"]) != {"members", "page"} or "page" not in summary["required"]:
        errors.append("멤버 목록의 page 객체가 누락되거나 정의 외 필드가 있음")
    return errors


if __name__ == "__main__":
    findings = check(Path(__file__).resolve().parents[2])
    print(json.dumps({"서비스": 2, "오류": findings}, ensure_ascii=False, indent=2))
    raise SystemExit(bool(findings))
