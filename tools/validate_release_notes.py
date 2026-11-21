#!/usr/bin/env python3
"""
N-ENG-010 发布说明质量门禁（M1, P0）。

规约（对应规格 N-ENG-010）：
  1. version.json 的 notes 字段不得直接等于 PR 标题（如 "Merge PR #4: ..."）；
  2. notes 至少 3 条要点（以 - / * / 数字. 开头），非空、非纯空白；
  3. breaking change 必须出现在首条要点并显著标注（BREAKING / ⚠ / 不兼容 /
     升级注意）；
  4. notes 长度必须在 20 ~ 4000 字符之间；
  5. notes 不得包含 GitHub 自动生成的合并消息关键词
     （"Merge pull request" / "Merge branch" / "auto-merge"）。

返回码：
  0  → 合格
  1  → 不合格（门禁失败，CI 拒绝发布）
  2  → 文件无法读取 / JSON 解析错误

使用：
  python3 tools/validate_release_notes.py Abyss-Tools-Release/version.json
  python3 tools/validate_release_notes.py Abyss-Tools-Release/changelog.json --entry-latest
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


PR_TITLE_PATTERNS = [
    re.compile(r"^Merge (PR|pull request) #\d+", re.IGNORECASE),
    re.compile(r"^Merge branch ", re.IGNORECASE),
    re.compile(r"^auto-merge", re.IGNORECASE),
    re.compile(r"^chore\(release\):", re.IGNORECASE),
    re.compile(r"^bump v?\d", re.IGNORECASE),
]

BREAKING_KEYWORDS = ("BREAKING", "不兼容", "⚠", "升级注意", "breaking change")
BULLET_PREFIX = re.compile(r"^\s*(?:[-*]|\d+[.)])\s+")


def fail(msg: str) -> None:
    print(f"::error::{msg}", file=sys.stderr)
    sys.exit(1)


def load_notes_from_version_json(path: Path) -> str:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"::error::无法解析 {path}: {e}", file=sys.stderr)
        sys.exit(2)
    notes = data.get("notes") or ""
    version = data.get("versionName") or data.get("tag") or "?"
    return notes, version


def load_latest_from_changelog(path: Path) -> tuple[str, str]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"::error::无法解析 {path}: {e}", file=sys.stderr)
        sys.exit(2)
    history = data.get("history") or data.get("versions") or []
    if not history:
        fail("changelog history 为空")
    latest = history[0] if isinstance(history, list) else next(iter(history.values()))
    notes = latest.get("notes") or ""
    version = latest.get("versionName") or latest.get("tag") or "?"
    return notes, version


def count_bullets(text: str) -> int:
    count = 0
    for line in text.splitlines():
        if BULLET_PREFIX.match(line):
            count += 1
    return count


def first_bullet(text: str) -> str:
    for line in text.splitlines():
        if BULLET_PREFIX.match(line):
            return BULLET_PREFIX.sub("", line).strip()
    return text.strip()


def validate(notes: str, version: str) -> None:
    n = notes.strip()
    if not n:
        fail(f"v{version} 发布说明为空：notes 字段必须填写，不允许 PR 标题自动填充")

    if len(n) < 20:
        fail(f"v{version} 发布说明过短（{len(n)} 字符）：至少 20 字符，必须说明用户影响")
    if len(n) > 4000:
        fail(f"v{version} 发布说明过长（{len(n)} 字符）：超过 4000 字符，请精简")

    for pat in PR_TITLE_PATTERNS:
        if pat.search(n.splitlines()[0]):
            fail(
                f"v{version} 发布说明疑似直接使用 PR/合并提交标题："
                f"'{n.splitlines()[0][:80]}'。N-ENG-010 要求至少 3 条要点，禁止"
                f"用自动生成的合并信息作 notes。"
            )

    bullets = count_bullets(n)
    if bullets < 3:
        fail(
            f"v{version} 发布说明仅识别到 {bullets} 条要点（要求 ≥ 3）。"
            f"请以 '- ' 或数字编号列出用户可感知的变更。"
        )

    # breaking change 检查：若 notes 包含 BREAKING 关键词，则必须出现在第一条要点
    lower = n.lower()
    has_breaking = any(k.lower() in lower for k in BREAKING_KEYWORDS)
    if has_breaking:
        first = first_bullet(n)
        if not any(k.lower() in first.lower() for k in BREAKING_KEYWORDS):
            fail(
                f"v{version} 发布说明包含 breaking change 相关关键词，"
                f"但未放在首条要点显著标注。N-ENG-010 要求 breaking 内容"
                f"必须是第一条并明确提示。"
            )

    # 不允许占位符
    placeholders = ("TODO", "TBD", "xxx", "待补充")
    for p in placeholders:
        if p in n:
            fail(f"v{version} 发布说明包含占位符 '{p}'，发布前必须填写完整")

    print(f"✅ v{version} 发布说明质量门禁通过：{len(n)} 字符 / {bullets} 条要点")


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    path = Path(argv[1])
    if not path.exists():
        fail(f"文件不存在: {path}")
    if "--entry-latest" in argv or path.name == "changelog.json":
        notes, version = load_latest_from_changelog(path)
    else:
        notes, version = load_notes_from_version_json(path)
    validate(notes, version)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
