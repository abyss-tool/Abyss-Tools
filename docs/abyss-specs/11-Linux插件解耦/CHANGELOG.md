# 规格集变更记录 · 深渊工具 Linux 插件解耦设计规格

> 本文件遵循「一版一条」纪律；每个发布版本一个 `## v*` 条目，与代码仓 CHANGELOG 纪律一致。

## v1.0（2026-11-22）

- **首版发布**：基于 `github.com/abyss-tool` 组织 19 个公开仓库全量克隆研究，产出「Linux 插件与应用构建器解耦」完整设计规格与任务方案，共 23 份子规格 + 总索引 + 变更记录。
- **核心决策**：新建 `abyss-plugin-linux`（唯一持有 rootfs / 镜像 / PRoot / QEMU / Shizuku 授权），应用构建器经 `depends` + 宿主 `PluginBroker` 依赖它；`abyss-plugin-virtual-machine` 归档收编为 QEMU 执行域。
- **架构增量**：HOST_API 1 → 2（依赖解析与能力绑定）、双执行域统一 `RuntimeSession` 抽象、受限 AIDL 代理 + `SessionGrant` 令牌、`abyss-asset-v1` 大资产通道。
- **契约增量**：`plugin.json` 增 `provides/depends/capabilities/assetChannel` 四个可选字段；`plugins.json` 升 hubVersion 0.4.0；新增能力描述、会话契约、大资产元数据与统一错误码表。
- **任务方案**：M0–M4 五里程碑、46 条 WBS 任务（约 182 人日）、逐仓迁移清单、6 个回滚点、14 条风险登记、最终 DoD 与 5 个评审节点。
- **明确假设**：用户跳过「执行域形态」与「VM 插件处置」两项澄清，按 README §2 的 A-1~A-5 推荐假设展开；M0 评审必须复核，裁剪路径已写入 README。
- **对齐纪律**：沿用 `PLUGIN_SDK.md`、`SRS-002/003/004`、`ADR-001/008/011`、`SEC-001/002/003/005`、PLG-004、`HUB-001/005/010/014`、`DC-006/007/012`；增量修订点集中列于 README §4。

### 待评审项（v1.1 之前必须闭环）

| # | 待定 | 影响规格 |
| --- | --- | --- |
| 1 | A-1 双执行域是否确认为最终形态 | README、ADR-100、LIN-002、PLAN-100 M3 |
| 2 | A-2 VM 仓收编 vs 保留独立 | SRS-102、ARC-102、PLAN-101 §1⑤ |
| 3 | `minApp` 具体宿主版本号（暂用 0.10.0 占位） | APP-100、DC-100 |
| 4 | 默认配额 8GB 与 QEMU 档位数值 | LIN-002/003、DC-101 |
| 5 | 性能基线目标值需真机实测后定稿 | ENG-100 §7 |
