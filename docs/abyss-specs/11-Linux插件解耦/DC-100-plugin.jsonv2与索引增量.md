# DC-100 · plugin.json v2 与 plugins.json 索引增量

> 深渊工具 Abyss Tools · 设计规格（数据契约）
> 状态：**设计中 · 待评审** ｜ 优先级：**P0** ｜ 里程碑：**M0** ｜ 编制日期：**2026-11-22**

| 项 | 值 |
| --- | --- |
| 规格编号 | `DC-100` |
| 当前状态 | 设计中 · 待评审 |
| 优先级 / 里程碑 | P0 / M0 |
| 上游依据 | `DC-006`、`PLG-002`、`HUB-001`、`PLUGIN_SDK.md`、`SRS-003` |
| 兼容策略 | 新增字段全部可选；v1 解析器 `ignoreUnknownKeys = true` |

## 1. 目标与范围

为「插件依赖插件」定义最小 schema 增量：4 个新字段 + 索引透传 + 校验规则。不改动 v1 已有 15 字段的语义。

## 2. plugin.json v2 新增字段

| 字段 | 类型 | 必填 | 含义 | 校验 |
| --- | --- | --- | --- | --- |
| `provides` | `Array<ProvideSpec>` | 否 | 本插件对外提供的能力 | 每项 `capability` 唯一、格式 `^[a-z0-9-]+-v[0-9]+$` |
| `depends` | `Array<DependSpec>` | 否 | 消费的能力与插件 | 见 §3 |
| `capabilities` | `Object` | 否 | 能力细节描述（域 / 特性 / 架构 / 限额） | 见 DC-101 |
| `assetChannel` | `String?` | 否 | `assets/index.json` URL | 过 `ChannelPolicy` 域白名单与重定向复检 |

```json
{
  "provides": [{"capability": "linux-runtime-v1", "since": "0.2.0", "stable": true}],
  "depends": [{
    "pluginId": "abyss-plugin-linux",
    "capability": "linux-runtime-v1",
    "versionRange": ">=0.2.0 <1.0.0",
    "optional": false,
    "reason": "构建需要 Linux 运行环境……"
  }],
  "capabilities": {"linux-runtime-v1": {"domains": ["PROOT_USERLAND", "QEMU_VM"]}},
  "assetChannel": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/index.json"
}
```

## 3. DependSpec / ProvideSpec 定义

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `pluginId` | String | 必须符合 `ID_PATTERN ^[a-z0-9]+(-[a-z0-9]+)+$`，且等于被依赖仓库末段 |
| `capability` | String | 必须命中索引中该插件 `provides[].capability` |
| `versionRange` | String | SemVer 区间（`>=x.y.z <a.b.c`），统一用 `SemVer` 比较，禁止 `split('.')` |
| `optional` | Boolean | 默认 false；false 时宿主必须 fail-closed |
| `reason` | String | `optional=false` 时必填且 ≥10 字符，人话说明为何依赖 |
| `since` | String | ProvideSpec：该能力自哪个插件版本起提供 |
| `stable` | Boolean | ProvideSpec：false 表示预览能力，索引标注 |

**规则**
- 一个插件可声明多条 depends；当前实现只支持单层依赖（DAG 环检测仍必须做）。
- `depends` 不得指向归档 id；索引 CI 直接拒收。
- `provides` 的能力主版本变化（v1 → v2）必须新开 capability id，禁止原地改语义。

## 4. plugins.json 索引增量（hubVersion 0.4.0）

```json
{
  "schema": "abyss-plugin-index-v1",
  "generatedAt": "2026-11-22T00:00:00Z",
  "hubVersion": "0.4.0",
  "plugins": [
    {
      "id": "abyss-plugin-linux",
      "name": "Linux 运行环境",
      "repo": "abyss-tool/abyss-plugin-linux",
      "category": "tools",
      "version": "0.2.0",
      "minApp": "0.10.0",
      "minHost": 2,
      "summary": "自包含 Linux 运行环境：PRoot 用户态 + QEMU 完整系统双执行域，统一会话与 Shizuku 授权。",
      "icon": "🐧",
      "permissions": ["INTERNET", "FOREGROUND_SERVICE", "WAKE_LOCK"],
      "shizuku": {"ownAuth": true, "reason": "PRoot/QEMU 需 shell 上下文绕过普通应用沙箱限制；本插件为独立 APK，需单独授权一次。"},
      "provides": [{"capability": "linux-runtime-v1", "since": "0.2.0", "stable": true}],
      "capabilities": {"linux-runtime-v1": {"domains": ["PROOT_USERLAND", "QEMU_VM"], "arch": ["arm64"]}},
      "assetChannel": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/assets/index.json",
      "apk": null,
      "patchChannel": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-linux/main/patches/index.json",
      "roadmap": ["v0.2 PRoot 域", "v0.3 跨插件能力绑定", "v0.4 QEMU 域", "v0.5 资产差量"]
    },
    {
      "id": "abyss-plugin-app-builder",
      "depends": [{"pluginId": "abyss-plugin-linux", "capability": "linux-runtime-v1", "versionRange": ">=0.2.0 <1.0.0", "optional": false, "reason": "构建需要 Linux 运行环境，由 Linux 插件统一提供。"}],
      "minHost": 2,
      "apk": null,
      "patchChannel": "https://raw.githubusercontent.com/abyss-tool/abyss-plugin-app-builder/main/patches/index.json",
      "roadmap": ["v0.2 依赖握手", "v0.3 路径一", "v0.4 路径二", "v0.5 路径三", "v0.6 交互层"]
    },
    {
      "id": "abyss-plugin-virus-guard",
      "version": "0.1.0",
      "minHost": 1,
      "apk": null
    }
  ]
}
```

> 上例为契约示意，仅展示与本次增量相关的字段；每条索引条目实际仍必须含 HUB-001 FR-002 要求的全部字段（name / repo / category / minApp / summary / icon / permissions / shizuku / roadmap 等）。构建器完整 manifest 见 APP-100 §2；病毒拦截插件完整条目沿用现值不变。

| 索引规则 | 说明 |
| --- | --- |
| 条目数 | 恰好 3 个活跃插件；不含 `abyss-plugin-virtual-machine` |
| 必填 | 每条目仍含 id/name/repo/category/version/minApp/summary/icon/permissions/shizuku/apk/patchChannel/roadmap（HUB-001 FR-002） |
| `apk=null` | 展示「资产未发布」，不隐藏条目（HUB-001 FR-004） |
| `shizuku` | 仅 Linux 插件声明；声明即 ownAuth=true + reason 非空 |
| `depends` | 由 `sync_index.py` 从各仓 plugin.json 透传，不手工维护 |
| 新鲜度 | `hubVersion` / `generatedAt` 展示索引新鲜度（PLG-003 FR-003） |

## 5. 校验矩阵

| 校验点 | 脚本 | 规则 |
| --- | --- | --- |
| 插件仓 CI | `validate_plugin.py` | 三处一致性 + shizuku 形状 + 新增 v2 字段 + 静态扫描（APP-100 §3） |
| 索引 CI | `validate_hub.py` | id 唯一、三条目、depends 指向存在且未归档、capability 可解析、SemVer 合法、patchChannel 正则 |
| 资产 CI | `validate_assets.py` | `assets/index.json` 字段完整、sha256 64 位 hex、URL 白名单、版本不倒退 |
| 宿主侧 | `ChannelPolicy` / `PluginBroker` | 运行时二次校验，不信任索引为唯一真相 |
| 测试夹具 | `src/test/resources` | 上述三类清单的镜像夹具，随契约同步更新 |

## 6. 功能需求

| 编号 | 需求描述 |
| --- | --- |
| FR-001 | 新增字段全部可选，v1 插件在 v2 宿主可加载，v2 插件在 v1 宿主 fail-closed。 |
| FR-002 | `repo` 末段等于 id、归属 `abyss-tool`，`patchChannel` 符合既有正则（PLG-004）。 |
| FR-003 | 版本比较统一 SemVer，禁止手写 split。 |
| FR-004 | 索引聚合器对单仓故障保留旧条目并告警，不阻塞生态滚动。 |
| FR-005 | 任何 schema 不符 / 坏 JSON → 整页 Error，不展示部分数据。 |
| FR-006 | 契约变更必须同步更新 `DC-006`、`PLG-002`、`PLUGIN_SDK.md` 与测试夹具。 |

## 7. 失败语义与边界

- 坏数据 / 非法版本 / 路径穿越 / 签名不符一律 fail-closed 并给人话错误。
- 远端数据统一 `UiState` 三态，加载中禁止假数据。
- 索引里出现归档 id 或重复 id → 保留首条并记 rejected（PLG-003 FR-002），Hub CI 红灯。

## 8. 验收标准

| 编号 | 验收条件 |
| --- | --- |
| AC-001 | 三处一致性门禁全绿；注入 `shizuku` 到构建器 manifest 时 CI 失败。 |
| AC-002 | `validate_hub.py` 覆盖依赖缺失 / 环 / 归档 id / 非法区间 / capability 不匹配 5 类。 |
| AC-003 | v1 宿主加载 v2 插件被拒；v2 宿主加载 v1 插件正常。 |
| AC-004 | 索引中 `apk` 由 null 变为资产后，sync_index 自动回填且 sha256 非空。 |

## 9. 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| 1.0 | 2026-11-22 | 创建；定义 4 个可选字段、索引 0.4.0 增量与校验矩阵。 |
