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

## 更新体系（双仓库）

```
开发仓 Abyss-Tools（源码 + CI 门禁）
    │ CI 发布（v0.2 起接入自动流水线）
    ▼
发布仓 Abyss-Tools-Release
    ├─ version.json   ← 客户端「检查更新」固定读取点
    ├─ patches.json   ← 跨版本链式增量索引
    └─ changelog.json ← 版本叙事
    ▼
App UpdateChecker（比对 SemVer）→ 增量补丁命中 / 整包
```

数据层补丁（abyss-patch-v1，插件 manifest / 技能目录）**已可用**：
下载 → SHA-256 → 备份 → 应用 → 失败回滚。APK 二进制增量（vcdiff 合成 +
覆盖安装）属 v0.2 发布流水线。详见 [UPDATE_CHANNEL.md](UPDATE_CHANNEL.md)。

## 插件体系

- 中心索引：`Abyss-Plugins/plugins.json`（13 个大插件聚合）
- 插件仓约定：`plugin.json`（manifest）+ `patches/index.json`（补丁通道）
  + `tools/validate_plugin.py`（CI 门禁）
- 宿主契约：`PluginContract.HOST_API_VERSION`（只增不减），
  `filesDir/plugins/<id>/plugin.json` 为安装态单一事实源
- 安装流水线（框架期已就绪）：下载 → SHA-256 校验 → ApkInstallGateway
  （Android 侧占位，v0.2 接 PackageInstaller + 同签名信任门）

详见 [PLUGIN_SDK.md](PLUGIN_SDK.md)。

## 安全基线

- targetSdk 钉 28（终端引擎 SELinux 红线，Termux 同款决策）
- 插件资产 fail-closed：签名 / 校验 / 路径穿越三重门
- 免责声明内置 App（设置 → 许可与免责声明）+ 仓库 LICENSE（UGRL）
- R8 混淆：v0.2 发布流水线接入（框架期 minify 关闭，CI 面最小）
