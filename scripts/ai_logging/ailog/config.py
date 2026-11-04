"""저장소 로깅 설정(config/ai-logging.json)과 브랜치 → 작업 ID 규칙."""
from __future__ import annotations

import json
import re

from .common import Context, read_json_ro

CONFIG_REL = "config/ai-logging.json"
DEFAULTS = {
    "service": "AI",
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
    return cfg


def feature_id(cfg: dict, branch: str | None):
    if not branch or not branch.startswith(cfg["feature_prefix"]):
        return None
    fid = branch[len(cfg["feature_prefix"]):]
    return fid if _FEATURE_ID_RE.match(fid) else None


def task_for_branch(ctx: Context, branch: str | None = None):
    """feature/LOG-001 → AI-LOG-001. feature 브랜치가 아니면 None."""
    from . import gitops

    cfg = load(ctx)
    fid = feature_id(cfg, branch or gitops.current_branch(ctx.root))
    return f"{cfg['service']}-{fid}" if fid else None


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
