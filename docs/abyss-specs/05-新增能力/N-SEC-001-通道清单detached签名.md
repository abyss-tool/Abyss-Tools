# N-SEC-001 · 通道清单 detached 签名

> 深渊工具 Abyss Tools · 软件规格说明书（新增能力）  
> 状态：**规划中 · 待实现** ｜ 优先级：**P0** ｜ 里程碑：**M1** ｜ 承载形态：**新增能力** ｜ 基线：**v0.9.5 / versionCode 28** ｜ 编制日期：**2026-11-21**

| 项 | 值 |
| --- | --- |
| 规格编号 | `N-SEC-001` |
| 所属域 | 新增能力 |
| 承载形态 | 新增能力 |
| 当前状态 | 规划中 · 待实现 |
| 优先级 / 里程碑 | P0 / M1 |
| 组织与基线 | abyss-tool 组织 19 仓库克隆研究；v0.9.5 / versionCode 28 |
| 标签 | M1 · 信任链 · Ed25519 |

## 1. 目标与范围

补上 SECURITY.md 明确承认的缺口：version.json / patches.json / plugins.json 目前无 detached 签名，发布仓写权限是信任单点。

## 2. 现状锚点（克隆研究实测）

- `Abyss-Tools/docs/SECURITY.md`
- `Abyss-Tools-Release/version.json`

> 研究口径：2026-11-21 对 `github.com/abyss-tool` 组织下 19 个公开仓库（主仓 / 发布仓 / 插件中心 / 13 归档插件仓 / 3 引擎级插件仓）全量拉取，共 511 个文件；主仓 296 个文件、约 22 个 feature 模块。

## 3. 功能需求

| 编号 | 需求描述（原子、可验证） |
|---|---|
| FR-001 | 采用 Ed25519 / minisign 对三份清单生成 detached 签名，公钥内置客户端常量。 |
| FR-002 | 客户端拉取清单后先验签再解析，验签失败整页 Error（fail-closed）。 |
| FR-003 | 私钥仅存 GitHub Actions secrets，禁止入仓；轮换需提供旧公钥兼容期。 |
| FR-004 | 插件仓 patches/index.json 同样纳入验签范围。 |
| FR-005 | 验签失败次数与原因写入本地诊断日志（不外传）。 |

> 纪律：每条 FR 必须至少被一条 AC 或一个自动化用例覆盖；下表 AC 为关键验收条件，完整覆盖关系由 `QA-008 需求追溯矩阵` 与 spec-lint 登记校验。需求变更需同步更新本文件与对应的测试夹具（`src/test/resources` 为通道契约镜像）。

## 4. 契约与接口

```
version.json + version.json.minisig
patches.json + patches.json.minisig
plugins.json + plugins.json.minisig
客户端：fetch → verify(内置公钥) → parse；验签失败即拒绝，绝不降级为「无签名也放行」
```

## 5. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 拒绝并给人话错误，不猜测、不静默降级。
- 远端数据统一走 `UiState`（Loading / Error / Data），加载中禁止展示假数据。

## 6. 验收标准

| 编号 | 验收条件 |
|---|---|
| AC-001 | 篡改清单任意字节后客户端拒绝且给出人话错误。 |

> 验收方法：纯 JVM 单测（`./gradlew test`）/ CI 门禁脚本 / 真机回归清单（Android 15 与 16 各一台）/ 通道清单校验，按单元性质择一或组合。

## 7. 依赖与风险

**依赖**

- SRS-003
- UPD-006

**风险**

- 密钥轮换会打断存量客户端，需设计双公钥过渡期。

## 8. 变更记录

| 版本 | 日期 | 说明 |
|---|---|---|
| 0.1 | 2026-11-21 | 基于 abyss-tool 组织 19 仓库克隆研究创建；对齐 v0.9.5 基线。 |

