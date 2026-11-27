#!/usr/bin/env python3
"""
N-ENG-009 · 归档仓历史清单漂移审计（M1，P2）。

背景（HUB-014 漂移台账）：13 个归档插件仓中 6 仓 plugin.json 的
repo / patchChannel 仍指向旧组织 Ultra-Guru —— GitHub 归档仓只读
不可改，治理方式为「台账登记 + 持续审计」，绝不让漂移值被现网索引
采纳（FR-001/FR-002：不复活、不进 plugins.json）。

检查项（全部 fail-closed）：
  1. 台账与 HUB-014 规格 md 双向一致：13 仓名逐仓互查（防脚本/文档漂移）；
  2. 每仓 plugin.json 的 repo 字段与台账登记值一致 —— 出现台账未登记的
     新漂移（仓库改名 / 字段被改）即失败（FR-001 归档快照不得再动）；
  3. 归档仓 GitHub API archived=true（HUB-014 AC-002；--offline 跳过）；
  4. 主仓测试夹具 plugins-index.json 不含任何归档 id（HUB-014 AC-001）；
  5. PluginFrameworkTest retired 集合覆盖全部 13 个归档 id（FR-004）。

用法：
  python3 tools/audit_archived_repos.py            # 全量（需网络）
  python3 tools/audit_archived_repos.py --offline  # 跳过 GitHub API 网络检查

环境变量：GITHUB_TOKEN（可选，只读即可，用于提高 API 限额）。
"""

from __future__ import annotations

import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HUB014 = ROOT / "docs" / "abyss-specs" / "04-插件生态" / "HUB-014-13个归档插件仓治理.md"
FIXTURE = ROOT / "core" / "plugin" / "src" / "test" / "resources" / "plugins-index.json"
TEST = ROOT / "core" / "plugin" / "src" / "test" / "kotlin" / "com" / "abyss" / "tools" / "core" / "plugin" / "PluginFrameworkTest.kt"

API_BASE = "https://api.github.com/repos/abyss-tool"
RAW_BASE = "https://raw.githubusercontent.com/abyss-tool"

# ── HUB-014 漂移台账（2026-11-27 实测登记；原值 = 归档时只读快照）──────
# drift=True 的 6 仓 repo/patchChannel 仍为 Ultra-Guru 旧组织前缀 ——
# 归档仓不可修改，治理 = 台账留痕（N-ENG-009 FR-001「或显式标注为历史
# 只读快照」路径）+ 本脚本持续审计防再漂移。
LEDGER: dict[str, dict[str, object]] = {
    # v0.2.0 七仓内置化归档（六仓漂移 + disguise 干净）
    "abyss-plugin-app-manager": {"repo": "Ultra-Guru/abyss-plugin-app-manager", "drift": True},
    "abyss-plugin-shizuku-kit": {"repo": "Ultra-Guru/abyss-plugin-shizuku-kit", "drift": True},
    "abyss-plugin-automation": {"repo": "Ultra-Guru/abyss-plugin-automation", "drift": True},
    "abyss-plugin-island": {"repo": "Ultra-Guru/abyss-plugin-island", "drift": True},
    "abyss-plugin-wallpaper": {"repo": "Ultra-Guru/abyss-plugin-wallpaper", "drift": True},
    "abyss-plugin-qr-craft": {"repo": "Ultra-Guru/abyss-plugin-qr-craft", "drift": True},
    "abyss-plugin-disguise": {"repo": "abyss-tool/abyss-plugin-disguise", "drift": False},
    # v0.8.0 大内置化六仓归档（均已更正为 abyss-tool 组织）
    "abyss-plugin-terminal": {"repo": "abyss-tool/abyss-plugin-terminal", "drift": False},
    "abyss-plugin-python-lab": {"repo": "abyss-tool/abyss-plugin-python-lab", "drift": False},
    "abyss-plugin-sandbox": {"repo": "abyss-tool/abyss-plugin-sandbox", "drift": False},
    "abyss-plugin-media-lab": {"repo": "abyss-tool/abyss-plugin-media-lab", "drift": False},
    "abyss-plugin-network-guard": {"repo": "abyss-tool/abyss-plugin-network-guard", "drift": False},
    "abyss-plugin-backup": {"repo": "abyss-tool/abyss-plugin-backup", "drift": False},
}

errors: list[str] = []


def fail(msg: str) -> None:
    errors.append(msg)


def http_get_json(url: str) -> object:
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "abyss-archived-audit",
    })
    import os
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def http_get_text(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "abyss-archived-audit"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8")


def check_ledger_vs_spec() -> None:
    """检查 1：台账与 HUB-014 规格 md 逐仓互查（单一事实源，防漂移）。"""
    text = HUB014.read_text(encoding="utf-8")
    for name, entry in LEDGER.items():
        if name not in text:
            fail(f"HUB-014 台账缺仓：{name}（审计脚本已登记，规格 md 未登记 —— 两侧漂移）")
        expected = f"`{entry['repo']}`"
        if entry["drift"] and expected not in text:
            fail(f"HUB-014 台账未登记 {name} 的 repo 原值 {entry['repo']!r}（FR-004 原值留痕）")


def check_manifest_snapshot() -> None:
    """检查 2：归档仓 plugin.json 快照与台账一致（需网络）。

    归档仓只读 —— manifest 理论上永不变化；任何与台账不符的偏差都说明
    仓库被动过（改名 / 手改字段），fail-closed 提示人工复核台账。
    """
    for name, entry in LEDGER.items():
        url = f"{RAW_BASE}/{name}/main/plugin.json"
        try:
            data = json.loads(http_get_text(url))
        except Exception as e:  # 网络失败 / 404 / 坏 JSON
            fail(f"{name}: plugin.json 拉取失败（{e}）—— 归档仓应恒可读")
            continue
        actual_repo = data.get("repo")
        if actual_repo != entry["repo"]:
            fail(
                f"{name}: repo 字段与台账不符 → 实测 {actual_repo!r}，"
                f"台账 {entry['repo']!r} —— 归档仓被改动？请人工复核 HUB-014 台账"
            )
        channel = data.get("patchChannel", "")
        expected_prefix = f"https://raw.githubusercontent.com/{entry['repo']}/"
        if not channel.startswith(expected_prefix):
            fail(
                f"{name}: patchChannel 前缀与 repo 字段错位 → {channel[:72]!r}，"
                f"应为 {expected_prefix}… 前缀"
            )


def check_archived_flag() -> None:
    """检查 3：GitHub API archived=true（HUB-014 AC-002）。"""
    for name in LEDGER:
        try:
            data = http_get_json(f"{API_BASE}/{name}")
        except Exception as e:
            fail(f"{name}: GitHub API 查询失败（{e}）")
            continue
        if data.get("archived") is not True:
            fail(f"{name}: archived={data.get('archived')} —— 归档仓被解档？HUB-014 FR-001 禁止复活")


def check_fixture_exclusion() -> None:
    """检查 4：现网索引夹具不含任何归档 id（HUB-014 AC-001）。"""
    data = json.loads(FIXTURE.read_text(encoding="utf-8"))
    ids = {p.get("id") for p in data.get("plugins", [])}
    leaked = ids & set(LEDGER)
    if leaked:
        fail(f"plugins-index.json 夹具出现归档 id：{sorted(leaked)} —— 归档插件绝不允许再进索引")


def check_retired_coverage() -> None:
    """检查 5：PluginFrameworkTest retired 集合覆盖全部 13 归档 id（FR-004）。"""
    text = TEST.read_text(encoding="utf-8")
    m = re.search(r"val retired\s*=\s*setOf\((.*?)\)", text, re.DOTALL)
    if not m:
        fail("PluginFrameworkTest 未找到 retired 集合 —— HUB-014 FR-004 断言缺失")
        return
    retired = set(re.findall(r'"(abyss-plugin-[a-z0-9-]+)"', m.group(1)))
    missing = set(LEDGER) - retired
    if missing:
        fail(f"retired 集合缺归档 id：{sorted(missing)}（另：virtual-machine 亦应在内）")


def main() -> int:
    offline = "--offline" in sys.argv[1:]
    print(f"[archived-audit] 台账仓数：{len(LEDGER)}（漂移 {sum(1 for e in LEDGER.values() if e['drift'])} 仓）")

    check_ledger_vs_spec()
    check_fixture_exclusion()
    check_retired_coverage()
    if offline:
        print("[archived-audit] --offline：跳过 GitHub API / 归档仓快照网络检查")
    else:
        check_manifest_snapshot()
        check_archived_flag()

    if errors:
        print(f"::error::归档仓漂移审计失败，共 {len(errors)} 处：", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    where = "（--offline 轻量模式）" if offline else "（含网络实测）"
    print(f"[archived-audit] ✓ 13 归档仓治理审计通过 {where}：台账/规格/夹具/retired 四方一致，无未登记漂移")
    return 0


if __name__ == "__main__":
    sys.exit(main())
