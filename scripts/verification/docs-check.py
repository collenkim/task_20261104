"""저장소 문서의 로컬 링크·앵커·분류·정책 참조 검사. 파일을 수정하지 않는다."""
from __future__ import annotations

import argparse
import html
import re
from pathlib import Path
from urllib.parse import unquote, urlsplit


def prose(text: str) -> str:
    """코드 예제 안의 링크·정책 문자열은 문서 계약으로 해석하지 않는다."""
    lines, fence = [], None
    for line in text.splitlines():
        marker = re.match(r"^\s*(`{3,}|~{3,})", line)
        if marker:
            if fence is None:
                fence = marker[1][0]
            elif fence == marker[1][0]:
                fence = None
            lines.append("")
        else:
            lines.append(line if fence is None else "")
    return "\n".join(lines)


def anchors(text: str) -> set[str]:
    found = set(re.findall(r'\bid=["\']([^"\']+)["\']', text))
    counts = {}
    for title in re.findall(r"^#{1,6}\s+(.+?)\s*#*\s*$", prose(text), re.M):
        title = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", title)
        title = re.sub(r"<[^>]+>", "", title)
        slug = re.sub(r"[^\w\- ]", "", html.unescape(title).lower()).replace(" ", "-")
        n = counts.get(slug, 0)
        found.add(slug + (f"-{n}" if n else ""))
        counts[slug] = n + 1
    return found


def collect(root: Path) -> list[Path]:
    paths = [root / name for name in ("README.md", "AGENTS.md", "CLAUDE.md")]
    paths += list((root / "docs").rglob("*.md"))
    paths += list((root / "services").glob("*/docs/**/*.md"))
    return sorted({p for p in paths if p.is_file()})


def check(root: Path) -> dict:
    root = root.resolve()
    pages = collect(root)
    errors, definitions, references = [], {}, []
    links = 0
    for path in pages:
        rel = path.relative_to(root).as_posix()
        text = path.read_text(encoding="utf-8-sig")
        body = prose(text)
        # 과거 작업/검증의 본문은 새 정책으로 고쳐 쓰지 않는다.
        history = rel.startswith(("docs/tasks/", "docs/verification/"))
        if path.name != "README.md" and path.parent != root and not history:
            for field in ("분류", "상태"):
                if not re.search(r"^\|\s*" + field + r"\s*\|", text, re.M):
                    errors.append(f"{rel}: 문서 {field} 없음")
        for line_no, line in enumerate(body.splitlines(), 1):
            for target in re.findall(r"!?\[[^\]]*\]\(([^\s)]+)(?:\s+[^)]*)?\)", line):
                target = target.strip("<>")
                parsed = urlsplit(target)
                if parsed.scheme or parsed.netloc:
                    continue
                resolved = (path.parent / unquote(parsed.path)).resolve() if parsed.path else path
                links += 1
                if not resolved.is_relative_to(root) or not resolved.is_file():
                    errors.append(f"{rel}:{line_no}: 로컬 링크 대상 없음")
                elif parsed.fragment and resolved.suffix == ".md":
                    if unquote(parsed.fragment) not in anchors(resolved.read_text(encoding="utf-8-sig")):
                        errors.append(f"{rel}:{line_no}: 앵커 없음 ({unquote(parsed.fragment)})")
        if not history:
            for ident in re.findall(r"^#{1,6}\s+(POL-[A-Z]+-\d+)\b", body, re.M):
                if ident in definitions:
                    errors.append(f"{rel}: 정책 중복 정의 ({ident})")
                definitions[ident] = rel
            references += [(rel, ident) for ident in re.findall(r"\bPOL-[A-Z]+-\d+\b", body)]
    errors += [f"{rel}: 정책 정의 없음 ({ident})" for rel, ident in sorted(set(references)) if ident not in definitions]
    return {"문서": len(pages), "로컬_링크": links, "정책": len(definitions), "오류": errors}


def main():
    import json
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    report = check(parser.parse_args().root)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return bool(report["오류"])


if __name__ == "__main__":
    raise SystemExit(main())
