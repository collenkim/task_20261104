"""마스킹과 민감정보 검증.

원칙
- 원본은 바꾸지 않고 사본에서만 값을 `****`로 치환한다. 줄·순서·공백·개행은 그대로다.
- 마스킹: Git 제외 치환 목록 + 알려진 토큰 형식만. 길이·엔트로피만으로 치환하지 않는다.
- 검증: 마스킹보다 넓게 찾는다. 치환·허용 목록으로 처리되지 않은 후보는 보류한다.
- 진단에는 값을 남기지 않는다(규칙 종류·줄 번호·형태 정보만).
"""
from __future__ import annotations

import json
import math
import re
from bisect import bisect_right
from collections import Counter
from dataclasses import dataclass, field
from urllib.parse import urlsplit

from .common import POLICY_CODE_VERSION, sha256

MASK = b"****"

# 알려진 토큰 형식. 구조 필드(signature, encrypted_content) 밖에서만 적용한다.
TOKEN_PATTERNS: list[tuple[str, re.Pattern]] = [
    ("anthropic_key", re.compile(rb"(?<![A-Za-z0-9])sk-ant-[A-Za-z0-9_-]{20,}")),
    ("openai_key", re.compile(rb"(?<![A-Za-z0-9])sk-(?:proj-|svcacct-|admin-)?[A-Za-z0-9_-]{20,}")),
    ("github_token", re.compile(rb"(?<![A-Za-z0-9])gh[pousr]_[A-Za-z0-9]{36,}")),
    ("github_pat", re.compile(rb"(?<![A-Za-z0-9])github_pat_[A-Za-z0-9_]{22,}")),
    ("aws_access_key", re.compile(rb"(?<![A-Za-z0-9])(?:AKIA|ASIA)[0-9A-Z]{16}(?![A-Za-z0-9])")),
    ("slack_token", re.compile(rb"(?<![A-Za-z0-9])xox[abposr]-[A-Za-z0-9-]{10,}")),
    ("google_api_key", re.compile(rb"(?<![A-Za-z0-9])AIza[0-9A-Za-z_-]{35}")),
    ("jwt", re.compile(rb"(?<![A-Za-z0-9])eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}")),
    (
        "private_key",
        re.compile(
            rb"-----BEGIN [A-Z ]*PRIVATE KEY-----(?:[^\"\\\r\n]|\\[^\r\n])*?-----END [A-Z ]*PRIVATE KEY-----"
        ),
    ),
    ("bearer_token", re.compile(rb"(?i)(?<=bearer )[A-Za-z0-9._~+/-]{20,}=*")),
]

# 토큰 형식 자동 판정에서 제외하는 구조 필드(불투명한 서명·암호문).
# 다른 기록 안에 문자열로 다시 담긴 경우(예: Claude가 남긴 파일 diff 속 Codex 줄)의 이스케이프 형태도 포함한다.
_STRUCT_FIELD_RE = re.compile(
    rb'\\{0,3}"(?:signature|encrypted_content)\\{0,3}"\s*:\s*\\{0,3}"[^"\\\r\n]*\\{0,3}"'
)
# 대화에 붙은 base64 이미지(내용을 자동 판단할 수 없으므로 별도 규칙으로 보류)
_IMAGE_RE = re.compile(
    rb'"type"\s*:\s*"base64"\s*,\s*"media_type"\s*:\s*"image/[^"\r\n]*"\s*,\s*"data"\s*:\s*"([^"\r\n]*)"'
)

_EMAIL_RE = re.compile(rb"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")
_JSON_ESCAPE_LETTERS = b"ntrbf"
_PRIVATE_KEY_HEADER_RE = re.compile(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----")
# 자격 증명 값 후보: 따옴표·역따옴표·역슬래시·괄호·구분 기호가 없는 ASCII 6자 이상(한글 문장은 후보가 아님)
_VALUE = rb"(?P<v>[A-Za-z0-9!#$%&*+./:<=>?@^_|~-]{6,})"
_KEYWORD_RE = re.compile(
    rb"(?i)(?<![A-Za-z0-9])(password|passwd|pwd|pass|secret|client[_-]?secret|api[_-]?key|access[_-]?token"
    rb"|auth[_-]?token|token|private[_-]?key)"
    rb"(?:\\{0,3}[\"'])?\s*[:=]\s*(?:\\{0,3}[\"'])?" + _VALUE
)
_FLAG_RE = re.compile(
    # --flag value, --flag=value, 그리고 JSON 배열 인자 "--flag", "value"
    rb"(?i)--(password|passwd|pass|token|secret|api-key)(?:=|\s+|\\{0,3}[\"']\s*,\s*)(?:\\{0,3}[\"'])?" + _VALUE
)
_AUTH_RE = re.compile(
    rb"(?i)authorization(?:\\{0,3}[\"'])?\s*[:=]\s*(?:\\{0,3}[\"'])?\s*(?:(?:bearer|basic|token)\s+)?"
    rb"(?P<v>[A-Za-z0-9._~+/=-]{8,})"
)
_RANDOM_RE = re.compile(rb"[A-Za-z0-9+/_=-]{32,}")
# 환경 변수 대입(NAME=value) 형태면 치환 후보는 값 부분만(변수 이름은 남긴다)
_ENV_ASSIGN_RE = re.compile(rb"[A-Z][A-Z0-9_]*=(.+)")
_HEXISH_RE = re.compile(rb"[0-9a-fA-F-]+")
_TYPE_PREFIX_RE = re.compile(rb"^([a-z]{1,12}[_-])")
_KEY_BEFORE_RE = re.compile(rb'"([A-Za-z0-9_]+)"\s*:\s*"[^"]*$')
_VALUE_FULL_RE = re.compile(rb"[A-Za-z0-9!#$%&*+./:<=>?@^_|~-]{6,}")


def _string_value_at(line: bytes, key_end: int, token_end: int):
    """키 뒤의 JSON 문자열 값(따옴표 안, 이스케이프 없는 단순 값)을 돌려준다. 해석할 수 없으면 None."""
    q = line.find(b'"', line.find(b":", key_end) + 1)
    if q < 0:
        return None
    end = line.find(b'"', max(q + 1, token_end))
    if end < 0:
        return None
    v = line[q + 1:end]
    return None if b"\\" in v else v


_PLACEHOLDER_VALUES = {b"****", b"******", b"xxxxxx", b"changeme", b"example", b"placeholder", b"redacted"}


def _json_escape(s: str) -> str:
    return json.dumps(s, ensure_ascii=False)[1:-1]


def _variants(value: str) -> list[bytes]:
    """원래 형태와 JSON 이스케이프 1·2단계 형태. 중복 제거, 긴 것부터."""
    vs = {value, _json_escape(value), _json_escape(_json_escape(value))}
    return sorted({v.encode("utf-8") for v in vs if v}, key=len, reverse=True)


def _home_user_pattern(name: str) -> re.Pattern:
    """OS 사용자 이름을 독립된 단어로 찾는다(대소문자 무시).

    경로(C:\\Users\\name, /c/Users/name), 명령 출력(`ls -l`의 소유자, whoami의 PC\\name), 문장 속 단독 사용 모두 포함.
    앞뒤가 영숫자·밑줄이면 다른 단어의 일부로 보고 건드리지 않는다. 단, JSON 이스케이프(\\n, \\t, \\u0027 등)
    바로 뒤는 단어 시작으로 본다.
    """
    return re.compile(
        rb"(?i)(?:(?<![A-Za-z0-9_])|(?<=\\[ntrbf])|(?<=\\u[0-9a-fA-F]{4}))"
        + re.escape(name.encode("utf-8"))
        + rb"(?![A-Za-z0-9_])"
    )


def _opaque_spans(buf: bytes) -> tuple[list[tuple[int, int]], list[int]]:
    """짧은 단어 치환을 하지 않는 불투명 구간(서명·암호문 필드, 첨부 이미지 데이터)."""
    spans = sorted(_spans(_STRUCT_FIELD_RE, buf) + _spans(_IMAGE_RE, buf))
    return spans, [s for s, _ in spans]


def _spans(regex: re.Pattern, buf: bytes, group: int = 0) -> list[tuple[int, int]]:
    return [(m.start(group), m.end(group)) for m in regex.finditer(buf)]


def _inside(spans: list[tuple[int, int]], starts: list[int], pos: int) -> bool:
    i = bisect_right(starts, pos) - 1
    return i >= 0 and pos < spans[i][1]


def _entropy(b: bytes) -> float:
    c = Counter(b)
    n = len(b)
    return -sum(v / n * math.log2(v / n) for v in c.values())


_SEGMENT_SPLIT_RE = re.compile(rb"[/._-]+")


_UNICODE_ESCAPE_RE = re.compile(rb"u[0-9a-fA-F]{4}")


def _drop_json_escape(line: bytes, start: int, tok: bytes) -> bytes:
    """JSON 이스케이프(\\n, \\t, \\u0027 등)의 글자가 뒤 문자열에 붙어 잡힌 경우 그 글자를 뗀다.

    바로 앞의 역슬래시가 홀수 개일 때만 이스케이프다(짝수면 역슬래시 자체가 이스케이프된 실제 문자).
    """
    n = 0
    while start - n - 1 >= 0 and line[start - n - 1:start - n] == b"\\":
        n += 1
    if n % 2 == 0:
        return tok
    if tok[:1] in _JSON_ESCAPE_LETTERS:
        return tok[1:]
    if _UNICODE_ESCAPE_RE.match(tok):
        return tok[5:]
    return tok


def _has_random_segment(tok: bytes) -> bool:
    """경로·식별자처럼 짧은 조각의 연결이 아니라, 20자 이상의 무작위 조각이 있는지.

    대·소문자·숫자가 섞이고 엔트로피가 높은 조각만 후보로 본다(후보일 뿐 비밀이라는 단정은 아님).
    """
    for seg in _SEGMENT_SPLIT_RE.split(tok):
        if len(seg) < 20 or _HEXISH_RE.fullmatch(seg):
            continue
        if re.search(rb"[a-z]", seg) and re.search(rb"[A-Z]", seg) and re.search(rb"[0-9]", seg):
            if _entropy(seg) >= 3.5:
                return True
    return False


@dataclass
class Hit:
    rule: str
    line: int
    detail: str = ""  # 값이 아닌 형태 정보(길이·JSON 키·형식 접두어)
    # 검출된 값. 보고서·상태 파일에는 절대 쓰지 않고, mask-held가 치환 목록에 넣을 때만 쓴다.
    value: bytes = field(default=b"", repr=False, compare=False)


_DETECT_RULES = (
    _STRUCT_FIELD_RE, _IMAGE_RE, _EMAIL_RE, _PRIVATE_KEY_HEADER_RE, _KEYWORD_RE, _FLAG_RE, _AUTH_RE,
    _RANDOM_RE, _HEXISH_RE, _SEGMENT_SPLIT_RE, _ENV_ASSIGN_RE, _VALUE_FULL_RE,
)


def shape(value: bytes, limit: int = 12) -> str:
    """값을 드러내지 않는 형태 요약: 대문자열→A, 소문자열→a, 숫자열→9, 그 밖→*."""
    s = re.sub(rb"[A-Z]+", b"A", value)
    s = re.sub(rb"[a-z]+", b"a", s)
    s = re.sub(rb"[0-9]+", b"9", s)
    s = re.sub(rb"[^Aa9]+", b"*", s)
    return s.decode("ascii")[:limit]


class Allowlist:
    def __init__(self, data: dict, raw: bytes = b""):
        # 정책 버전에는 파일 바이트가 아니라 내용(정규화한 JSON)을 쓴다.
        # core.autocrlf 체크아웃처럼 개행만 바뀐 경우 정책이 달라지지 않게 하기 위해서다.
        self.raw = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        emails = data.get("emails", {})

        def _vals(items):
            return {(i if isinstance(i, str) else i["value"]).lower() for i in items}

        self.email_exact = _vals(emails.get("exact", []))
        self.email_domains = _vals(emails.get("domains", []))
        rnd = data.get("random_strings", {})
        self.random_patterns = [re.compile(p["pattern"].encode()) for p in rnd.get("patterns", [])]
        if rnd.get("json_keys"):
            # 키 이름만으로 면제하는 규칙은 지원하지 않는다(검토한 URL 문맥 url_contexts로 대체).
            raise ValueError("allowlist random_strings.json_keys is not supported; use url_contexts")
        self.url_contexts = list(rnd.get("url_contexts", []))
        kw = data.get("keywords", {})
        self.keyword_value_patterns = [re.compile(p["pattern"].encode()) for p in kw.get("value_patterns", [])]
        # 사람이 검토한 이미지·바이너리(대화 속 base64 이미지, 비텍스트 부속 파일)의 sha256
        self.image_hashes = {i["sha256"] for i in data.get("reviewed_binary", [])}

    def email_ok(self, email: str) -> bool:
        e = email.lower()
        domain = e.rsplit("@", 1)[-1]
        return e in self.email_exact or any(domain == d or domain.endswith("." + d) for d in self.email_domains)

    def random_ok(self, token: bytes, key, value=None) -> bool:
        """허용: 검토한 고정 패턴과 완전 일치, 또는 검토한 URL 문맥(키+scheme+host+경로 단계+쿼리 없음) 안의 경로 조각."""
        if any(p.fullmatch(token) for p in self.random_patterns):
            return True
        if key is None or value is None:
            return False
        k = key.decode("ascii", "replace")
        for c in self.url_contexts:
            if c.get("key") == k and _url_matches(value, c) and token in value.split(b"?", 1)[0]:
                return True
        return False

    def keyword_ok(self, value: bytes) -> bool:
        if value in _PLACEHOLDER_VALUES or any(p.fullmatch(value) for p in self.keyword_value_patterns):
            return True
        if MASK in value:
            # 완전히 가린 값만 허용한다. 일부만 가린 값은 남은 조각을 다시 후보로 본다.
            rest = [f for f in re.split(rb"\*{4,}", value) if f]
            return not any(_VALUE_FULL_RE.fullmatch(f) for f in rest)
        return False


def _url_matches(value: bytes, ctx: dict) -> bool:
    try:
        u = urlsplit(value.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return False
    if u.scheme != ctx.get("scheme", "https") or (u.hostname or "") != ctx.get("host"):
        return False
    if u.username or u.password or u.port:
        return False
    if not ctx.get("query", False) and (u.query or u.fragment or "?" in value.decode("utf-8", "replace")):
        return False
    segs = [x for x in u.path.split("/") if x]
    want = ctx.get("path_segments")
    return want is None or len(segs) == want


class Policy:
    """치환 목록(entries)과 허용 목록으로 만든 마스킹·검증기."""

    def __init__(self, entries: list[dict], allow: Allowlist):
        self.allow = allow
        literals: set[bytes] = set()
        self.home_users: list[re.Pattern] = []
        for e in entries:
            v = e.get("value")
            if not isinstance(v, str) or not v:
                continue
            if e.get("kind") == "home_user":
                self.home_users.append(_home_user_pattern(v))
            else:
                literals.update(_variants(v))
        self.literal_res = [re.compile(re.escape(v), re.IGNORECASE) for v in sorted(literals, key=len, reverse=True)]
        # 치환에 영향을 주는 (종류, 값)만으로 계산한다. 값 자체는 어디에도 기록하지 않는다.
        pairs = sorted({(e.get("kind") == "home_user", e.get("value")) for e in entries if isinstance(e.get("value"), str)})
        self.mask_digest = sha256(json.dumps(pairs, ensure_ascii=False).encode("utf-8"))

    @property
    def version(self) -> str:
        """정책 세대. 검출·마스킹 규칙(정규식 원문)·허용 목록·치환 목록이 바뀌면 달라진다.

        세대가 바뀌면 증분 검증 대신 전체 재검사하고, 스냅샷도 새 세대 폴더에 전체본을 만든다.
        """
        rules = b"|".join(rx.pattern for _, rx in TOKEN_PATTERNS) + b"|" + b"|".join(rx.pattern for rx in _DETECT_RULES)
        return sha256(
            POLICY_CODE_VERSION.encode() + b"|" + rules + b"|" + self.allow.raw + b"|" + self.mask_digest.encode()
        )[:12]

    # ---------------------------------------------------------------- masking
    def mask(self, buf: bytes) -> tuple[bytes, Counter]:
        counts: Counter = Counter()
        for rx in self.literal_res:
            buf, n = rx.subn(MASK, buf)
            counts["listed_value"] += n
        for rx in self.home_users:
            spans, starts = _opaque_spans(buf)
            hits = 0

            def repl_user(m, spans=spans, starts=starts):
                nonlocal hits
                if _inside(spans, starts, m.start()):
                    return m.group(0)
                hits += 1
                return MASK

            buf = rx.sub(repl_user, buf)
            counts["home_user"] += hits
        for name, rx in TOKEN_PATTERNS:
            spans = _spans(_STRUCT_FIELD_RE, buf)
            starts = [s for s, _ in spans]
            hits = 0

            def repl(m, spans=spans, starts=starts):
                nonlocal hits
                if _inside(spans, starts, m.start()):
                    return m.group(0)
                hits += 1
                return MASK

            buf = rx.sub(repl, buf)
            counts[name] += hits
        return buf, +counts

    def residue(self, buf: bytes) -> int:
        """마스킹 후에도 남은 치환 목록 값의 수(0이어야 정상)."""
        n = sum(len(rx.findall(buf)) for rx in self.literal_res)
        if self.home_users:
            spans, starts = _opaque_spans(buf)
            n += sum(1 for rx in self.home_users for m in rx.finditer(buf) if not _inside(spans, starts, m.start()))
        return n

    # -------------------------------------------------------------- detection
    def detect(self, buf: bytes) -> list[Hit]:
        hits: list[Hit] = []
        for lineno, line in enumerate(buf.splitlines(), 1):
            hits.extend(self._detect_line(line, lineno))
        return hits

    def _detect_line(self, line: bytes, lineno: int) -> list[Hit]:
        out: list[Hit] = []
        struct = _spans(_STRUCT_FIELD_RE, line) + _spans(_IMAGE_RE, line)
        struct.sort()
        starts = [s for s, _ in struct]

        for m in _EMAIL_RE.finditer(line):
            email = m.group(0)
            # JSON 이스케이프(\n 등)의 글자는 이메일 일부가 아니다: "\n@decorator" 같은 오탐 방지
            if m.start() > 0 and line[m.start() - 1:m.start()] == b"\\" and email[:1] in _JSON_ESCAPE_LETTERS:
                email = email[1:]
                if email.startswith(b"@"):
                    continue
            if not self.allow.email_ok(email.decode("ascii", "replace")):
                out.append(Hit("email", lineno, value=email))
        for name, rx in TOKEN_PATTERNS:
            for m in rx.finditer(line):
                if not _inside(struct, starts, m.start()):
                    out.append(Hit(f"token:{name}", lineno, value=m.group(0)))
        if _PRIVATE_KEY_HEADER_RE.search(line):
            out.append(Hit("private_key_header", lineno))
        for rx in (_KEYWORD_RE, _FLAG_RE):
            for m in rx.finditer(line):
                v = m.group("v")
                if not self.allow.keyword_ok(v):
                    out.append(Hit(f"keyword:{m.group(1).decode().lower()}", lineno, f"len={len(v)}", value=v))
        for m in _AUTH_RE.finditer(line):
            v = m.group("v")
            if not self.allow.keyword_ok(v):
                out.append(Hit("keyword:authorization", lineno, f"len={len(v)}", value=v))
        for m in _IMAGE_RE.finditer(line):
            if sha256(m.group(1)) not in self.allow.image_hashes:
                out.append(Hit("embedded_image", lineno, f"sha256={sha256(m.group(1))}"))
        for m in _RANDOM_RE.finditer(line):
            tok = _drop_json_escape(line, m.start(), m.group(0))
            if len(tok) < 32 or _inside(struct, starts, m.start()) or MASK in tok:
                continue
            if _HEXISH_RE.fullmatch(tok) or not _has_random_segment(tok):
                continue
            km = _KEY_BEFORE_RE.search(line, max(0, m.start() - 120), m.start())
            key = km.group(1) if km else None
            value = _string_value_at(line, km.end(1), m.end()) if km else None
            if self.allow.random_ok(tok, key, value):
                continue
            pm = _TYPE_PREFIX_RE.match(tok)
            prefix = pm.group(1).decode() if pm else "-"
            out.append(
                Hit(
                    "random_string",
                    lineno,
                    f"len={len(tok)} key={key.decode() if key else '-'} prefix={prefix}",
                    value=em.group(1) if (em := _ENV_ASSIGN_RE.fullmatch(tok)) else tok,
                )
            )
        return out


def load_allowlist(path: str) -> Allowlist:
    try:
        with open(path, "rb") as f:
            raw = f.read()
    except FileNotFoundError:
        return Allowlist({}, b"")
    return Allowlist(json.loads(raw.decode("utf-8")), raw)


def load_mask_entries(path: str) -> list[dict]:
    entries = []
    try:
        with open(path, "rb") as f:
            for line in f:
                line = line.strip()
                if line:
                    entries.append(json.loads(line.decode("utf-8")))
    except FileNotFoundError:
        pass
    return entries
