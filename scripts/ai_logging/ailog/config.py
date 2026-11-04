"""저장소 로깅 설정(config/ai-logging.json)과 브랜치 → 작업 ID 규칙.

작업 ID: feature ID가 task_prefixes의 접두어로 시작하면 그 서비스 이름을 앞에 붙이고(feature/LOG-001 → AI-LOG-001),
아니면 service(비어 있으면 feature ID 그대로)를 쓴다(feature/SETUP-001 → SETUP-001).
"""
from __future__ import annotations

import json
import re

from .common import Context, read_json_ro

CONFIG_REL = "config/ai-logging.json"
DEFAULTS = {
    # 모든 feature에 붙일 서비스 이름. 비어 있으면 feature ID를 작업 ID로 그대로 쓴다.
    "service": "",
    # feature ID 접두어별 서비스 이름. AI 로깅 도구 작업(LOG-xxx)은 AI-LOG-xxx로 기록한다.
    "task_prefixes": {"LOG-": "AI"},
    "main": "master",
    "develop": "develop",
    "remote": "origin",
    "feature_prefix": "feature/",
    # master에 로깅 설정이 아직 없을 때(최초 도입) 예외로 허용하는 feature
    "bootstrap_feature": "feature/LOG-001",
}
_FEATURE_ID_RE = re.compile(r"^[A-Z0-9]+(?:-[A-Z0-9]+)*$")


def load(ctx: Context) -> dict:
    data, status = read_json_ro(ctx.config_path)
    cfg = dict(DEFAULTS)
    if status == "ok" and isinstance(data, dict):
        cfg.update({k: v for k, v in data.items() if k in DEFAULTS and isinstance(v, str)})
        tp = data.get("task_prefixes")
        if isinstance(tp, dict) and all(isinstance(k, str) and isinstance(v, str) for k, v in tp.items()):
            cfg["task_prefixes"] = dict(tp)
    return cfg


def feature_id(cfg: dict, branch: str | None):
    if not branch or not branch.startswith(cfg["feature_prefix"]):
        return None
    fid = branch[len(cfg["feature_prefix"]):]
    return fid if _FEATURE_ID_RE.match(fid) else None


def task_id(cfg: dict, fid: str) -> str:
    """feature ID → 커밋 제목의 작업 ID. 가장 긴 접두어가 우선한다."""
    for prefix in sorted(cfg.get("task_prefixes") or {}, key=len, reverse=True):
        if prefix and fid.startswith(prefix):
            return f"{cfg['task_prefixes'][prefix]}-{fid}"
    return f"{cfg['service']}-{fid}" if cfg.get("service") else fid


def task_for_branch(ctx: Context, branch: str | None = None):
    """feature/LOG-001 → AI-LOG-001, feature/SETUP-001 → SETUP-001. feature 브랜치가 아니면 None."""
    from . import gitops

    cfg = load(ctx)
    fid = feature_id(cfg, branch or gitops.current_branch(ctx.root))
    return task_id(cfg, fid) if fid else None


def check_branch_task(ctx: Context, task: str | None, branch: str | None) -> str | None:
    """feature 브랜치의 커밋은 그 브랜치의 작업 ID여야 한다. 위반이면 문제 문자열, 아니면 None.

    develop·master 등 feature가 아닌 브랜치는 여러 feature의 병합이 들어오므로 검사하지 않는다.
    """
    want = task_for_branch(ctx, branch) if branch else None
    if want is None or task == want:
        return None
    return f"task_branch_mismatch:{task or '-'}!={want}"


def has_config_in(root: str, treeish: str) -> bool:
    """커밋 트리에 로깅 설정이 있는지(master 확인용)."""
    from . import gitops

    files = gitops.tree_files(root, treeish, CONFIG_REL)
    return CONFIG_REL in files


def config_in(root: str, treeish: str) -> dict | None:
    from . import gitops

    files = gitops.tree_files(root, treeish, CONFIG_REL)
    if CONFIG_REL not in files:
        return None
    blob = gitops.cat_blobs(root, [files[CONFIG_REL][1]]).get(files[CONFIG_REL][1])
    try:
        return json.loads(blob.decode("utf-8"))
    except (AttributeError, ValueError, UnicodeDecodeError):
        return None
