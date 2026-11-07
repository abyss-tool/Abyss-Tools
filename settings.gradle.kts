pluginManagement {
    repositories {
        // NIA（android/nowinandroid）同款纪律：google() 只解析 Google 系 group，
        // 防止其它坐标误走 google() 拉到非预期构件，同时减少无谓元数据请求。
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

// NIA 同款前置检查：JDK 不满足直接给人话报错，而不是等 AGP 在配置中期
// 抛晦涩异常（本仓 JDK 17 起步，与 CI setup-java 对齐）。
check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) {
    "Abyss Tools requires JDK 17+ (current: ${JavaVersion.current()}). " +
        "Set JAVA_HOME to a JDK 17+ installation. CI reference: .github/workflows/ci.yml (setup-java temurin 17)."
}

rootProject.name = "abyss-tools"

// ── 模块地图 ─────────────────────────────────────────────────────────────
// :app                     主壳：导航图、液态玻璃底栏、服务装配（手动 DI）
// :vendor:backdrop         液态玻璃底层（Kyant0/AndroidLiquidGlass vendored，Apache-2.0）
// :core:common             纯 Kotlin：SemVer / UiState / 工具目录 / 渠道常量
// :core:designsystem       M3 主题 + 液态玻璃组件库（GlassSurface / 玻璃底栏）
// :core:update             补丁更新引擎（version.json/patches.json 客户端 + 补丁应用）
// :core:plugin             插件框架（仓库索引拉取 / 安装器 / 插件补丁通道）
// :core:shell              Shizuku 执行层（伪装四件套 / 内置工具的 adb shell 通道）
// :plugin-sdk:plugin-api   插件契约（宿主 ↔ 插件共享，含 manifest schema）
// :feature:dashboard       仪表盘
// :feature:navhub          导航（分类工具目录，第一类 = 插件）
// :feature:plugins         插件中心 + 插件详情（安装 AP 框架）
// :feature:disguise        伪装四件套（机型 / 电量 / 信号 / 定位，Shizuku 免 Root）
// :feature:onboarding      首启新手引导（权限申请一站式）
// :feature:settings        设置（含补丁更新入口）
include(":app")
include(":vendor:backdrop")
include(":core:common")
include(":core:designsystem")
include(":core:update")
include(":core:plugin")
include(":core:shell")
include(":plugin-sdk:plugin-api")
include(":feature:dashboard")
include(":feature:navhub")
include(":feature:plugins")
include(":feature:disguise")
include(":feature:onboarding")
include(":feature:settings")
include(":feature:appmgr")      // 应用管理器（列表 / 冻结 / 隐藏 / 提取 APK / 强停）
include(":feature:shizukukit")  // Shizuku 工具箱（appops / settings / dumpsys 指令面板）
include(":feature:qrcraft")     // 二维码工坊（ZXing 生成 / 相册解析）
include(":feature:blueprint")   // 任务蓝图 + 自动化编排（JSON 步骤链 + 定时调度）
include(":feature:island")      // 灵动岛与悬浮球（WindowManager overlay）
include(":feature:wallpaper")   // 动态壁纸（WallpaperService 液态玻璃光斑引擎）
