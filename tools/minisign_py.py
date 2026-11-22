#!/usr/bin/env python3
"""minisign 兼容签名/验签的 Python 实现（pynacl Ed25519 后备，N-SEC-001）。

唯一事实源：本文件（主仓 `Abyss-Tools/tools/minisign_py.py`）。发布仓 /
插件中心 / 插件仓按同构复制；任何格式变更必须与 Kotlin 侧
`MinisignVerifier`（`core/common/.../crypto/MinisignVerifier.kt`）三处同步。

格式契约（minisign 0.11 约定，与 Kotlin MinisignVerifier 一一对应）：

  签名文件（4 行，兼容 minisign CLI -V）：
    untrusted comment: <comment>
    base64( sig_blob )          # sig_blob = "Ed"(0x45 0x64) || keynum(8) || sig(64)，共 74 字节
    trusted comment: <comment>
    base64( global_blob )       # global_blob = "Ed" || keynum(8) || sig_over(blob||trusted_comment)(64)

  公钥文本（与客户端 TRUSTED_KEYS 内置常量一致）：
    untrusted comment: <comment>
    base64( key_blob )          # key_blob = "Ed" || keynum(8) || ed25519_pub(32)，共 42 字节

  客户端（MinisignVerifier.parseSignature）只消费第 2 行（对消息原文的
  detached Ed25519 签名）；第 4 行 global signature 保证 minisign CLI
  可独立复核。keynum 统一取自配对公钥的 blob —— 保证 keyId 恒一致，
  不依赖派生规则。

私钥输入（CI secret `RELEASE_SIGNING_KEY`，原文写盘）：
  raw Ed25519 seed（32 字节）的 base64 单行文本。签名前强制校验
  seed 派生公钥 == 公钥 blob 内嵌公钥（fail-closed，防 seed/公钥错配）。
"""

from __future__ import annotations

import base64
import hashlib
import sys
from pathlib import Path

try:
    import nacl.signing
except ImportError:  # pragma: no cover - CI 与本机均显式安装
    nacl = None  # type: ignore[assignment]

MAGIC = b"Ed"  # 原文签名（"ED" 为预哈希分支，本通道不用）
KEYNUM_LEN = 8
PUB_LEN = 32
SIG_LEN = 64
KEY_BLOB_LEN = len(MAGIC) + KEYNUM_LEN + PUB_LEN  # 42
SIG_BLOB_LEN = len(MAGIC) + KEYNUM_LEN + SIG_LEN  # 74


class MinisignError(Exception):
    """签名/验签失败（fail-closed 入口，调用方原样上抛）。"""


def _b64e(raw: bytes) -> str:
    return base64.b64encode(raw).decode("ascii")


def parse_pubkey(pubkey_text: str) -> tuple[bytes, bytes]:
    """解析公钥文本 → (keynum, pub32)。格式非法 / blob 长度错 → MinisignError。"""
    for line in pubkey_text.splitlines():
        line = line.strip()
        if not line or line.lower().startswith("untrusted comment:"):
            continue
        try:
            blob = base64.b64decode(line, validate=True)
        except Exception as e:
            raise MinisignError(f"公钥 base64 非法: {e}") from e
        if len(blob) != KEY_BLOB_LEN or not blob.startswith(MAGIC):
            raise MinisignError(f"公钥 blob 长度/magic 非法（{len(blob)} 字节）")
        return blob[len(MAGIC):len(MAGIC) + KEYNUM_LEN], blob[-PUB_LEN:]
    raise MinisignError("公钥文本无有效行")


def load_seed(seed_text: str, pubkey_text: str) -> tuple[bytes, bytes, bytes]:
    """校验 seed 与公钥配对 → (seed32, keynum, pub32)。错配即拒绝。"""
    if nacl is None:
        raise MinisignError("缺少 pynacl（pip install pynacl）")
    seed = base64.b64decode(seed_text.strip(), validate=True)
    if len(seed) != PUB_LEN:
        raise MinisignError(f"seed 应为 32 字节 base64（当前 {len(seed)}）")
    keynum, pub = parse_pubkey(pubkey_text)
    derived = bytes(nacl.signing.SigningKey(seed).verify_key.encode())
    if derived != pub:
        raise MinisignError("seed 与公钥不配对（派生公钥 ≠ 公钥 blob 内嵌公钥）——拒绝签名")
    return seed, keynum, pub


def sign_message(seed: bytes, keynum: bytes, message: bytes,
                 untrusted_comment: str, trusted_comment: str) -> str:
    """产出 minisign 兼容 4 行签名文本（keynum 取自配对公钥）。"""
    if nacl is None:
        raise MinisignError("缺少 pynacl（pip install pynacl）")
    signing = nacl.signing.SigningKey(seed)
    sig = bytes(signing.sign(message).signature)
    sig_blob = MAGIC + keynum + sig
    tc_bytes = trusted_comment.encode("utf-8")
    global_sig = bytes(signing.sign(sig_blob + tc_bytes).signature)
    global_blob = MAGIC + keynum + global_sig
    return "\n".join([
        f"untrusted comment: {untrusted_comment}",
        _b64e(sig_blob),
        f"trusted comment: {trusted_comment}",
        _b64e(global_blob),
        "",
    ])


def verify(pubkey_text: str, sig_text: str, message: bytes) -> bool:
    """一次性验签：公钥 keynum 与签名 keynum 必须一致 + Ed25519 验过。"""
    if nacl is None:
        raise MinisignError("缺少 pynacl（pip install pynacl）")
    try:
        _, pub = parse_pubkey(pubkey_text)
        lines = [ln.strip() for ln in sig_text.splitlines() if ln.strip()]
        sig_line = next(
            (ln for ln in lines
             if not ln.lower().startswith(("untrusted comment:", "trusted comment:"))),
            None,
        )
        if sig_line is None:
            return False
        blob = base64.b64decode(sig_line, validate=True)
        if len(blob) != SIG_BLOB_LEN or not blob.startswith(MAGIC):
            return False
        vkey = nacl.signing.VerifyKey(pub)
        vkey.verify(message, blob[len(MAGIC) + KEYNUM_LEN:])
        return True
    except Exception:
        return False


def _cli(argv: list[str]) -> int:
    import argparse
    p = argparse.ArgumentParser(description="minisign 兼容签名/验签（pynacl）")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("sign", help="对文件签名 → <file>.minisig")
    s.add_argument("file", type=Path)
    s.add_argument("--seed-file", type=Path, required=True,
                   help="raw seed base64 单行文本（CI secret 原文）")
    s.add_argument("--pubkey-file", type=Path, required=True)
    s.add_argument("--untrusted-comment", default="signature from abyss release key")
    s.add_argument("--trusted-comment", default="abyss-release")
    v = sub.add_parser("verify", help="验 <file>.minisig（exit 0/1）")
    v.add_argument("file", type=Path)
    v.add_argument("--pubkey-file", type=Path, required=True)
    a = p.parse_args(argv)
    if a.cmd == "sign":
        seed, keynum, _ = load_seed(
            a.seed_file.read_text(encoding="utf-8"),
            a.pubkey_file.read_text(encoding="utf-8"),
        )
        msg = a.file.read_bytes()
        sig = sign_message(seed, keynum, msg, a.untrusted_comment, a.trusted_comment)
        out = a.file.with_name(a.file.name + ".minisig")
        out.write_text(sig, encoding="ascii")
        if not verify(a.pubkey_file.read_text(encoding="utf-8"), sig, msg):
            print(f"::error::签名自验失败: {a.file.name}", file=sys.stderr)
            return 1
        print(f"✅ signed {a.file.name} → {out.name}")
        return 0
    ok = verify(
        a.pubkey_file.read_text(encoding="utf-8"),
        a.file.with_name(a.file.name + ".minisig").read_text(encoding="utf-8"),
        a.file.read_bytes(),
    )
    print(f"{'✅ verify OK' if ok else '✗ verify FAILED'}: {a.file.name}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(_cli(sys.argv[1:]))
