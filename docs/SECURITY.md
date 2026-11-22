# 安全与信任链（Security Notes）

> 面向审计者与贡献者：本文记录深渊工具发布链的信任模型、已知的
> 历史风险与处置决策。普通用户只需要知道：**只从
> [Abyss-Tools-Release](https://github.com/abyss-tool/Abyss-Tools-Release)
> 的 Releases 下载 APK，安装时系统会校验签名一致性。**

## 发布签名

| 项 | 值 |
| --- | --- |
| 签名身份 | `meng411722`（key alias / name，v0.5.0 起） |
| 证书 SHA-256 | `76:03:78:21:44:88:D6:20:79:78:BA:1E:37:E6:DC:82:AB:C2:DE:72:C9:F6:C6:7D:9B:44:95:15:8A:99:B7:69` |
| 签名方案 | v2 + v3（minSdk 26，v1 自 v0.6.0 起关闭） |
| 材料存放 | GitHub Actions secrets（`ABYSS_KEYSTORE_B64` / 口令三件套），**不出现在仓库** |

### 信任链（三层 fail-closed）

1. **同签名门禁**（`PackageInstallerApkGateway`）：覆盖安装时待装 APK
   签名集必须与在装版本一致；任一侧签名不可读 / 为空 → 拒绝安装。
2. **发布者指纹硬校验**（`PublisherSignatures.PINNED_SHA256`）：宿主
   自更新（`com.abyss.tools` 覆盖安装）额外要求签名证书指纹命中内置
   常量 —— 双保险：即使有人在同签名上做文章，指纹锚点不动就不放行。
3. **CI 发布门禁**（`release.yml`）：发布前 `apksigner verify` 实测
   指纹与常量一致才允许上传；`versionCode` 必须严格大于通道现值
   （防误发旧版本把 latest 拉回去）。

### 历史风险与处置（诚实声明）

- **v0.5.0 期间 jks 与口令曾入库公开**（当时的动机是「CI 与本地共用
  同一份 → 指纹恒定」）。该 keystore 已从仓库删除（v0.6.0），但 git
  历史中的副本无法追回，理论上应视为已泄露。
- **为什么不立即轮换**：轮换 = 存量用户全部卸载重装（签名变更无法
  覆盖安装），补丁更新链（patches.json 的 from 比对）也会断裂。当前
  决策是**保持 meng411722 身份不轮换**，靠上述三层门禁兜底 —— 攻击者
  即便持有旧 jks，也必须同时攻破发布仓写入权限才能推送恶意更新，而
  发布链还有 versionCode 单调门禁与指纹校验。
- **本地构建签名材料**：`local.properties` 提供 `abyssKeystoreFile` /
  `abyssKeystorePassword` / `abyssKeyAlias` / `abyssKeyPassword`，或
  同名环境变量；缺失时 release 构建降级为未签名产物（不阻断编译门禁）。

### 未来轮换路径（如需）

1. 生成新 keystore，把新指纹常量替换进 `PublisherSignatures`；
2. 发布最后一个「旧签」版本，内置新指纹与迁移引导；
3. 发布仓 `version.json` 标记 breaking，客户端提示卸载重装；
4. 新旧 jks 的 `minApp` / 补丁通道 from 链全部重新起链。

## Secrets 与令牌纪律（N-SEC-002，M1 起）

### 最小权限矩阵（唯一事实源，新增 secret 必须先登记）

| 仓 | Secret | 权限 / 内容 | 约束 |
| --- | --- | --- | --- |
| Abyss-Tools | `RELEASE_TOKEN` | fine-grained PAT，**仅发布仓 Abyss-Tools-Release 的 Contents RW** | 禁止授其他仓/组织权限；90 天有效期 |
| Abyss-Tools | `RELEASE_SIGNING_KEY` | 清单签名私钥（raw Ed25519 seed，base64 单行） | 永不入库不入日志；轮换走双公钥过渡 |
| Abyss-Tools | `ABYSS_KEYSTORE_B64` + 口令三件套 | APK 发布签名材料 | v0.6.0 起出库 secrets（SEC-008 历史决策不变） |
| Abyss-Plugins | `RELEASE_SIGNING_KEY` | plugins.json 签名私钥（同首签钥） | 独立配置，不与主仓共享 PAT |
| abyss-plugin-*（3 仓） | `RELEASE_SIGNING_KEY` | 补丁通道签名私钥 | 各仓独立配置（FR-005：不共用主仓 RELEASE_TOKEN） |
| 各仓 | `GITHUB_TOKEN` | Actions 内置，workflow `permissions` 显式收敛 | 发布仓 / 插件中心 / 插件仓 CI 均只读或最小写 |

### 90 天轮换与吊销演练（FR-002 / FR-003）

`tools/pat_rotate_drill.py` 提供三步演练：`--probe`（身份 + 各仓读探针 +
发布链路关键资产可读性）、`--audit-secrets`（「仓 → secret → 消费点」
使用记录审计）、`--check-tooling`（发布工具链错误路径不回显令牌的静态
断言 —— AC-001 的「错误信息不泄露令牌」半边）。轮换 SOP 五步见脚本输出
尾部；真实吊销在 GitHub → Developer settings 手工执行（脚本绝不持有
吊销权限，也不打印任何令牌值）。

### 审计红线（FR-004）

- workflow / 脚本日志**禁止回显**任何 secret 值（CI 步骤只回显
  「材料就绪」状态，不回显内容）；
- 发布工具链（`publish_release.py` / `publish_patch.py` / `sync_index.py`）
  错误输出仅含 HTTP 状态与响应片段，不含令牌 —— 由
  `pat_rotate_drill.py --check-tooling` 在 CI 侧持续断言。

## 下载镜像（v0.7.0 起）

国内网络直连 GitHub 极不稳定，v0.7.0 引入镜像链（ghfast.top /
ghproxy.net / gh-proxy.com / ghproxy.cn / raw.gitmirror.com）。信任模型：

- **通路而非信任源**：镜像只承担「搬运」，全部资产仍强制 SHA-256 校验
  （fail-closed）—— 镜像被劫持最多造成下载失败 / 校验不过，不可能植入
  被篡改的载荷；
- **域白名单收敛**：`FetchHostPolicy` 只放行注册镜像域，且 PREFIX 形态
  递归校验内嵌的 GitHub 白名单 URL —— 镜像域不能被借道外传任意域载荷；
- **目录可演进**：镜像死亡是常态，`MirrorCatalog` 单点维护，删除即下线。

## 更新通道（version.json / patches.json / plugins.json）

- 拉取域白名单：`raw.githubusercontent.com` / `github.com` /
  `objects.githubusercontent.com`（Release 资产重定向域）——
  `OkHttpTransport` 请求前与重定向后双重校验，白名单外一律拒绝。
- SHA-256 摘要**缺失即拒绝**（v0.6.0 起，不再有「框架期兼容」放行）。
- **清单 detached 签名（v0.10 / N-SEC-001 起，已落地）**：发布仓三件套
  （version.json / patches.json / changelog.json）、插件中心 plugins.json、
  三个插件仓 patches/index.json 全部附带 minisign 兼容 detached 签名
  （`.minisig`，Ed25519，发布流水线对**写入字节**签名、清单与签名同 job
  原子提交）；客户端 `SignedHttpTransport` **先拉 `.minisig` 验签再解析**，
  公钥内置 `ChannelSignatureVerifier.TRUSTED_KEYS`（唯一事实源），无签名
  一律拒绝 —— **不存在「无签名也放行」的兼容后门**：
  - **存量客户端兼容/灰度策略**：v0.9.9（PR #10）起客户端已强制验签，
    v0.9.10 客户端对缺签名通道 fail-closed（当时发布侧尚未产出签名，更新
    链处于「拒绝」态）—— 本节所述发布侧签名上线即恢复「验签通过」，属
    **修复而非 breaking**；v0.9.8 及更早客户端从不请求 `.minisig`，不受
    影响。历史上没有任何「签名缺失也放行」的客户端版本存在。
  - **镜像同步语义**：`.minisig` 与清单同路径，镜像同步延迟会表现为
    「清单 200 / 签名 404」—— 客户端给人话提示「镜像可能尚未同步，请
    稍后重试或切换网络」并拒绝解析（fail-closed，绝不放行未验签清单）；
    镜像只做通路不做信任源，签名校验不依赖镜像诚实。
  - **密钥管理**：私钥（raw Ed25519 seed）只存各仓 GitHub Actions secrets
    （`RELEASE_SIGNING_KEY`），永不入库；签名格式与 minisign 0.11 CLI 兼容
    （`minisign -V` 可独立复核）。密钥轮换走**双公钥过渡期**：新公钥加入
    `TRUSTED_KEYS`（新增不替换），旧签名在兼容窗口内继续有效，窗口
    ≥ 1 个版本；若发布私钥疑似泄露，第一时间吊销各仓 secret、生成新钥对
    并按双公钥流程换签。
  - 发布仓 CI 门禁（`validate_manifests.py`）：`.minisig` 存在且可验 +
    清单 `download.sha256` = 该 tag GitHub Release APK 附件真实 digest，
    任一不符直接失败。

## 命令执行面（Shizuku 通道）

- 免引用参数一律经 `ShizukuCommands.requireSafeToken`（白名单字符集
  `[A-Za-z0-9._:-]`，元字符 / 空格就地拒绝）；
- 带空格的自由文本（设备名 / 蓝牙名）一律 `shellQuote` 单引号转义；
- Shizuku 网关并发消费 stdout/stderr + 1 MB 单流上限 + 真实超时，
  大输出命令不再有管道死锁面。

## 插件链路

- 插件中心索引在运行时逐条校验（`ChannelPolicy`）：id 形态 / 仓库
  归属 / 补丁通道 host / APK 资产 host + sha256 非空 —— 不合规条目
  整条拒绝展示，绝不「拉到什么用什么」；
- 卸载 / 补丁应用入口做 canonical 路径断言（穿越即拒）；
- 插件 APK 安装走与宿主相同的 PackageInstaller 门禁（同签名 +
  摘要校验 + 用户确认页）。
