> 深渊工具 Abyss Tools · 软件规格说明书（功能）  
> 状态：**已实现（v0.9.14）** ｜ 优先级：**P1** ｜ 里程碑：**M1** ｜ 承载形态：**主壳内置** ｜ 基线：**v0.9.13 / versionCode 36** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `FEAT-037` |
| 所属域 | 功能 |
| 承载形态 | 主壳内置 |
| 当前状态 | 已实现（v0.9.14） |
| 优先级 / 里程碑 | P1 / M1 |
| 标签 | 深渊agent · 直达下载 · 多镜像 |
| 关联规格 | `FEAT-003`（插件中心页承载）、`SRS-003`、`DC-001`（下载一律 sha256 校验）、`N-ENG-008` |
| 建议落盘路径 | `docs/abyss-specs/03-功能模块/FEAT-037-深渊agent置顶直达.md` |

---

## 1. 目标与范围

插件中心页**置顶**「深渊agent」卡（Ultra-Guru/Android-Guru-Agent，用户自有姊妹项目）：从其发布仓固定通道拉取最新版本清单，**多镜像**下载 APK（复用 MirrorRouter 镜像链），SHA-256 强校验后交系统安装器。

| 项 | 值 |
| --- | --- |
| 通道清单 | `https://raw.githubusercontent.com/Ultra-Guru/Android-Guru-Agent-Release/main/version.json`（既有白名单 host，与深渊工具 version.json 同族 schema） |
| 清单字段 | `versionName / versionCode / tag / publishedAt / download.{arm64,universal}{url,sizeBytes,sha256} / signingCertSha256` |
| 镜像 | 下载 URL（github.com releases asset）经既有 MirroredTransport：ghfast / ghproxy.net / gh-proxy 前缀 + raw 域镜像 + 直连兜底 |
| 安装 | `ApkInstallGateway.install(file)`（插件 APK 同路径 —— 非宿主自更新，无发布者指纹门禁；sha256 由本卡管线强校验） |

**范围内**：`AgentReleaseManager`（core:update 纯 JVM：清单拉取解析 / ABI 变体选择 / 下载校验 / 网关安装）、插件页置顶卡 UI（UiState 三态 + 确认对话框 + 进度）、ServiceRegistry 装配。
**范围外（硬边界）**：不做本机已装版本比对（可能未装 / 包名未知，诚实展示通道版本）；不做 vcdiff 增量（整包下载）；不改插件索引管线与 `FEAT-003` 六条 FR；Guru 清单非 minisig 签名 —— 以通道内 `download.*.sha256` 为完整性锚（fail-closed，校验失败拒装）。

---

## 3. 功能需求

| 编号 | 需求描述 |
| --- | --- |
| FR-001 | 插件中心页首屏顶部渲染「深渊agent」置顶卡（置顶于镜像区之前）：名称 + 副标题 `Ultra-Guru/Android-Guru-Agent` + 通道最新版本 + 发布时间 + 变体（arm64 默认 / universal 可选）+ 大小。 |
| FR-002 | 清单拉取走 UiState 三态（Loading / Error+重试 / Data），加载中禁止假数据；解析缺失 `download` 关键字段 = fail-closed 报错展示，不猜。 |
| FR-003 | ABI 变体：`Build.SUPPORTED_ABIS` 含 `arm64-v8a` 默认选 arm64，用户可手动切 universal；两变体均展示大小与各自 sha256 前 8 位。 |
| FR-004 | 「下载并安装」前弹确认对话框：版本 / 大小 / sha256 摘要 / 来源说明（姊妹项目独立 APK，非插件）；下载进度条实时百分比；校验失败删缓存文件并人话报错（fail-closed 拒装）。 |
| FR-005 | 下载走 MirrorRouter 多镜像自动择优与回退；安装走 ApkInstallGateway（阻塞调用切 IO 线程），结果（含系统取消）人话回显。 |
| FR-006 | 管线全部纯 JVM 可单测（core:update），Android 依赖隔离在网关与 UI 后；ServiceRegistry 单点装配。 |

## 6. 验收标准

| 编号 | 验收条件 |
| --- | --- |
| AC-001 | 插件页顶部可见深渊agent卡；断网时 Error+重试；通道可达时展示真实版本。 |
| AC-002 | 下载中断点续传 / 镜像回退生效；sha256 不符拒绝进入安装阶段。 |
| AC-003 | 纯 JVM 单测：AgentReleaseManifest 解析（正常 / 缺 download 字段 / 坏 JSON 均覆盖）。 |
| AC-004 | CI 全绿。 |

## 8. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 0.1 | 2026-11-22 | 创建；用户需求「插件页面置顶 Android-Guru-Agent 项目，名为深渊agent，可在软件中下载 GitHub release，多搞一些镜像」。 |
