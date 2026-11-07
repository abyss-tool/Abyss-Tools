# 更新通道规范（docs/UPDATE_CHANNEL.md）

> 对齐 Android-Guru-Agent-Release 的热更新体系设计；本仓是**开发仓侧**
> 的客户端契约与规范。发布仓侧手册见 Abyss-Tools-Release/HOT_UPDATE.md。

## 通道文件（发布仓 main 分支根）

### version.json —— 客户端检查更新的固定读取点

```json
{
  "schema": "abyss-version-v1",
  "versionName": "0.1.0",
  "versionCode": 1,
  "tag": "v0.1.0",
  "publishedAt": "2026-10-30T12:00:00Z",
  "notes": "……",
  "download": { "url": "…", "sizeBytes": 0, "sha256": "…" },
  "patch": { "fromTag": "v0.0.9", "toTag": "v0.1.0", "url": "…", "sizeBytes": 0, "sha256": "…" }
}
```

- `download` / `patch` 可空（框架预览期常态）：客户端视为「有新版本但
  资产未发布」，给出人话提示。
- `tag` 缺省按 `v{versionName}` 推导；`versionName` 必须是合法 SemVer。

### patches.json —— 跨版本链式增量索引

```json
{
  "schema": "abyss-patches-v1",
  "latestTag": "v0.1.0",
  "patches": [
    { "fromTag": "v0.0.9", "toTag": "v0.1.0", "url": "…", "sizeBytes": 0, "sha256": "…" }
  ]
}
```

### changelog.json —— 版本叙事（设置页 / 更新弹层消费）

```json
{
  "schema": "abyss-changelog-v1",
  "generatedAt": "2026-10-31T01:55:29Z",
  "latest": {
    "versionName": "0.1.1",
    "versionCode": 2,
    "tag": "v0.1.1",
    "date": "2026-10-31",
    "title": "v0.1.1 —— ……",
    "notes": ["要点一", "要点二"],
    "markdown": "## v0.1.1\n- 要点一",
    "releaseUrl": "https://github.com/abyss-tool/Abyss-Tools-Release/releases/tag/v0.1.1"
  },
  "history": [ { "…": "旧版本 latest 条目下沉" } ]
}
```

客户端 `UpdateManager.fetchChangelog()` 拉取；设置页「更新日志」弹层渲染
`entries`（latest 置顶 + history）。`markdown` 与 `notes` 内容一致，渲染端
按能力选择。

## 下载镜像系统（v0.7.0 起）

国内裸连 GitHub（Release 资产 / raw 文本）极不稳定。v0.7.0 起所有远端
拉取（更新检查 / 插件索引 / APK 与补丁下载）经 `MirroredTransport` 走
镜像链：

- **镜像目录**（`core:common MirrorCatalog`）：ghfast.top / ghproxy.net /
  gh-proxy.com / ghproxy.cn（PREFIX 反代，双形态）+ raw.gitmirror.com
  （raw 域等价替换）+ GitHub 直连（恒为兜底）。
- **延迟实测**（插件中心「测速」）：对真实资产路径 HEAD + Range 探测
  TTFB，全镜像并发，结果驱动「自动（延迟择优）」排序。
- **回退链**：下载按镜像序逐个尝试，单镜像失败自动换下一个（2 分钟
  失败冷却记忆防全链重复超时）；直连恒在链尾兜底。
- **信任锚不松动**：镜像域收敛在 `FetchHostPolicy` 白名单（PREFIX 形态
  递归校验内嵌的 GitHub 白名单 URL）；一切资产仍强制 SHA-256 校验
  （fail-closed）—— 镜像被劫持最多表现为「下载失败 / 校验不过」，
  不可能植入被篡改的载荷。

## 自动发布（v0.7.0 起，auto-release.yml）

每次 PR 合并（push 到 main）自动发版：版本推进（AppInfo 未发布的新
VERSION_NAME 原样采用；已发布过则 patch 自动递增，versionCode 恒 =
通道现值 + 1）→ 写回 AppInfo.kt 提交（`[skip ci]` 防自触发）→ 签名
构建 → 发布者指纹门禁 → **双仓发布**：

- 发布仓（Abyss-Tools-Release）：Release + APK 资产 + version.json /
  patches.json / changelog.json 通道三件套（客户端检查更新的固定读取点）；
- 主仓（Abyss-Tools）：同 tag Release + 同 APK 资产（APK 双仓留存，
  不单点在发布仓）。

跳过语义：commit message 含 `[skip release]` → 本次不发布。tag 触发的
手工稳定发布（release.yml）继续并存。

## 发布流水线（v0.1.1 起全自动）

```
开发仓升版（build.gradle.kts ↔ AppInfo 三处同步，CI 门禁校验）
  └ git tag v* && git push --tags
      └ release.yml：assembleRelease（keystore/meng411722.jks 恒定签名）
          └ tools/publish_release.py（RELEASE_TOKEN secret）
              ├ 发布仓创建 GitHub Release + APK 资产（幂等：同名先删）
              ├ version.json：download{url,sizeBytes,sha256} + notes + commitSha
              ├ patches.json：latestTag 滚动
              └ changelog.json：旧 latest 下沉 history，新版本置顶
```

发布要点来源：`docs/release-notes/v<版本>.md`（一行一条）或 dispatch
输入（分号分隔）。

客户端闭环：安装 v0.1.0 的设备 → 设置页「检查补丁更新」→ 发现 v0.1.1
→「下载并安装整包」→ 下载 → SHA-256 校验 → PackageInstaller 安装
（入库 keystore 同签名 → 覆盖安装零冲突）。

## 客户端决策表（core:update UpdateChecker）

| 条件 | 结果 |
| --- | --- |
| `latest <= current` | UpToDate |
| `latest > current` 且 `patch.fromTag == 当前 tag` | Available（**增量**） |
| `latest > current` 其余情形 | Available（整包 / 资产未发布） |
| 网络 / JSON / 版本号非法 | Failed（fail-closed，绝不猜测） |

## 数据补丁格式（abyss-patch-v1，已可用）

> 适用：插件 manifest / 技能目录 / 配置等**数据资产**的增量更新。
> APK 二进制增量（vcdiff 合成 + 覆盖安装）属 v0.2 发布流水线，另册。

补丁包是一个 zip：

```
patch-manifest.json          # 必须在包根
<相对路径>                    # 载荷，与 manifest.files[].path 对应
```

`patch-manifest.json`：

```json
{
  "schema": "abyss-patch-v1",
  "target": "abyss-plugin-terminal",
  "fromVersion": "0.1.0",
  "toVersion": "0.1.1",
  "files": [
    { "path": "plugin.json", "action": "replace" },
    { "path": "skills/new.json", "action": "add" },
    { "path": "legacy/config.json", "action": "delete" }
  ]
}
```

应用纪律（`ZipPatchApplier`，全部有单测覆盖）：

1. 无 manifest / schema 不符 / target 空 → **整包拒绝**；
2. 路径穿越（`../` / 绝对路径）→ **整包拒绝**，目标目录零触碰；
3. 载荷缺失 → **整包拒绝**；
4. 任意写失败 → 从备份**回滚全部已改文件**；
5. manifest 未列 files → 按包内载荷默认 replace（幂等友好）。

## 插件补丁通道（每个插件仓独立）

`patches/index.json`（插件仓 main 分支）：

```json
{
  "schema": "abyss-plugin-patches-v1",
  "plugin": "abyss-plugin-terminal",
  "latest": "0.1.0",
  "patches": [
    { "from": "0.1.0", "to": "0.1.1", "url": "…", "sha256": "…", "notes": "…" }
  ]
}
```

插件详情页实时读取该通道展示补丁时间线，并给出应用闭环（v0.1.1 起）：

- `PluginInstaller.updatablePatch()` —— 选链：`from` 命中本机版本，取
  `to` 最大的补丁；
- 「下载并应用补丁」：下载 → SHA-256 校验 → 备份 → 应用（
  `PluginInstaller.applyDataPatch()`）→ 失败自动回滚 → 版本账本随
  `plugin.json` 升级 → `PatchReport` 摘要（替换 / 新增 / 删除计数）。

### 插件补丁发布（patch-release.yml，已上线）

插件仓库 Actions → Patch Release → 填目标版本与变更要点：

1. `tools/make_patch.py` 打 `abyss-patch-v1` 增量包（载荷 = 升版后
   manifest + changelog/<版本>.md），同步升版仓库 `plugin.json` /
   `CHANGELOG.md`；
2. `tools/publish_patch.py` 创建 `v<版本>` GitHub Release + 上传 zip 资产
   → 滚动 `patches/index.json`（新条目置顶、latest → 新版本）；
3. `tools/validate_plugin.py` 三处一致性门禁 → bot 提交。

发布后 `Abyss-Plugins` 的 `sync-index.yml`（每日 12:00 UTC / 手动）自动把
新版本汇入 `plugins.json`。

## 校验门禁

- 开发仓 CI：`tools/validate_manifests.py` 校验测试夹具（通道契约镜像）、
  版本三处同步（build.gradle.kts ↔ AppInfo）与 tools 脚本语法；
- 发布仓 CI：`validate-manifests.yml` 校验三份清单一致性；
- 插件仓 CI：`tools/validate_plugin.py` 校验 plugin.json 与补丁通道；
- 发布流水线内置：`release.yml` 校验 tag ↔ build.gradle 版本一致；
  `patch-release.yml` 校验插件三处一致性后再提交。
