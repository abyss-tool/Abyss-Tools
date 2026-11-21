#!/usr/bin/env python3
"""从 Kotlin 源提取通道签名公钥（N-SEC-001）。

公钥唯一事实源 = `core/common/.../security/ChannelSignatureVerifier.kt`
的 `TRUSTED_KEYS`。本脚本解析该文件输出公钥文本（untrusted comment + base64
blob 两行），供发布流水线回读自验与清单门禁使用 —— 禁止在 workflow /
其他脚本里手工复制公钥（防漂移）。

用法：python3 tools/publisher_pubkey.py [--index 0]
输出：公钥文本写入 stdout。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

KT = Path(__file__).resolve().parent.parent / (
    "core/common/src/main/kotlin/com/abyss/tools/core/common/security/ChannelSignatureVerifier.kt"
)


def extract(index: int = 0) -> str:
    if not KT.exists():
        print(f"::error::找不到 ChannelSignatureVerifier.kt: {KT}", file=sys.stderr)
        sys.exit(2)
    text = KT.read_text(encoding="utf-8")
    # TRUSTED_KEYS = listOf( """ ... """.trimIndent(), ... ) —— 逐个 raw string 段
    m = re.search(r"TRUSTED_KEYS[^=]*=\s*listOf\((.*?)\n\s*\)", text, re.S)
    if not m:
        print("::error::TRUSTED_KEYS 段解析失败", file=sys.stderr)
        sys.exit(2)
    blocks = re.findall(r'"""(.*?)"""', m.group(1), re.S)
    if len(blocks) <= index:
        print(f"::error::TRUSTED_KEYS 只有 {len(blocks)} 把公钥，index={index} 越界", file=sys.stderr)
        sys.exit(2)
    lines = [ln.strip() for ln in blocks[index].strip().splitlines() if ln.strip()]
    if len(lines) != 2 or not lines[0].startswith("untrusted comment:"):
        print("::error::公钥块格式非法（应为 untrusted comment + base64 两行）", file=sys.stderr)
        sys.exit(2)
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--index", type=int, default=0, help="TRUSTED_KEYS 中第几把（0 起）")
    print(extract(ap.parse_args().index), end="")
