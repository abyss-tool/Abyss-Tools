# 架构（docs/ARCHITECTURE.md）

## 分层总览

```
┌───────────────────────────────────────────────────────────────┐
│ :app 主壳                                                      │
│   AbyssRoot（Crossfade 返回栈） · ServiceRegistry（手动 DI）   │
│   内容层 = 玻璃采样源（layerBackdrop）→ 玻璃件悬浮其上          │
├───────────────┬───────────────┬───────────────────────────────┤
│ :feature:dashboard │ :feature:navhub │ :feature:plugins │ :feature:settings │
│      （纯 Compose 界面层，依赖注入经由参数，不感知 DI 容器）      │
├───────────────┴───────────────┴───────────────────────────────┤
│ :core:designsystem（M3 主题 + 液态玻璃组件）                    │
│    └── :vendor:backdrop（Kyant0/AndroidLiquidGlass，Apache-2.0）│
├───────────────────────────────────────────────────────────────┤
│ :core:plugin（索引/安装器/插件补丁通道）  :core:update（补丁引擎）│
│    └── :plugin-sdk:plugin-api（契约）      └── :core:common     │
│                                                （SemVer/目录/渠道）│
└───────────────────────────────────────────────────────────────┘
```

## 依赖方向（单向，无环）

- `app → feature:* → designsystem → vendor:backdrop`
- `feature:* → core:{common,plugin,update}`
- `core:plugin → core:update → core:common`；`core:plugin → plugin-sdk`
- 纯 JVM 模块（common / update / plugin / plugin-sdk）：单测零 Android
  依赖，CI 上 `./gradlew test` 即全量行为验证。

## 导航与返回栈

框架期不引 navigation-compose：`AbyssBackStack` 自维护
`mutableStateOf(List<Screen>)`，Crossfade 切页，BackHandler 手势返回。
底栏仅顶层 tab 显示（stack.size == 1），详情页沉浸全屏 —— 玻璃采样源
在内容根上，任何页面滚过玻璃件下方都会被真实采样。

## 液态玻璃系统（三层能力）

| 档位 | 条件 | 渲染 |
| --- | --- | --- |
| Backdrop | 有采样源 + API 31+ | blur +（API 33+）AGSL lens 折射/色散 + 霜面 tint + 边缘受光 + 内阴影 |
| Frosted | 无源 或 API < 31 | 霜面渐变 + 边缘光（零 GPU 管线，诚实降级） |

纪律：`drawBackdrop` 的全部 lambda 记忆化（ModifierNodeElement 对
lambda 按身份比较 —— 重组期新建 lambda 会引发 draw 失效风暴）；效果
级 `runCatching`（OEM RenderEffect/AGSL 差异单项降级，进程存活优先）。

## 更新体系（双仓库，全自动）

```
开发仓 Abyss-Tools（源码 + CI 门禁：双变体构建 + 单测 + 清单/版本同步门禁）
    │ git tag v*（升版三处同步：build.gradle.kts ↔ AppInfo）
    ▼
release.yml：assembleRelease（keystore/abyss-release.jks 恒定签名）
    │ tools/publish_release.py（RELEASE_TOKEN）
    ▼
发布仓 Abyss-Tools-Release
    ├─ GitHub Release v<版本> + APK 资产（sha256 / size 入清单）
    ├─ version.json   ← 客户端「检查更新」固定读取点
    ├─ patches.json   ← latestTag 滚动（vcdiff 二进制增量 v0.2 接入）
    └─ changelog.json ← 旧 latest 下沉 history，新版本置顶
    ▼
App UpdateChecker（比对 SemVer）→ 增量命中 / 整包
    → 设置页「下载并安装」：下载 → SHA-256 校验 → PackageInstaller 安装
      （同签名门禁恒定通过；未知来源确认页自动转发）
```

数据层补丁（abyss-patch-v1，插件 manifest / 技能目录）**已可用**：
下载 → SHA-256 → 备份 → 应用 → 失败回滚。APK 二进制增量（vcdiff 合成 +
覆盖安装）属 v0.2 发布流水线。详见 [UPDATE_CHANNEL.md](UPDATE_CHANNEL.md)。

## 插件体系

- 中心索引：`Abyss-Plugins/plugins.json`（8 个重型插件聚合；
  `sync-index.yml` 每日 / 手动自动聚合，APK 资产自动探测）
- 插件仓约定：`plugin.json`（manifest）+ `patches/index.json`（补丁通道）
  + `tools/validate_plugin.py`（CI 门禁）+ `patch-release.yml`
  （数据补丁发布流水线：make_patch → publish_patch → 三处一致性门禁）
- 分界纪律（v0.2 定型）：**只有「重」的能力才做成插件**（原生工具链 / 独立进程 / 大型框架）；轻量功能（应用管理 / Shizuku 面板 / 任务蓝图 / 自动化 / 灵动岛 / 壁纸 / 二维码）一律 `ToolStatus.BUILT_IN` 内置主壳，随版本更新获得
- Shizuku 授权声明：独立 APK 插件可在 manifest 声明 `shizuku.ownAuth`（per-app 授权，不继承宿主）—— 插件详情页展示独立授权引导
- 宿主契约：`PluginContract.HOST_API_VERSION`（只增不减），
  `filesDir/plugins/<id>/plugin.json` 为安装态单一事实源
- 安装流水线（v0.1.1 起全链路真实）：
  - APK 形态：下载 → SHA-256 校验 → `PackageInstallerApkGateway`
    （同签名门禁 + 未知来源确认页转发 + 结果广播等待 + 超时 abandon）
  - 数据形态：`installDataPlugin`（id 形态 / SemVer / 宿主下限校验 →
    manifest 原子落盘）→ 版本账本（installedVersion）→ 补丁应用闭环
    （updatablePatch 选链 → applyDataPatch）→ 卸载清目录

详见 [PLUGIN_SDK.md](PLUGIN_SDK.md)。

## 安全基线

- targetSdk 钉 28（终端引擎 SELinux 红线，Termux 同款决策；
  `lint { disable += "ExpiredTargetSdkVersion" }` —— 侧载分发渠道语义不适用）
- 插件资产 fail-closed：签名 / 校验 / 路径穿越三重门
- 发布签名恒定：入库 keystore（指纹不变 → 覆盖安装零冲突）
- 免责声明内置 App（设置 → 许可与免责声明）+ 仓库 LICENSE（UGRL）
- R8 混淆：v0.2 发布流水线接入（框架期 minify 关闭，CI 面最小）
