# 插件 SDK（docs/PLUGIN_SDK.md）

> 框架期（v0.1.0）契约已定型；APK 插件的跨进程桥（AIDL）与
> DexClassLoader 动态加载随 v0.2 发布流水线接入。本文件是插件仓库群
> （abyss-plugin-*）与宿主共同遵守的单一事实源。

## 插件形态

| 形态 | 载体 | 安装 | 更新 |
| --- | --- | --- | --- |
| **APK 插件** | 独立仓库构建的 APK | 系统安装器（v0.2：PackageInstaller + 同签名信任门） | GitHub Releases 资产 |
| **数据插件** | manifest + 数据资产目录 | 免安装，宿主目录落位 | abyss-patch-v1 数据补丁（**已可用**） |

## 契约常量（plugin-sdk:plugin-api PluginContract）

- `HOST_API_VERSION = 1` —— 只增不减；插件以 `minHost` 声明需求，宿主
  比对不满足则拒绝加载（fail-closed）。
- `MANIFEST_FILE = "plugin.json"` —— 插件仓根与安装目录内的入口文件。
- `PLUGINS_DIR = "plugins"` —— 宿主 `filesDir` 下的插件根目录；安装态
  单一事实源：`filesDir/plugins/<id>/plugin.json` 存在即已安装。
- `PATCHES_INDEX = "patches/index.json"` —— 插件仓补丁通道固定读取点。
- `ID_PATTERN` —— 插件 id 形如 `abyss-plugin-terminal`（小写 + 连字符）。

## plugin.json schema（v1）

```json
{
  "id": "abyss-plugin-terminal",
  "name": "终端引擎",
  "version": "0.1.0",
  "minHost": 1,
  "minApp": "0.1.0",
  "category": "terminal",
  "summary": "……",
  "icon": "🖥",
  "permissions": ["INTERNET"],
  "entry": "com.abyss.tools.plugin.terminal.TerminalPlugin",
  "repo": "Ultra-Guru/abyss-plugin-terminal",
  "patchChannel": "https://raw.githubusercontent.com/Ultra-Guru/abyss-plugin-terminal/main/patches/index.json",
  "roadmap": ["v0.2 …", "v0.3 …"]
}
```

字段纪律：`version` / `minApp` 必须是合法 SemVer；`repo` 末段必须等于
`id`；`category` ∈ {terminal, system, sandbox, disguise, media,
automation, desktop, network, data, tools}。

## 插件仓库骨架（十三个大插件仓库同构）

```
abyss-plugin-<name>/
├── README.md               # 简介 / 权限 / 安装 / 补丁更新说明 / 路线图
├── plugin.json             # manifest（CI 校验）
├── CHANGELOG.md            # 版本日志（CI 校验含当前版本条目）
├── ROADMAP.md              # 排期
├── patches/index.json      # 补丁通道（CI 校验 latest == version）
├── tools/validate_plugin.py
├── .github/workflows/plugin-ci.yml
└── LICENSE                 # UGRL v1.0
```

插件 CI（`plugin-ci.yml`）为确定性校验：manifest 必填字段 / SemVer /
repo 与仓库名一致 / changelog 含当前版本 / 补丁通道 latest 一致。
全绿才算一次合法的插件仓库提交。

## 宿主侧消费链路（core:plugin）

```
Abyss-Plugins/plugins.json（中心索引，13 插件聚合）
    → PluginRepository.fetchIndex()            # 插件中心页
    → PluginDetailScreen                       # 详情 + 补丁时间线
    → PluginInstaller.downloadApk()            # 资产未发布 → 人话错误（fail-closed）
    → PluginInstaller.verifyApk()              # SHA-256 门禁
    → ApkInstallGateway.install()              # Android 侧（v0.2 PackageInstaller）
    → PluginInstaller.applyDataPatch()         # 数据补丁（备份 + 回滚，已可用）
```

## v0.2 预告（宿主桥）

- AIDL 双向桥（`IAbyssPlugin` / `IAbyssPluginHost`）+ PackageManager
  service 发现（`PluginContract.ACTION_PLUGIN`）
- 同签名信任门 + UI 入口 meta-data（`META_UI_ENTRIES`）
- DexClassLoader 动态加载（数据插件的进程内形态）
