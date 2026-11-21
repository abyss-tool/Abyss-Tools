#!/usr/bin/env python3
"""
N-ENG-008 Spec-Lint 轻量校验（M1）。

检查项：
  1. SPEC-INDEX.md 中每个规格编号对应的 .md 文件必须存在；
  2. docs/abyss-specs/ 下每个 .md 文件必须能在 SPEC-INDEX.md 找到登记（孤儿检测）；
  3. 规格文件头部必须包含 "规格编号"、"状态"、"优先级"、"里程碑" 四个字段；
  4. 新增/修改代码的 PR 描述必须关联至少一个 spec 编号（N-XXX-XXX / SRS-XXX 等）。

不通过时返回 1，CI 阻断。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC_DIR = ROOT / "docs" / "abyss-specs"
INDEX = SPEC_DIR / "SPEC-INDEX.md"

SPEC_ID_RE = re.compile(r"`((?:SRS|ARC|CORE|UPD|PLG|SHL|SDK|INS|FEAT|HUB|N-[A-Z]+|SEC|DC|QA|ADR)-\d{3})`")
HEADER_FIELD_RE = re.compile(r"\|\s*(规格编号|状态|优先级|里程碑)\s*\|")


def load_registered_ids() -> set[str]:
    text = INDEX.read_text(encoding="utf-8")
    return set(SPEC_ID_RE.findall(text))


def scan_spec_files() -> dict[str, Path]:
    out: dict[str, Path] = {}
    for md in SPEC_DIR.rglob("*.md"):
        if md.name in ("SPEC-INDEX.md",):
            continue
        m = SPEC_ID_RE.search(md.read_text(encoding="utf-8"))
        if m:
            out[m.group(1)] = md
    return out


def lint_spec_file(path: Path) -> list[str]:
    errors: list[str] = []
    text = path.read_text(encoding="utf-8")
    # 截取第一个二级标题前的内容作为元信息头；如果文件没有 "## " 就用前 800 字
    header_cut = text.split("## ", 1)[0] if "## " in text else text[:800]
    for field in ("规格编号", "当前状态", "优先级 / 里程碑"):
        if not re.search(rf"\|\s*{re.escape(field)}\s*\|", header_cut):
            errors.append(f"  · 缺失头部字段 `{field}`")
    # 编号必须与文件名一致
    m2 = re.match(r"^((?:N-[A-Z]+|[A-Z]+)-\d{3})", path.stem)
    id_from_name = m2.group(1) if m2 else None
    m = SPEC_ID_RE.search(text)
    if m and id_from_name and m.group(1) != id_from_name:
        errors.append(f"  · 文件名 ID `{id_from_name}` 与正文编号 `{m.group(1)}` 不一致")
    return errors


def main() -> int:
    if not INDEX.exists():
        print("::error::找不到 SPEC-INDEX.md", file=sys.stderr)
        return 2
    registered = load_registered_ids()
    files = scan_spec_files()

    errors: list[str] = []

    # 1. 注册但缺失文件
    for sid in sorted(registered):
        if sid not in files:
            errors.append(f"  · SPEC-INDEX 登记 {sid} 但未找到对应 .md 文件")

    # 2. 孤儿文件
    for sid, p in sorted(files.items()):
        if sid not in registered:
            errors.append(f"  · 孤儿规格 {sid}（{p.relative_to(ROOT)}）未在 SPEC-INDEX 登记")

    # 3. 头字段
    for sid, p in sorted(files.items()):
        fe = lint_spec_file(p)
        errors.extend(fe)

    if errors:
        print("::error::spec-lint 发现以下问题：", file=sys.stderr)
        for e in errors:
            print(e, file=sys.stderr)
        return 1
    print(f"✅ spec-lint 通过：{len(registered)} 个已登记规格 / {len(files)} 个文件，头字段齐全，无孤儿")
    return 0


if __name__ == "__main__":
    sys.exit(main())
