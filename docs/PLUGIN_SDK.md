# 插件 SDK（docs/PLUGIN_SDK.md）

> 框架期（v0.1.0）契约已定型；APK 插件的跨进程桥（AIDL）与
> DexClassLoader 动态加载随 v0.2 发布流水线接入。本文件是插件仓库群
> （abyss-plugin-*）与宿主共同遵守的单一事实源。

## 插件形态

| 形态 | 载体 | 安装 | 更新 |
| --- | --- | --- | --- |
| **APK 插件** | 独立仓库构建的 APK | 系统安装器（v0.1.1 已接入：PackageInstaller + 同签名信任门 + 确认页转发） | GitHub Releases 资产 |
| **数据插件** | manifest + 数据资产目录 | `installDataPlugin`（校验后原子落盘，v0.1.1 已接入） | abyss-patch-v1 数据补丁（**已可用**） |

## 契约常量（plugin-sdk:plugin-api PluginContract）

- `HOST_API_VERSION = 2` —— 只增不减；插件以 `minHost` 声明需求，宿主
  比对不满足则拒绝加载（fail-closed）。**v2 增量（ADR-100 D-5）**：新增
  依赖解析与能力绑定（`PluginBroker`）；`minHost=1` 插件在 v2 宿主照常
  加载，`minHost=2` 插件在 v1 宿主 fail-closed 并提示升级；兼容期 ≥2 个
  宿主版本，期间 `depends` 字段在 v1 宿主被忽略（索引层标注「需新版宿主」）。
- `MANIFEST_FILE = "plugin.json"` —— 插件仓根与安装目录内的入口文件。
- `PLUGINS_DIR = "plugins"` —— 宿主 `filesDir` 下的插件根目录；安装态
  单一事实源：`filesDir/plugins/<id>/plugin.json` 存在即已安装。
- `PATCHES_INDEX = "patches/index.json"` —— 插件仓补丁通道固定读取点。
- `ID_PATTERN` —— 插件 id 形如 `abyss-plugin-terminal`（小写 + 连字符）。
- `CAPABILITY_PATTERN` / `CAPABILITY_LINUX_RUNTIME_V1`（v2 新增）——
  能力 id 形如 `linux-runtime-v1`；主版本变化必须新开 id，禁止原地改语义。
- `ACTION_PLUGIN` / `ACTION_LINUX_RUNTIME`（v2 新增）—— AIDL 双向桥与
  Linux 运行时服务的发现动作串。
- `META_HOST_ENTRY` / `META_UI_ENTRIES` / `META_CAPABILITIES` —— manifest
  meta-data 声明键（v2 新增能力声明键）。
- `GRANT_TTL_MAX_SEC = 300`（v2 新增）—— 跨插件令牌有效期上限（ARC-102 FR-005）。

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
  "shizuku": {"ownAuth": true, "reason": "为什么需要独立授权（人话）"},
  "entry": "com.abyss.tools.plugin.terminal.TerminalPlugin",
  "repo": "abyss-tool/abyss-plugin-terminal",
  "patchChannel": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-terminal/main/patches/index.json",
  "roadmap": ["v0.2 …", "v0.3 …"]
}
```

字段纪律：`version` / `minApp` 必须是合法 SemVer；`repo` 末段必须等于
`id`；`category` ∈ {terminal, system, sandbox, disguise, media,
automation, desktop, network, data, tools, dev}。

`shizuku` 为可选声明（缺省 null = 不涉及）：`ownAuth: true` 表示本插件
为独立 APK 且需用户在 Shizuku 内**单独授权一次**（Shizuku 授权严格
per-app，宿主授权不能继承给独立插件包名）—— 详情页会展示独立授权引导
与 `reason`；运行在宿主进程内的数据插件不声明此字段。**HOST_API v2 收敛
（LIN-005）**：引擎级授权持有方（`abyss-plugin-linux`）必填；纯编排型
消费方（如应用构建器）**必须删除**本声明（CI 门禁「出现即失败」）。

## plugin.json v2 增量字段（DC-100 §2 —— 全部可选，v1 解析器天然兼容）

```json
{
  "provides": [{"capability": "linux-runtime-v1", "since": "0.2.0", "stable": true}],
  "depends": [{
    "pluginId": "abyss-plugin-linux",
    "capability": "linux-runtime-v1",
    "versionRange": ">=0.2.0 <1.0.0",
    "optional": false,
    "reason": "构建需要 Linux 运行环境，由 Linux 插件统一提供。"
  }],
  "capabilities": {"linux-runtime-v1": {"domains": ["PROOT_USERLAND", "QEMU_VM"]}},
  "assetChannel": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/index.json"
}
```

字段纪律（DC-100 §3）：`provides[].capability` 形如 `^[a-z0-9-]+-v[0-9]+$`
且插件内唯一；`depends[].pluginId` 符合 `ID_PATTERN` 且不得指向归档 id；
`versionRange` 为 SemVer 区间（统一 SemVer 比较，禁止 `split('.')`）；
`optional=false` 时 `reason` 必填且 ≥10 字符；`assetChannel` 过
ChannelPolicy 域白名单与重定向复检（abyss-asset-v1 大资产通道，与
abyss-patch-v1 严格分离）。消费方永不直连提供方 —— 跨插件调用一律经宿主
`PluginBroker` 中介（`getCapability` 四重校验 + `SessionGrant` 令牌，
详见 `11-Linux插件解耦/ARC-102`）。

## 插件仓库骨架（三个引擎级插件仓库同构）

```
abyss-plugin-<name>/
├── README.md               # 简介 / 权限 / 安装 / 补丁更新说明 / 路线图
├── plugin.json             # manifest（CI 校验）
├── CHANGELOG.md            # 版本日志（CI 校验含当前版本条目）
├── ROADMAP.md              # 排期
├── patches/index.json      # 补丁通道（CI 校验 latest == version）
├── tools/validate_plugin.py
├── tools/make_patch.py     # 数据补丁打包器（abyss-patch-v1）
├── tools/publish_patch.py  # Release + 资产上传 + 通道滚动
├── .github/workflows/plugin-ci.yml
├── .github/workflows/patch-release.yml   # 补丁发布流水线（dispatch）
└── LICENSE                 # UGRL v1.0
```

插件 CI（`plugin-ci.yml`）为确定性校验：manifest 必填字段 / SemVer /
repo 与仓库名一致 / changelog 含当前版本 / 补丁通道 latest 一致。
全绿才算一次合法的插件仓库提交。

### 补丁发布流水线（patch-release.yml）

Actions → Patch Release → Run workflow（填目标版本 + 变更要点）：

1. `make_patch.py`：校验 SemVer 升序 → 打 `abyss-patch-v1` 增量包
   （载荷 = 升版后 plugin.json + changelog/<版本>.md）→ 同步升版仓库
   `plugin.json` / `CHANGELOG.md`；产物在 `patches/data/`（不入库）；
2. `publish_patch.py`：创建 `v<版本>` GitHub Release + 上传 zip 资产 →
   滚动 `patches/index.json`（新条目置顶，latest → 新版本）；
3. `validate_plugin.py` 三处一致性门禁 → bot 提交（`[skip ci]`）。

宿主侧即时可消费：插件详情页 → 「下载并应用补丁」。

## 宿主侧消费链路（core:plugin）

```
Abyss-Plugins/plugins.json（中心索引，3 个引擎级插件聚合 —— 应用构建器 / 病毒拦截 / 虚拟机，sync-index 自动滚动）
    → PluginRepository.fetchIndex()            # 插件中心页（含「我的插件」）
    → PluginDetailScreen                       # 详情 + 补丁时间线 + 应用闭环
    → PluginInstaller.installDataPlugin()      # 数据形态：校验 + 原子落盘
    → PluginInstaller.downloadApk()            # APK 形态：资产未发布 → 人话错误
    → PluginInstaller.verifyApk()              # SHA-256 门禁
    → PackageInstallerApkGateway.install()     # 真实系统安装（:app 注入）
    → PluginInstaller.updatablePatch()         # 选链：from 命中取 to 最大
    → PluginInstaller.applyDataPatch()         # 数据补丁（备份 + 回滚）
    → PluginInstaller.uninstall()              # 卸载清目录（确认弹层）
```

## v0.2 预告（宿主桥）

- AIDL 双向桥（`IAbyssPlugin` / `IAbyssPluginHost`）+ PackageManager
  service 发现（`PluginContract.ACTION_PLUGIN`）
- 同签名信任门 + UI 入口 meta-data（`META_UI_ENTRIES`）
- DexClassLoader 动态加载（数据插件的进程内形态）
