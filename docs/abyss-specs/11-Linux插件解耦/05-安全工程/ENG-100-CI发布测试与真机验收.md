# ENG-100 · CI 门禁、发布链、测试策略与真机验收

> 深渊工具 Abyss Tools · 设计规格（工程与验收）
> 状态：**设计中 · 待评审** ｜ 优先级：**P0** ｜ 里程碑：**M0–M4** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `ENG-100` |
| 当前状态 | 设计中 · 待评审 |
| 优先级 / 里程碑 | P0 / M0–M4 |
| 上游依据 | `QA-001~008`、`RM-006 DoD`、`HUB-006/007/010`、`SRS-003`、`SEC-001`、`PLUGIN_SDK.md` |
| 合并 QA / 工程纪律 | 本文件统一承接 CI、发布、测试、真机、性能五类验收要求 |

## 1. 目标与范围

规定三仓（主壳 / Linux 插件 / 构建器）+ 插件中心 + 发布仓的门禁、发布链、测试金字塔、端到端用例、真机矩阵与性能基线。

## 2. CI 门禁矩阵

| 仓库 | 工作流 | 校验内容 | 红灯后果 |
| --- | --- | --- | --- |
| `Abyss-Tools` | 主仓 CI | `./gradlew test`（纯 JVM）、模块图无环、ToolCatalog 单测、HOST_API v2 契约夹具、静态扫描 | 不合并 |
| `Abyss-Plugins` | Hub CI | `validate_hub.py`：3 条目、depends 指向存在且未归档、capability 匹配、SemVer 区间、patchChannel 正则、id 唯一 | 不滚动索引 |
| `abyss-plugin-linux` | plugin-ci.yml | `validate_plugin.py`（三处一致性 + shizuku 形状）+ `validate_assets.py` + Gradle assemble + 单测 + `apksigner verify` 指纹门禁 | 不发 Release |
| `abyss-plugin-app-builder` | plugin-ci.yml | 同上 + **不得出现 shizuku** + `minHost>=2` + 静态扫描无底座实现 | 不发 Release |
| `abyss-plugin-virus-guard` | plugin-ci.yml | 不变 | 不合并 |
| `Abyss-Tools-Release` | 发布流水线 | versionCode 单调、`[skip release]` / `[skip ci]` 语义、写后自检回读三件套一致性 | 发布失败 |
| 全组织 | spec-lint | 编号唯一、引用可达、FR/AC 覆盖非空、错误码全在 DC-102 表内 | 规格变更不合并 |

**新增脚本**：`validate_assets.py`（清单字段 / sha256 / URL 白名单 / 版本不倒退 / 模板许可）、`validate_depends.py`（可并入 validate_hub.py）、`scan_decoupling.py`（构建器侧底座关键字扫描）。

## 3. 发布链（沿用双仓库纪律）

| 步 | 动作 |
| --- | --- |
| 1 | 插件仓 tag / dispatch → Gradle assemble → 固定 keystore 签名 → `apksigner verify` 实测指纹 |
| 2 | 产物 APK 上传 GitHub Release（开发仓不存签名材料） |
| 3 | `sync_index.py`（每日 12:00 UTC / 手动）聚合各仓 plugin.json、补丁通道、Releases APK 资产 → `plugins.json` |
| 4 | `apk` 字段由聚合器自动探测填入；`apk.sha256` 非空才允许客户端安装 |
| 5 | 数据补丁：`make_patch.py` → `publish_patch.py` → 滚动 `patches/index.json`（仅小数据） |
| 6 | 大资产：CI 生成分卷 + 计算 sha256 → 上传 Release → 更新 `assets/index.json`（`generatedAt` / 版本 / minPlugin） |
| 7 | versionCode 严格大于通道现值；commit 含 `[skip release]` 不发布 |
| 8 | 写后自检回读：version.json / patches.json / changelog.json 三件套一致 |

> 索引为运行时拉取，新增插件无需宿主发版；但 **HOST_API v2 与 PluginBroker 需主壳发版一次**（A-5）。

## 4. 测试金字塔

| 层 | 覆盖 | 工具 / 环境 | 占比目标 |
| --- | --- | --- | --- |
| L1 纯 JVM 单测 | SemVer 区间、依赖解析 / 环 / 拓扑、manifest v2 解析、资产元数据、错误码、状态机、指令模板、路径断言、限额 | `./gradlew test`，零 Android 依赖 | ~60% |
| L2 契约测试 | AIDL 数据类、grant 五元组、DeathRecipient、能力描述与 RuntimeInfo 一致性、测试夹具镜像 | mock binder / JVM | ~20% |
| L3 集成测试 | openSession → exec → fileIn/fileOut → close；资产下载续传；PRoot 冷启动；三路径产出 APK | 模拟器 + 真机 | ~15% |
| L4 真机 / 失败注入 | ROM 兼容、授权撤销、进程被杀、断电重启、下载中断、签名不符、OOM | 真机矩阵 | ~5% |

**失败注入必测清单**：ptrace 被杀、Shizuku 停止、grant 过期/重放、Binder 死亡、分卷篡改、外域重定向、存储不足、并发 exec、长输出截断、断电重启。

## 5. 端到端用例集（E2E）

| ID | 用例 | 期望 |
| --- | --- | --- |
| E2E-01 | 仅装宿主 + Linux 插件，打开会话执行 `echo ok` / `apk --version` | READY，exitCode=0 |
| E2E-02 | 构建器在 Linux 插件缺失时启动 | 引导安装，不崩溃 |
| E2E-03 | Linux 插件版本低于 `>=0.2.0` | 引导升级 |
| E2E-04 | 已装但未授权 Shizuku | 引导为 Linux 插件单独授权，授权次数 1 |
| E2E-05 | 资产未下载完成 | 展示真实进度，不启动构建 |
| E2E-06 | 三路径各构建 1 个项目 | 各产出可安装 APK |
| E2E-07 | 路径二失败后切路径三 | sessionId 不变、产物保留、无需重导 |
| E2E-08 | ColorOS 模拟器 ptrace 被拦 | ROM 清单 + 切域建议，不无限重试 |
| E2E-09 | 用户确认切 QEMU_VM | 新会话拉起 Alpine，`uname -r` 为客机内核 |
| E2E-10 | 卸载 Linux 插件 | 级联提示；重装后无需重启宿主即可恢复 |
| E2E-11 | 分卷下载中断后续传 | 不重复已完成分卷，整像 sha256 匹配 |
| E2E-12 | 杀掉 Linux 插件进程 | 10s 内宿主/构建器收 CLOSED，UI 转 Error |

## 6. 真机兼容矩阵

| 设备类 | 系统 | 重点 |
| --- | --- | --- |
| Pixel / 类原生 | Android 15、16 | 基准性能、16KB 页对齐、targetSdk 28 执行域 |
| ColorOS | 15 / 16（OPPO/OnePlus/Realme） | ptrace 约 1 分钟被杀、Shizuku 权限监控 |
| MagicOS | 荣耀 | 严格 SELinux 拒绝 ptrace |
| 内存档位 | 4GB / 6GB / 8GB+ | Gradle OOM、QEMU 档位拒绝 |
| 架构 | arm64 为主；非 arm64 | 明确不支持提示 |
| 存储 | 剩余 <8GB | `E_STORAGE_INSUFFICIENT` 与所需空间提示 |

## 7. 性能基线（目标值，真机实测后定稿）

| 指标 | 目标 | 上限 / 拒绝线 |
| --- | --- | --- |
| PRoot 域冷启动到 READY | ≤8s | >20s 告警 |
| `exec("echo ok")` 往返 | ≤300ms | >1s 告警 |
| rootfs 下载（64MB / WiFi） | ≤60s | 中断可续传 |
| 路径一构建 | ≤60s | — |
| 路径二构建 | ≤3min | — |
| 路径三构建 | 10–30min（明示预期） | OOM 即降档 |
| QEMU_VM 冷启动 | ≤180s | >5min 拒绝 |
| QEMU 域构建相对 PRoot | 3–8 倍 | 超过则建议放弃 |
| JVM 堆 | ≤2048m | 超限拒绝 |
| 输出单流 | 1MB | 超限 truncated 如实标注 |
| 连续 20 次 open/close | 0 孤儿进程 / 0 目录泄漏 | 任一残留即失败 |

## 8. 完成定义（DoD，逐项打勾才可进下一里程碑）

- [ ] 全部 FR 至少 1 条 AC 或自动化用例，追溯关系登记 `QA-008 需求追溯矩阵`
- [ ] 三仓 + 插件中心 + 发布仓 CI 全绿，spec-lint 全绿
- [ ] 测试夹具镜像（`src/test/resources`）与所有契约同步
- [ ] E2E-01~12 全部通过并留痕（截图 / 日志 / 构建产物 sha256）
- [ ] 真机矩阵覆盖 Android 15/16 各一台 + ColorOS + MagicOS
- [ ] 性能基线实测达标；未达标项有登记与缓解
- [ ] 安全用例（SEC-100 AC-001~005）全部通过
- [ ] 发布说明 / CHANGELOG / 归档公告齐备，版本号与三件套一致
- [ ] 回滚路径（ADR-100 §5）每条有监控指标或测试用例

## 9. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-22 | 创建；统一 CI、发布、测试、真机、性能与 DoD。 |
