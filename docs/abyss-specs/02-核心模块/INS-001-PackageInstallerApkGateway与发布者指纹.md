# INS-001 · PackageInstallerApkGateway 与发布者指纹

> 深渊工具 Abyss Tools · 软件规格说明书（核心）  
> 状态：**基线已实现** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 承载形态：**Android 实现** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `INS-001` |
| 所属域 | 核心 |
| 承载形态 | Android 实现 |
| 当前状态 | 基线已实现 |
| 优先级 / 里程碑 | P0 / M0 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | 信任链 · 签名 · apksigner |

## 1. 目标与范围

三层 fail-closed 信任链：同签名门禁 + PublisherSignatures.PINNED_SHA256 指纹硬校验 + CI apksigner 实测门禁。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/app/src/main/kotlin/com/abyss/tools/install/PackageInstallerApkGateway.kt`
- `Abyss-Tools/app/src/main/kotlin/com/abyss/tools/install/PublisherSignatures.kt`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 覆盖安装时待装 APK 签名集必须与在装版本一致；任一不可读 / 为空即拒绝。 |
| FR-002 | 宿主自更新（com.abyss.tools 覆盖安装）额外要求指纹命中内置常量。 |
| FR-003 | CI 发布前 apksigner verify 实测指纹与常量一致才允许上传。 |
| FR-004 | STATUS_PENDING_USER_ACTION 经系统 PendingIntent 转发，不触发 BAL 限制。 |
| FR-005 | Android 13+ 结果广播显式 RECEIVER_NOT_EXPORTED。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```kotlin
object PublisherSignatures {
    const val PINNED_SHA256 = "76:03:78:21:44:88:D6:20:79:78:BA:1E:37:E6:DC:82:AB:C2:DE:72:C9:F6:C6:7D:9B:44:95:15:8A:99:B7:69"
}
```

## 5. 失败语义与边界

- v0.5.0 期间 jks 曾入库公开，视为已泄露；当前决策保持身份不轮换，靠三层门禁兜底。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 用非发布签名 APK 触发安装被拒且给出人话错误。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- `core:common`（SemVer / UiState / 渠道常量）

**风险**

- 无新增外部依赖；行为变更需回归既有单测。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |

