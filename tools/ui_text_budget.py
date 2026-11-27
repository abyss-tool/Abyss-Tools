#!/usr/bin/env python3
"""UI-001 功能文案精简门禁（ui_text_budget）。

扫描 feature/*/src/main 与 core/designsystem/src/main 的 .kt 文件，揪出
「装饰性长文案」字符串字面量并拒绝其入仓：

  违约判据（同时满足）：
    1. 字面量字符数 >= 18（CJK 计 1 字符）；
    2. 字面量包含长文本标点之一：。；——（句号 / 分号 / 破折号 ——
       双字符强调即一个 U+2014）。

  豁免通道（二选一）：
    a. 行尾注释标记：`// keep-text: <理由>` —— 逐行豁免；若该行是
       `+` 拼接长文案的首行，豁免沿拼接块传播到后续段。
    b. 白名单文件 tools/ui_text_budget_whitelist.txt：每行一个
       `<sha256(literal)>  # <类别注释>`，用于不便改行的历史文案。

白名单类别纪律（见 UI-001 规格）：错误 / 授权引导 / 高危确认 / 免责
声明 / 数值单位 / 按钮动作 / 输入 label / 空态一句 / 结果反馈。说明卡
/ 教程段 / helper 长段必须删除，不得入白名单。

用法：python3 tools/ui_text_budget.py（违约未豁免时 exit 1）
"""

import hashlib
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
WHITELIST_FILE = Path(__file__).resolve().parent / "ui_text_budget_whitelist.txt"

MIN_CHARS = 18
LONG_TEXT_PUNCT = "。；—"  # 句号 / 分号 / 破折号
KEEP_TEXT_MARKER = "keep-text:"

SCAN_GLOBS = [
    "feature/*/src/main/**/*.kt",
    "core/designsystem/src/main/**/*.kt",
]

# 字面量抽取：普通字符串（含转义与 ${} 插值）；原始字符串 """ 块整段跳过。
STRING_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')


def load_whitelist(path: Path) -> dict:
    """解析白名单：{sha256hex: 注释}。"""
    entries = {}
    if not path.exists():
        return entries
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("#", 1)
        digest = parts[0].strip()
        note = parts[1].strip() if len(parts) > 1 else ""
        if digest:
            entries[digest] = note
    return entries


def strip_block_comments(text: str) -> str:
    """移除 /* ... */ 块注释（保守实现：字符串内的 /* 不动）。"""
    out = []
    in_string = False
    in_block = False
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if in_block:
            if c == "*" and i + 1 < n and text[i + 1] == "/":
                in_block = False
                i += 2
                continue
            i += 1
            continue
        if in_string:
            out.append(c)
            if c == "\\":
                if i + 1 < n:
                    out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if c == '"':
            in_string = True
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            in_block = True
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def scan_file(path: Path):
    """返回 [(line_no, literal, in_keep_text_block)] 列表。

    keep-text 豁免以「语句」为粒度：注释所在行激活；只要语句尚未收尾
    （行尾 "+" 拼接，或仍处于激活时的括号深度内）就沿续行传播。
    """
    text = path.read_text(encoding="utf-8", errors="replace")
    text = strip_block_comments(text)
    raw_lines = text.split("\n")

    findings = []
    keep_active = False  # keep-text 豁免态（沿拼接 / 括号深度传播）
    active_depth = 0  # 激活时的括号深度
    depth = 0  # 当前括号深度（仅统计圆括号，字符串外）
    for lineno, line in enumerate(raw_lines, start=1):
        # 原始字符串 """ 整行跳过（本仓 UI 层不用 raw string，防御性处理）
        if '"""' in line:
            keep_active = False
            continue

        # 行注释切分：字符串之外首个 "//" 之后的内容作为注释区
        code_part, comment_part = split_line_comment(line)
        has_keep_text = KEEP_TEXT_MARKER in comment_part

        depth_delta = paren_delta(code_part)
        literals = STRING_RE.findall(code_part)

        if not literals:
            if has_keep_text:
                # keep-text 注释独占一行：激活豁免，作用于后续字面量语句
                keep_active = True
                active_depth = depth
            depth += depth_delta
            if depth < active_depth:
                keep_active = False
            continue

        exempt_block = keep_active or has_keep_text
        if has_keep_text and not keep_active:
            active_depth = depth
        for literal in literals:
            findings.append((lineno, literal, exempt_block))

        # 拼接 / 括号深度传播：语句未收尾（行尾 +，或仍深于激活层）则延续
        stripped = code_part.rstrip()
        depth += depth_delta
        if exempt_block and (stripped.endswith("+") or depth > active_depth):
            keep_active = True
        else:
            keep_active = False
    return findings


def paren_delta(code: str) -> int:
    """一行代码的圆括号深度增量（字符串字面量内不计）。"""
    delta = 0
    in_string = False
    i, n = 0, len(code)
    while i < n:
        c = code[i]
        if in_string:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_string = False
        else:
            if c == '"':
                in_string = True
            elif c == "(":
                delta += 1
            elif c == ")":
                delta -= 1
        i += 1
    return delta


def split_line_comment(line: str):
    """把一行拆成 (代码区, 注释区)：字符串外的 // 视为注释起点。"""
    in_string = False
    i, n = 0, len(line)
    while i < n:
        c = line[i]
        if in_string:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if c == '"':
            in_string = True
            i += 1
            continue
        if c == "/" and i + 1 < n and line[i + 1] == "/":
            return line[:i], line[i + 2:]
        i += 1
    return line, ""


def is_violation(literal: str) -> bool:
    if len(literal) < MIN_CHARS:
        return False
    return any(ch in literal for ch in LONG_TEXT_PUNCT)


def main() -> int:
    whitelist = load_whitelist(WHITELIST_FILE)
    files = []
    for pattern in SCAN_GLOBS:
        files.extend(sorted(REPO_ROOT.glob(pattern)))
    if not files:
        print("ui_text_budget: 未找到扫描目标（目录结构漂移？）", file=sys.stderr)
        return 2

    violations = []
    for path in files:
        rel = path.relative_to(REPO_ROOT)
        for lineno, literal, keep_text in scan_file(path):
            if not is_violation(literal):
                continue
            digest = hashlib.sha256(literal.encode("utf-8")).hexdigest()
            if keep_text:
                continue  # 豁免通道 a：行内 keep-text 标记
            if digest in whitelist:
                continue  # 豁免通道 b：白名单哈希
            violations.append((str(rel), lineno, literal, digest))

    if violations:
        print(f"UI-001 文案预算违约 {len(violations)} 处（删除文案，或仅错误/授权/"
              "高危确认/免责声明类加 keep-text 注释）：")
        for rel, lineno, literal, digest in violations:
            preview = literal[:40] + ("…" if len(literal) > 40 else "")
            print(f"  {rel}:{lineno}  {preview}")
        print("提示：装饰性长段必须删除；白名单只收 错误/授权引导/高危确认/免责"
              "声明/数值单位/按钮动作/输入label/空态一句/结果反馈。")
        print("哈希（如需入白名单：sha256 + 理由）：")
        for rel, lineno, literal, digest in violations:
            print(f"  {digest}  # {rel}:{lineno}")
        return 1

    print(f"UI-001 文案预算通过：{len(files)} 个文件扫描，0 处未豁免长文案。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
