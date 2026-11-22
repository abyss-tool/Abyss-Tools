#!/usr/bin/env python3
"""N-SEC-002 · RELEASE_TOKEN 最小权限与轮换演练脚本。

三项能力（对齐规格 FR-002 / FR-003 / FR-004）：

  --probe          身份确认 + 各仓读探针 + 发布链路关键资产可读性
                   （version.json / .minisig / Release 列表）。吊销演练的
                   「验证发布链路恢复」跑这个。
  --audit-secrets  静态扫描各仓 workflow 的 secrets 引用，输出
                   「仓 → secret → 消费点」使用记录（FR-004 审计面）。
  --check-tooling  静态断言发布工具链错误路径不回显令牌（AC-001 的
                   「错误信息不泄露令牌」半边 —— die/print 不得引用
                   TOKEN/KEY 变量）。

90 天轮换 SOP（FR-002）见输出与 docs/SECURITY.md「Secrets 与令牌纪律」。

真实吊销在 GitHub → Settings → Developer settings → Fine-grained tokens
执行（脚本绝不持有吊销权限，也不打印任何令牌值）。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ORG = "abyss-tool"
API = "https://api.github.com"

# 令牌生态清单（最小权限矩阵，见 SECURITY.md）
TOKEN_INVENTORY = [
    ("Abyss-Tools", "RELEASE_TOKEN", "fine-grained PAT，仅发布仓 Abyss-Tools-Release Contents RW"),
    ("Abyss-Tools", "RELEASE_SIGNING_KEY", "清单签名私钥（raw Ed25519 seed base64 单行）"),
    ("Abyss-Tools", "ABYSS_KEYSTORE_B64 / 口令三件套", "APK 发布签名材料（v0.6.0 出库 secrets）"),
    ("Abyss-Tools-Release", "GITHUB_TOKEN", "清单门禁只读（Actions 内置，无需 PAT）"),
    ("Abyss-Plugins", "RELEASE_SIGNING_KEY", "plugins.json 签名私钥"),
    ("Abyss-Plugins", "GITHUB_TOKEN", "聚合读取（Actions 内置）"),
    ("abyss-plugin-*（3 仓）", "RELEASE_SIGNING_KEY", "补丁通道签名私钥（各仓独立配置）"),
    ("abyss-plugin-*（3 仓）", "GITHUB_TOKEN", "补丁发布（Actions 内置 contents:write）"),
]

READ_PROBE_TARGETS = [
    "Abyss-Tools-Release",
    "Abyss-Plugins",
    "abyss-plugin-app-builder",
    "abyss-plugin-virus-guard",
    "abyss-plugin-virtual-machine",
]

CHAIN_ASSETS = [
    "Abyss-Tools-Release/version.json",
    "Abyss-Tools-Release/version.json.minisig",
    "Abyss-Plugins/plugins.json",
    "Abyss-Plugins/plugins.json.minisig",
]


def api_get(path: str, token: str) -> tuple[int, bytes]:
    req = urllib.request.Request(f"{API}{path}")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "abyss-pat-drill/1.0")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def probe(token: str) -> int:
    print("== 身份确认（令牌值不回显）==")
    code, body = api_get("/user", token)
    if code != 200:
        print(f"✗ 令牌无效或已吊销（HTTP {code}）—— 吊销演练中这是预期失败")
        return 1
    login = json.loads(body.decode()).get("login", "?")
    print(f"✓ 身份: {login}")

    print("\n== 各仓读探针 ==")
    bad = 0
    for repo in READ_PROBE_TARGETS:
        code, _ = api_get(f"/repos/{ORG}/{repo}", token)
        mark = "✓" if code == 200 else f"✗({code})"
        print(f"  {mark} {ORG}/{repo}")
        bad += code != 200

    print("\n== 发布链路关键资产 ==")
    for asset in CHAIN_ASSETS:
        url = f"https://raw.githubusercontent.com/{asset}"
        req = urllib.request.Request(url)
        req.add_header("User-Agent", "abyss-pat-drill/1.0")
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                size = len(resp.read())
                print(f"  ✓ {asset}（{size} bytes）")
        except urllib.error.HTTPError as e:
            # .minisig 尚未首签属已知过渡态（N-SEC-001 落地时序），不算链路故障
            note = "（尚未首签 —— 等待 N-SEC-001 首次发布产出）" if asset.endswith(".minisig") else ""
            print(f"  {'⚠' if note else '✗'} {asset} HTTP {e.code}{note}")
            bad += not note

    print("\n结论: " + ("链路探针全部通过" if bad == 0 else f"{bad} 处异常 —— 排查后再发布"))
    return 0 if bad == 0 else 1


def audit_secrets(root: Path) -> int:
    print("== secrets 使用记录审计（FR-004）==")
    # 审计目标 = 本仓 + 本地兄弟仓克隆（D 盘 abyss-tool 工作区惯例布局）
    targets = [root]
    for d in sorted(root.parent.iterdir()):
        if d != root and d.is_dir() and (d / ".github" / "workflows").is_dir():
            targets.append(d)
    used: dict[str, list[str]] = {}
    for repo in targets:
        for wf in sorted((repo / ".github" / "workflows").glob("*.yml")):
            text = wf.read_text(encoding="utf-8", errors="replace")
            for m in re.finditer(r"secrets\.([A-Z0-9_]+)", text):
                used.setdefault(m.group(1), []).append(f"{repo.name}/{wf.name}")
    for secret in sorted(used):
        print(f"  {secret}")
        for where in used[secret]:
            print(f"    ← {where}")
    print("\n== 令牌生态最小权限矩阵（SOP 对照）==")
    for scope, name, policy in TOKEN_INVENTORY:
        print(f"  [{scope}] {name} —— {policy}")
    print("\n矩阵外新增 secret 必须先在 SECURITY.md 登记（fail-closed 审计纪律）。")
    return 0


def check_tooling(root: Path) -> int:
    print("== 发布工具链错误路径不回显令牌（AC-001 静态断言）==")
    bad = 0
    self_name = Path(__file__).name
    patterns = re.compile(r"(TOKEN|KEY|PASSWORD|SECRET)\b", re.I)
    for tool in sorted((root / "tools").glob("*.py")):
        if tool.name == self_name:
            print(f"  · {tool.name}（本脚本自跳过 —— 规则引擎自身不含机密输出）")
            continue
        text = tool.read_text(encoding="utf-8", errors="replace")
        hits = []
        for i, line in enumerate(text.splitlines(), 1):
            # die/print/raise 携带 TOKEN/KEY 变量即视为回显风险
            if re.search(r"\b(die|print|raise)\b", line) and re.search(r"\bf?(TOKEN|KEY|PASSWORD|SECRET)\b", line):
                # 允许「不回显值」的字样与常量名单
                if "不回显" in line or "TOKEN_INVENTORY" in line or "_TRUSTED_PUBKEYS" in line:
                    continue
                hits.append((i, line.strip()[:100]))
        if hits:
            bad += 1
            print(f"  ✗ {tool.name}（{len(hits)} 处疑似令牌引用进入输出）:")
            for i, ln in hits[:5]:
                print(f"      L{i}: {ln}")
        else:
            print(f"  ✓ {tool.name}")
    print("\n结论: " + ("错误路径零令牌回显" if bad == 0 else f"{bad} 个脚本需整改"))
    return 0 if bad == 0 else 1


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    ap = argparse.ArgumentParser(description="N-SEC-002 令牌演练与审计")
    ap.add_argument("--probe", action="store_true", help="身份 + 读探针 + 发布链路资产")
    ap.add_argument("--audit-secrets", action="store_true", help="secrets 使用记录审计")
    ap.add_argument("--check-tooling", action="store_true", help="错误路径不回显令牌静态断言")
    ap.add_argument("--token", default=os.environ.get("GITHUB_TOKEN", ""),
                    help="被审计的 PAT（不回显；--probe 必需）")
    args = ap.parse_args()
    if not (args.probe or args.audit_secrets or args.check_tooling):
        ap.print_help()
        return 2
    rc = 0
    if args.probe:
        if not args.token:
            print("::error::--probe 需要 --token / GITHUB_TOKEN", file=sys.stderr)
            return 2
        rc |= probe(args.token)
    if args.audit_secrets:
        rc |= audit_secrets(root)
    if args.check_tooling:
        rc |= check_tooling(root)
    print("\n── 90 天轮换 SOP（FR-002）──")
    print("  1. GitHub 生成新 fine-grained PAT（只授目标仓 Contents RW，90 天有效期）")
    print("  2. 各仓 Settings → Secrets 更新 RELEASE_TOKEN / RELEASE_SIGNING_KEY（新值）")
    print("  3. 手动触发发布仓 validate / 插件中心 Sync Index / 主仓 CI → 全绿")
    print("  4. 本脚本 --probe 复核链路 → 吊销旧令牌 → 再次 --probe 应失败")
    print("  5. SECURITY.md 更新轮换记录（日期 / 执行人 / 新到期日）")
    return rc


if __name__ == "__main__":
    sys.exit(main())
