#!/usr/bin/env python3
"""
N-SEC-001 / N-SEC-002 通道清单签名脚本（发布流水线使用）。

调用（主仓发布流水线，发布仓三件套）：
  python3 tools/sign_manifests.py <release-dir> \
      --seed-file <seed.txt> --pubkey-file <pubkey.txt> [--trusted-comment "..."]

调用（插件仓补丁通道 / 插件中心索引，任意清单名）：
  python3 tools/sign_manifests.py <dir> --files index.json --seed-file ... --pubkey-file ...

流程：
  1. 对目录下清单（默认 version.json / patches.json / changelog.json /
     plugins.json；--files 覆盖）生成 detached 签名（.minisig）；
  2. 回读验证每个 .minisig 可被公钥验过（防 CI 密钥错误静默上线）；
  3. 产物与原清单同目录，发布动作一并 commit/push；
  4. 私钥输入：--seed-file（raw Ed25519 seed base64 单行，CI secret
     RELEASE_SIGNING_KEY 原文写盘），或 --key（minisign CLI 私钥，需 CLI）。

实现路径：优先 minisign CLI（若可用且提供 CLI 私钥）；否则走
`minisign_py`（pynacl，minisign 兼容文本格式，与 Kotlin MinisignVerifier
契约一致）。两种路径产出的签名均可被客户端与 CLI 双向验证。
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import minisign_py  # noqa: E402

MANIFEST_NAMES = ("version.json", "patches.json", "changelog.json", "plugins.json")


def run(cmd: list[str]) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True)


def sign_file(args, file: Path) -> None:
    """对单个清单文件产出 .minisig（CLI 优先，pynacl 后备；失败即终止）。"""
    sig_path = file.with_name(file.name + ".minisig")
    ms = shutil.which("minisign")
    if ms and args.key:
        r = run([ms, "-S", "-x", str(sig_path), "-s", str(args.key),
                 "-c", args.trusted_comment, "-f", str(file)])
        if r.returncode != 0:
            print(f"::error::minisign sign failed for {file.name}: {r.stderr}", file=sys.stderr)
            sys.exit(1)
    elif args.seed_file:
        seed_text = args.seed_file.read_text(encoding="utf-8")
        pubkey_text = args.pubkey_file.read_text(encoding="utf-8")
        try:
            seed, keynum, _ = minisign_py.load_seed(seed_text, pubkey_text)
        except minisign_py.MinisignError as e:
            print(f"::error::{e}", file=sys.stderr)
            sys.exit(1)
        sig = minisign_py.sign_message(
            seed, keynum, file.read_bytes(),
            "signature from abyss release key", args.trusted_comment,
        )
        sig_path.write_text(sig, encoding="ascii")
    else:
        print("::error::需要 --seed-file（pynacl 路径）或 --key（minisign CLI 路径）",
              file=sys.stderr)
        sys.exit(2)
    print(f"✅ signed {file.name} → {sig_path.name}")


def verify_file(pubkey_text: str, file: Path) -> bool:
    sig = file.with_name(file.name + ".minisig")
    if not sig.exists():
        return False
    return minisign_py.verify(pubkey_text, sig.read_text(encoding="utf-8"), file.read_bytes())


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Sign channel manifests (N-SEC-001)")
    parser.add_argument("release_dir", type=Path, help="directory containing manifests")
    parser.add_argument("--files", default=None,
                        help="逗号分隔清单名（缺省 version/patches/changelog/plugins.json）")
    parser.add_argument("--seed-file", type=Path, default=None,
                        help="raw Ed25519 seed（base64 单行；CI secret RELEASE_SIGNING_KEY 原文）")
    parser.add_argument("--pubkey-file", type=Path, default=None,
                        help="minisign 公钥文本（与客户端 TRUSTED_KEYS 一致；自验必需）")
    parser.add_argument("--key", type=Path, default=None,
                        help="minisign CLI 私钥（可选路径；缺省读 MINISIGN_KEY_PATH）")
    parser.add_argument("--trusted-comment", default="abyss-release")
    args = parser.parse_args(argv[1:])

    names = tuple(s.strip() for s in args.files.split(",")) if args.files else MANIFEST_NAMES
    key = args.key or (
        Path(os.environ["MINISIGN_KEY_PATH"]) if os.environ.get("MINISIGN_KEY_PATH") else None)

    if not args.seed_file and not key:
        print("::error::缺少私钥输入：--seed-file 或 --key / MINISIGN_KEY_PATH（fail-closed，不静默跳过）",
              file=sys.stderr)
        return 2
    if not args.seed_file and key and not key.exists():
        print(f"::error::minisign 私钥不存在：{key}", file=sys.stderr)
        return 2

    base: Path = args.release_dir
    if not base.is_dir():
        print(f"::error::目录不存在: {base}", file=sys.stderr)
        return 2

    signed = 0
    for name in names:
        f = base / name
        if not f.exists():
            print(f"  · 跳过 {name}（文件不存在）")
            continue
        sign_file(args, f)
        signed += 1

    if signed == 0:
        print(f"::warning::未签署任何清单（目录中没有 {', '.join(names)}）", file=sys.stderr)
        return 1

    if args.pubkey_file and args.pubkey_file.exists():
        pubkey_text = args.pubkey_file.read_text(encoding="utf-8")
        for name in names:
            f = base / name
            if not f.exists():
                continue
            if not verify_file(pubkey_text, f):
                print(f"::error::回读验签失败：{name} —— 拒绝发布（fail-closed）", file=sys.stderr)
                return 1
            print(f"✅ verify {name} OK")
    else:
        print("::error::未提供 --pubkey-file，无法回读自验 —— fail-closed 拒绝", file=sys.stderr)
        return 2

    print(f"\n{signed} 份清单签名完成（含回读自验）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
