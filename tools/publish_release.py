#!/usr/bin/env python3
"""Abyss-Tools 发布流水线：构建产物 → 发布仓（Abyss-Tools-Release）。

职责（对齐 Android-Guru-Agent 双仓库模式）：
  1. 计算发布 APK 的 SHA-256 与体积；
  2. 在发布仓创建 GitHub Release（tag = v{versionName}）并上传 APK 资产；
  3. 更新发布仓三份通道清单（Contents API，带 sha 的原子更新）：
     - version.json   download.url/sha256/sizeBytes + versionCode/tag/notes
     - patches.json   latestTag 滚动（APK 二进制增量 v0.2 接入，当前只记链）
     - changelog.json previous latest 下沉 history，新版本置顶

凭据：RELEASE_TOKEN 环境变量（PAT，需 repo 权限——发布仓写入 + Release 创建）。
只用 Python stdlib（urllib），CI 与本机行为一致。

用法：
  RELEASE_TOKEN=ghp_xxx python3 tools/publish_release.py \
      --tag v0.1.1 --version-code 2 \
      --apk app/build/outputs/apk/release/app-release.apk \
      --notes "第一行要点" --notes "第二行要点" \
      --channel framework-preview \
      --commit-sha <git-sha>
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

API = "https://api.github.com"
RELEASE_REPO = os.environ.get("ABYSS_RELEASE_REPO", "abyss-tool/Abyss-Tools-Release")
TOKEN = os.environ.get("RELEASE_TOKEN", "")


def die(msg: str) -> None:
    print(f"[publish] ✗ {msg}", file=sys.stderr)
    sys.exit(1)


def api(
    method: str,
    path: str,
    token: str,
    body: dict | None = None,
    raw: bytes | None = None,
    accept: str = "application/vnd.github+json",
    base: str = API,
    not_found_ok: bool = False,
) -> dict:
    url = path if path.startswith("http") else f"{base}{path}"
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, method=method, data=data)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", accept)
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data is not None and body is not None:
        req.add_header("Content-Type", "application/json")
    elif data is not None and raw is not None:
        req.add_header("Content-Type", "application/octet-stream")
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                payload = resp.read()
                if resp.status in (201, 202, 204) and not payload:
                    return {}
                return json.loads(payload.decode()) if payload else {}
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            if e.code == 404 and not_found_ok:
                return {}
            if e.code in (409, 422) and attempt < 3:
                time.sleep(1.5 * (attempt + 1))
                continue
            die(f"GitHub API {method} {url} → HTTP {e.code}\n{detail[:600]}")
        except urllib.error.URLError as e:
            if attempt < 3:
                time.sleep(2 * (attempt + 1))
                continue
            die(f"GitHub API {method} {url} → 网络错误 {e.reason}")
    return {}


def get_contents(token: str, path: str, not_found_ok: bool = False) -> dict:
    """读取发布仓文件：{content(base64), sha}；404 可选兑底（首次发布）。"""
    return api(
        "GET",
        f"/repos/{RELEASE_REPO}/contents/{path}?ref=main",
        token,
        not_found_ok=not_found_ok,
    )


def put_contents(token: str, path: str, text: str, sha: str | None, message: str) -> None:
    import base64

    body = {
        "message": message,
        "content": base64.b64encode(text.encode()).decode(),
        "branch": "main",
    }
    if sha:
        body["sha"] = sha
    api("PUT", f"/repos/{RELEASE_REPO}/contents/{path}", token, body)
    print(f"[publish] ✓ 发布仓已更新 {path}")


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def asset_sha256_from_digest(digest: str | None) -> str | None:
    """GitHub 资产 digest（"sha256:xxx"）→ 纯十六进制（无摘要 → None）。"""
    if not digest:
        return None
    return digest.removeprefix("sha256:").lower() or None


def verify_uploaded_asset(repo: str, release_id: int, asset_name: str, expected_sha: str) -> str:
    """回读资产真实 digest 并与预期比对（fail-closed）。

    v0.7.0 加固：发布仓 / 主仓各自上传后立即回读 —— 任何环节（传输截断 /
    上传期间源文件被改 / GitHub 入档异常）导致资产字节 ≠ 预期，就地报错
    拒绝继续（通道清单不会指向一个校验必失败的资产）。返回资产真实摘要。
    """
    assets = api("GET", f"/repos/{repo}/releases/{release_id}/assets", TOKEN)
    asset = next((a for a in assets if a["name"] == asset_name), None)
    if asset is None:
        die(f"资产回读失败（{repo} / {asset_name} 不在资产列表）")
    actual = asset_sha256_from_digest(asset.get("digest"))
    if actual is None:
        print(f"[publish] ⚠ {repo} 资产无 digest 字段（旧版 GitHub）—— 跳过比对")
        return expected_sha
    if actual != expected_sha:
        die(
            f"资产摘要与构建产物不一致（{repo} / {asset_name}）：\n"
            f"  资产真实: {actual}\n  预期:    {expected_sha}\n"
            f" —— 拒绝发布（fail-closed，防通道指向校验必失败的资产）"
        )
    print(f"[publish] ✓ 资产摘要回读一致（{repo}）")
    return actual


def ensure_release_with_apk(
    repo: str,
    tag: str,
    title: str | None,
    notes: list[str],
    apk_path: str,
    asset_name: str,
) -> int:
    """在指定仓库幂等创建 Release 并上传 APK 资产，返回 release id。

    （v0.7.0 抽取成函数：发布仓与主仓镜像发布共用同一套幂等逻辑 ——
    Release 已存在则复用；同名资产先删后传。）
    """
    existing_release = api("GET", f"/repos/{repo}/releases/tags/{tag}", TOKEN, not_found_ok=True)
    if "id" in existing_release:
        release_id = existing_release["id"]
        print(f"[publish] 复用既有 Release {tag}（{repo}，id={release_id}）")
    else:
        created = api(
            "POST",
            f"/repos/{repo}/releases",
            TOKEN,
            {
                "tag_name": tag,
                "target_commitish": "main",
                "name": title or f"Abyss Tools {tag}",
                "body": "\n".join(f"- {n}" for n in notes) or tag,
                "draft": False,
                "prerelease": False,
            },
        )
        release_id = created.get("id")
        if not release_id:
            die(f"Release 创建失败（{repo} / {tag}）")
        print(f"[publish] ✓ Release {tag} 已创建（{repo}，id={release_id}）")

    existing_assets = {a["name"]: a for a in api("GET", f"/repos/{repo}/releases/{release_id}/assets", TOKEN)}
    if asset_name in existing_assets:
        api("DELETE", existing_assets[asset_name]["url"], TOKEN)
        print(f"[publish] 已移除旧资产 {asset_name}（{repo}），重新上传")
    upload_url = f"https://uploads.github.com/repos/{repo}/releases/{release_id}/assets?name={asset_name}"
    with open(apk_path, "rb") as f:
        api("POST", upload_url, TOKEN, raw=f.read(), base="https://uploads.github.com")
    return release_id


def main() -> None:
    parser = argparse.ArgumentParser(description="Abyss-Tools 发布流水线")
    parser.add_argument("--tag", required=True, help="git tag，如 v0.1.1（必须等于 v{versionName}）")
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--apk", required=True, help="release APK 路径")
    parser.add_argument("--notes", action="append", default=[], help="更新要点（可多次）")
    parser.add_argument("--title", default=None, help="版本标题（缺省用 tag）")
    parser.add_argument("--channel", default="stable")
    parser.add_argument("--commit-sha", default=None)
    parser.add_argument("--asset-name", default=None, help="资产文件名（缺省 abyss-tools-<tag>.apk）")
    parser.add_argument(
        "--mirror-to-repo",
        default=None,
        help="（v0.7.0）同步发布 APK 到第二个仓库（如主开发仓 abyss-tool/Abyss-Tools）："
        "同 tag 建 Release + 上传同资产；通道清单（version.json 等）仍只写发布仓，"
        "镜像仓不产生任何通道文件。",
    )
    args = parser.parse_args()

    if not TOKEN:
        die("RELEASE_TOKEN 未设置（需要具有 repo 权限的 PAT）")
    if not os.path.isfile(args.apk):
        die(f"APK 不存在：{args.apk}")

    version_name = args.tag.removeprefix("v").removeprefix("V")
    asset_name = args.asset_name or f"abyss-tools-{args.tag}.apk"

    # ── 快照（v0.7.0 加固）：构建产物复制到不可变临时路径，后续哈希 / 双仓
    # 上传全部消费快照 —— 构建目录里的源文件即使在脚本执行期间被改写
    # （缓存回写 / 并发任务），也不可能污染发布字节。
    import shutil
    import tempfile

    snapshot_fd, snapshot_path = tempfile.mkstemp(suffix=".apk", prefix="abyss-publish-")
    os.close(snapshot_fd)
    shutil.copyfile(args.apk, snapshot_path)
    args.apk = snapshot_path
    try:
        apk_sha = sha256_of(args.apk)
        apk_size = os.path.getsize(args.apk)
        _main_publish(args, version_name, asset_name, apk_sha, apk_size)
    finally:
        try:
            os.unlink(snapshot_path)
        except OSError:
            pass


def _main_publish(args, version_name: str, asset_name: str, apk_sha: str, apk_size: int) -> None:
    published_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    print(f"[publish] 版本 {version_name} (code {args.version_code}) · {asset_name}")
    print(f"[publish] sha256 = {apk_sha} · {apk_size} bytes")

    # ── 0. 通道预检（v0.7.1 加固）：versionCode 单调门禁与同 tag 重发拦截
    # 【前置到一切上传之前】—— 此前该检查在资产上传后才执行，被拦截时
    # 发布仓资产已被删除重传（v0.7.0 事故实证：同源码不同 SDK 依赖块
    # 字节的 APK 覆盖了已发布资产，制造资产↔清单摘要不一致）。
    cur = get_contents(TOKEN, "version.json", not_found_ok=True)
    import base64 as _b64

    if cur.get("content"):
        try:
            existing_version = json.loads(_b64.b64decode(cur["content"]).decode())
        except Exception:
            die("version.json 现存内容损坏 —— 拒绝覆盖（先人工排查发布仓）")
        existing_code = existing_version.get("versionCode", 0)
        if args.version_code <= existing_code:
            die(
                f"versionCode 必须严格递增：通道现值 {existing_code}，"
                f"本次 {args.version_code} —— 防误发旧版本把 latest 拉回去"
            )
        if existing_version.get("tag") == args.tag:
            die(f"tag {args.tag} 已在通道中发布过 —— 同 tag 重发需先人工清理")

    # ── 1. 发布仓创建 Release + 上传 APK（幂等，双仓复用）─────────────
    release_id = ensure_release_with_apk(
        repo=RELEASE_REPO,
        tag=args.tag,
        title=args.title,
        notes=args.notes,
        apk_path=args.apk,
        asset_name=asset_name,
    )
    # v0.7.0 加固：回读资产真实 digest，与快照摘要比对（fail-closed）
    verify_uploaded_asset(RELEASE_REPO, release_id, asset_name, apk_sha)
    download_url = f"https://github.com/{RELEASE_REPO}/releases/download/{args.tag}/{asset_name}"
    print(f"[publish] ✓ APK 资产已上传 → {download_url}")

    # ── 3. version.json（单调门禁已在上传前预检，此处只写）────────────
    import base64

    version = {
        "schema": "abyss-version-v1",
        "versionName": version_name,
        "versionCode": args.version_code,
        "tag": args.tag,
        "publishedAt": published_at,
        "channel": args.channel,
        "notes": "；".join(args.notes) if args.notes else None,
        "download": {
            "url": download_url,
            "sizeBytes": apk_size,
            "sha256": apk_sha,
        },
        "patch": None,
        "commitSha": args.commit_sha,
    }
    put_contents(
        TOKEN, "version.json",
        json.dumps(version, ensure_ascii=False, indent=2) + "\n",
        cur.get("sha"),
        f"chore(channel): version.json → {args.tag}（发布流水线）[skip ci]",
    )

    # ── 4. patches.json ────────────────────────────────────────────────
    cur_patches = get_contents(TOKEN, "patches.json", not_found_ok=True)
    try:
        patches = json.loads(base64.b64decode(cur_patches["content"]).decode())
    except Exception:
        patches = {"schema": "abyss-patches-v1", "patches": []}
    patches["schema"] = "abyss-patches-v1"
    patches["generatedAt"] = published_at
    patches["latestTag"] = args.tag
    put_contents(
        TOKEN, "patches.json",
        json.dumps(patches, ensure_ascii=False, indent=2) + "\n",
        cur_patches.get("sha"),
        f"chore(channel): patches.json latestTag → {args.tag} [skip ci]",
    )

    # ── 5. changelog.json ──────────────────────────────────────────────
    cur_log = get_contents(TOKEN, "changelog.json", not_found_ok=True)
    try:
        changelog = json.loads(base64.b64decode(cur_log["content"]).decode())
    except Exception:
        changelog = {"schema": "abyss-changelog-v1", "latest": None, "history": []}
    changelog["schema"] = "abyss-changelog-v1"
    changelog["generatedAt"] = published_at
    if changelog.get("latest"):
        changelog.setdefault("history", []).insert(0, changelog["latest"])
    changelog["latest"] = {
        "versionName": version_name,
        "versionCode": args.version_code,
        "tag": args.tag,
        "date": today,
        "title": args.title or f"{args.tag} —— {args.notes[0] if args.notes else '例行发布'}",
        "notes": args.notes,
        "markdown": ("\n".join(f"- {n}" for n in args.notes)) if args.notes else None,
        "releaseUrl": f"https://github.com/{RELEASE_REPO}/releases/tag/{args.tag}",
    }
    changelog["history"] = changelog.get("history", [])[:20]
    put_contents(
        TOKEN, "changelog.json",
        json.dumps(changelog, ensure_ascii=False, indent=2) + "\n",
        cur_log.get("sha"),
        f"chore(channel): changelog.json 置顶 {args.tag} [skip ci]",
    )

    # ── 6. 写后自检（回读三份清单，防半写 / 错分支状态）────────────────
    for path_ in ("version.json", "patches.json", "changelog.json"):
        back = get_contents(TOKEN, path_, not_found_ok=True)
        if not back.get("content"):
            die(f"写后自检失败：{path_} 回读为空")
        try:
            data = json.loads(base64.b64decode(back["content"]).decode())
        except Exception:
            die(f"写后自检失败：{path_} 回读内容无法解析")
        if path_ == "version.json":
            if data.get("tag") != args.tag or data.get("versionCode") != args.version_code:
                die(f"写后自检失败：version.json 回读 tag/code 与本次发布不符（{data.get('tag')} / {data.get('versionCode')}）")
            if data.get("download", {}).get("sha256") != apk_sha:
                die("写后自检失败：version.json 回读摘要与本次 APK 不符")
        elif path_ == "patches.json":
            if data.get("latestTag") != args.tag:
                die(f"写后自检失败：patches.json latestTag ≠ {args.tag}")
        else:
            if not data.get("latest") or data["latest"].get("tag") != args.tag:
                die(f"写后自检失败：changelog.json latest ≠ {args.tag}")
        print(f"[publish] ✓ 写后自检通过 {path_}")


    # ── 7. 主开发仓镜像发布（v0.7.0；v0.7.1 调序：置于通道三件套与自检
    # 【之后】）—— 主仓 Release 会顺带创建本仓 tag，tag 推送会触发
    # release.yml；此时 version.json 已落盘，release.yml 的「已发布检测」
    # 能读到最新通道状态并优雅跳过（若镜像在前，则触发时通道还是旧值，
    # 检测失效 → 双流水线互相踩踏 —— v0.7.0 事故根因）。
    if args.mirror_to_repo:
        mirror_release_id = ensure_release_with_apk(
            repo=args.mirror_to_repo,
            tag=args.tag,
            title=args.title,
            notes=args.notes,
            apk_path=args.apk,
            asset_name=asset_name,
        )
        # 加固：镜像资产同样回读比对（与发布仓字节级一致才算双仓完成）
        verify_uploaded_asset(args.mirror_to_repo, mirror_release_id, asset_name, apk_sha)
        print(
            f"[publish] ✓ 主仓镜像发布完成 → "
            f"https://github.com/{args.mirror_to_repo}/releases/tag/{args.tag}"
        )

    print(f"[publish] ✓ 发布完成：{download_url}")
    print(f"[publish] ✓ version.json / patches.json / changelog.json 已同步（含写后自检）")


if __name__ == "__main__":
    main()
