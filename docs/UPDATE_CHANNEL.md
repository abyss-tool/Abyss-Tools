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

插件详情页实时读取该通道展示补丁时间线；数据补丁经
`PluginInstaller.applyDataPatch()` 应用到 `filesDir/plugins/<id>/`。

## 校验门禁

- 开发仓 CI：`tools/validate_manifests.py` 校验测试夹具（通道契约镜像）；
- 发布仓 CI：`validate-manifests.yml` 校验三份清单一致性；
- 插件仓 CI：`tools/validate_plugin.py` 校验 plugin.json 与补丁通道。
