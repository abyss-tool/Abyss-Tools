#!/usr/bin/env python3
"""开发仓库 CI 门禁：更新通道 / 插件索引测试夹具的 schema 校验。

为什么校验「测试夹具」：夹具即通道契约的镜像样本 —— 单元测试消费它们做
行为断言，本脚本保证夹具本身不发生手写漂移（字段缺失 / 版本号变形 /
id 与仓库错位）。发布仓与插件中心仓另有同款校验，三处同构。
"""
import json
import re
import sys
from pathlib import Path

SEMVER = re.compile(r"^\d+\.\d+\.\d+$")
REPO = re.compile(r"^Ultra-Guru/abyss-plugin-[a-z0-9]+(-[a-z0-9]+)*$")
CHANNEL = re.compile(
    r"^https://raw\.githubusercontent\.com/Ultra-Guru/abyss-plugin-[a-z0-9-]+/main/patches/index\.json$"
)

errors: list[str] = []


def fail(msg: str) -> None:
    errors.append(msg)


def check_version(value, where: str) -> None:
    if not isinstance(value, str) or not SEMVER.match(value):
        fail(f"{where}: 非法 SemVer → {value!r}")


def check_channel_fixtures() -> None:
    base = Path("core/update/src/test/resources/channel")
    for path in sorted(base.glob("version-*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("schema") != "abyss-version-v1":
            fail(f"{path}: schema 应为 abyss-version-v1")
        check_version(data.get("versionName"), str(path))
        tag = data.get("tag")
        if tag is not None and tag != f"v{data.get('versionName')}":
            fail(f"{path}: tag({tag}) 与 versionName(v{data.get('versionName')}) 不一致")
        patch = data.get("patch")
        if patch is not None:
            if not patch.get("url"):
                fail(f"{path}: patch.url 缺失")
            if not patch.get("fromTag", "").startswith("v"):
                fail(f"{path}: patch.fromTag 应为 v 前缀 tag")
    patches_index = base / "patches-index.json"
    if patches_index.exists():
        data = json.loads(patches_index.read_text(encoding="utf-8"))
        if data.get("schema") != "abyss-patches-v1":
            fail(f"{patches_index}: schema 应为 abyss-patches-v1")
        seen = set()
        for entry in data.get("patches", []):
            key = (entry.get("fromTag"), entry.get("toTag"))
            if key in seen:
                fail(f"{patches_index}: 重复补丁段 {key}")
            seen.add(key)


def check_plugin_index_fixture() -> None:
    path = Path("core/plugin/src/test/resources/plugins-index.json")
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema") != "abyss-plugin-index-v1":
        fail(f"{path}: schema 应为 abyss-plugin-index-v1")
    plugins = data.get("plugins", [])
    if len(plugins) < 10:
        fail(f"{path}: 插件中心至少登记 10 个大插件（当前 {len(plugins)}）")
    ids = set()
    for p in plugins:
        where = f"{path}[{p.get('id')}]"
        pid = p.get("id", "")
        if pid in ids:
            fail(f"{where}: id 重复")
        ids.add(pid)
        for field in ("id", "name", "repo", "category", "version", "minApp", "summary"):
            if not p.get(field):
                fail(f"{where}: 缺少必填字段 {field}")
        if not REPO.match(p.get("repo", "")):
            fail(f"{where}: repo 形态非法 → {p.get('repo')!r}")
        if not p["repo"].endswith(pid):
            fail(f"{where}: repo 末段应等于插件 id")
        check_version(p.get("version"), where)
        check_version(p.get("minApp"), where)
        channel = p.get("patchChannel", "")
        if not CHANNEL.match(channel):
            fail(f"{where}: patchChannel 形态非法 → {channel!r}")
        if p.get("apk") is not None:
            apk = p["apk"]
            if not apk.get("url"):
                fail(f"{where}: apk.url 缺失")
    print(f"[manifests] 插件索引夹具：{len(plugins)} 个插件校验通过")


def check_plugin_channel_fixture() -> None:
    path = Path("core/plugin/src/test/resources/plugin-patch-channel.json")
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema") != "abyss-plugin-patches-v1":
        fail(f"{path}: schema 应为 abyss-plugin-patches-v1")
    check_version(data.get("latest"), str(path))


def check_changelog_fixture() -> None:
    path = Path("core/update/src/test/resources/channel/changelog.json")
    if not path.exists():
        fail(f"{path}: changelog 夹具缺失（changelog.json 消费端测试依赖）")
        return
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema") != "abyss-changelog-v1":
        fail(f"{path}: schema 应为 abyss-changelog-v1")
    entries = []
    if data.get("latest") is not None:
        entries.append(("latest", data["latest"]))
    entries += [("history", e) for e in data.get("history", [])]
    versions = []
    for where, entry in entries:
        check_version(entry.get("versionName"), f"{path}[{where}]")
        versions.append(entry.get("versionName"))
        tag = entry.get("tag")
        if tag is not None and tag != f"v{entry.get('versionName')}":
            fail(f"{path}[{where}]: tag({tag}) 与 versionName 不一致")
    if len(versions) != len(set(versions)):
        fail(f"{path}: 条目版本号重复")
    # latest 必须是最新（严格大于 history 中所有版本的主次修订序）
    if data.get("latest") is not None and data.get("history"):
        latest = tuple(int(x) for x in data["latest"]["versionName"].split("."))
        for h in data["history"]:
            hv = tuple(int(x) for x in h["versionName"].split("."))
            if hv >= latest:
                fail(f"{path}: latest({latest}) 应高于 history 条目({hv})")
    print(f"[manifests] changelog 夹具：{len(entries)} 条目校验通过")


def check_version_sync() -> None:
    """版本三处一致（单一事实源纪律）：app/build.gradle.kts ↔ AppInfo。

    发布流水线（release.yml）从 build.gradle.kts 推导 tag；客户端比对用
    AppInfo —— 两处漂移会让「检查更新」语义错乱（tag 与实际版本号不符）。
    """
    gradle = Path("app/build.gradle.kts")
    appinfo = Path("core/common/src/main/kotlin/com/abyss/tools/core/common/AppInfo.kt")
    gradle_text = gradle.read_text(encoding="utf-8")
    appinfo_text = appinfo.read_text(encoding="utf-8")

    m_name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle_text)
    m_code = re.search(r"versionCode\s*=\s*(\d+)", gradle_text)
    a_name = re.search(r'VERSION_NAME:\s*String\s*=\s*"([^"]+)"', appinfo_text)
    a_code = re.search(r"VERSION_CODE:\s*Int\s*=\s*(\d+)", appinfo_text)
    if not (m_name and m_code and a_name and a_code):
        fail("check_version_sync: 版本字段解析失败（build.gradle.kts / AppInfo 形态变更？）")
        return
    if m_name.group(1) != a_name.group(1):
        fail(f"check_version_sync: versionName 漂移 build.gradle={m_name.group(1)!r} AppInfo={a_name.group(1)!r}")
    if m_code.group(1) != a_code.group(1):
        fail(f"check_version_sync: versionCode 漂移 build.gradle={m_code.group(1)!r} AppInfo={a_code.group(1)!r}")
    if not SEMVER.match(m_name.group(1)):
        fail(f"check_version_sync: versionName 非 SemVer → {m_name.group(1)!r}")
    print(f"[manifests] 版本同步：v{m_name.group(1)} ({m_code.group(1)}) 三处一致")


def check_scripts_compile() -> None:
    import py_compile

    for py in sorted(Path("tools").glob("*.py")):
        try:
            py_compile.compile(str(py), doraise=True)
        except py_compile.PyCompileError as e:
            fail(f"{py}: 语法错误 → {e}")


if __name__ == "__main__":
    check_channel_fixtures()
    check_plugin_index_fixture()
    check_plugin_channel_fixture()
    check_changelog_fixture()
    check_version_sync()
    check_scripts_compile()
    if errors:
        print(f"\n[manifests] ✗ {len(errors)} 处校验失败：", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        sys.exit(1)
    print("[manifests] ✓ 全部更新通道 / 插件索引 / changelog / 版本同步校验通过")
