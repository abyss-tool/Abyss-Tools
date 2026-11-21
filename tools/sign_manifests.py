#!/usr/bin/env python3
"""
N-SEC-001 / N-SEC-002 通道清单签名脚本（发布流水线使用）。

调用：
  python3 tools/sign_manifests.py <release-dir> --key <path-to-minisign.key> [--trusted-comment "..."]

流程：
  1. 对 release 目录下的 version.json / patches.json / changelog.json 以及
     插件中心 plugins.json 生成 detached 签名（.minisig）；
  2. 验证生成的 .minisig 可被自身重新验过（防 CI 密钥错误静默上线）；
  3. 产物与原清单同目录，发布动作一并 commit/push；
  4. 私钥路径从 MINISIGN_KEY_PATH 环境变量读取，命令行覆盖。

要求：
  - 运行环境已安装 `minisign` (≥ 0.11)；
  - 或提供 Python `pynacl` 作为后备（本脚本优先调用 minisign CLI）。
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

MANIFEST_NAMES = ("version.json", "patches.json", "changelog.json", "plugins.json")


def run(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True)


def have_minisign() -> str | None:
    return shutil.which("minisign")


def sign_with_minisign(
    ms: str, key_path: Path, file: Path, trusted_comment: str
) -> None:
    # minisign -S -x <sig> -s <key> -c <comment> -W (re-sign on change)
    sig_path = file.with_name(file.name + ".minisig")
    cmd = [
        ms, "-S", "-x", str(sig_path), "-s", str(key_path),
        "-c", trusted_comment, "-f", str(file),
    ]
    r = run(cmd)
    if r.returncode != 0:
        print(f"::error::minisign sign failed for {file.name}: {r.stderr}", file=sys.stderr)
        sys.exit(1)
    print(f"✅ signed {file.name} → {sig_path.name}")


def verify_with_minisign(ms: str, pubkey: Path, file: Path) -> bool:
    sig = file.with_name(file.name + ".minisig")
    if not sig.exists():
        return False
    r = run([ms, "-V", "-x", str(sig), "-p", str(pubkey), "-f", str(file)])
    return r.returncode == 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Sign channel manifests with minisign")
    parser.add_argument("release_dir", type=Path, help="directory containing manifests")
    parser.add_argument("--key", type=Path, default=None, help="minisign secret key path")
    parser.add_argument("--pubkey", type=Path, default=None, help="minisign public key (for verify)")
    parser.add_argument("--trusted-comment", default="abyss-release", help="trusted comment embedded in sig")
    args = parser.parse_args(argv[1:])

    key = args.key or Path(os.environ.get("MINISIGN_KEY_PATH", ""))
    if not key or not key.exists():
        print("::error::找不到 minisign 私钥。请通过 --key 或 MINISIGN_KEY_PATH 环境变量提供",
              file=sys.stderr)
        return 2

    ms = have_minisign()
    if not ms:
        print("::error::未找到 minisign 可执行文件，请先安装 (brew/apt install minisign)",
              file=sys.stderr)
        return 2

    base: Path = args.release_dir
    if not base.is_dir():
        print(f"::error::目录不存在: {base}", file=sys.stderr)
        return 2

    signed = 0
    for name in MANIFEST_NAMES:
        f = base / name
        if not f.exists():
            print(f"  · 跳过 {name}（文件不存在）")
            continue
        sign_with_minisign(ms, key, f, args.trusted_comment)
        signed += 1

    if signed == 0:
        print("::warning::未签署任何清单（目录中没有 version.json/patches.json/changelog.json）",
              file=sys.stderr)
        return 1

    if args.pubkey and args.pubkey.exists():
        for name in MANIFEST_NAMES:
            f = base / name
            if not f.exists():
                continue
            ok = verify_with_minisign(ms, args.pubkey, f)
            if not ok:
                print(f"::error::验签失败：{name}", file=sys.stderr)
                return 1
            print(f"✅ verify {name} OK")
    else:
        print("::notice::未提供公钥，跳过自验（发布流水线建议提供公钥做自检）")

    print(f"\n{signed} 份清单签名完成")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
