#!/usr/bin/env bash
#
# N-ENG-001 · 本地可复现门禁（M1，P1）
#
# 目标：一条命令在本地跑出与 GitHub Actions CI 相同的门禁结果，
#      杜绝「CI 绿本地红 / 本地绿 CI 红」（FR-003）。CI 的 manifests
#      任务直接调用本脚本 --jvm 模式 —— 两侧共用同一入口，永不漂移；
#      合并判决仍以 GitHub Actions 为唯一事实源（AGENTS.md 纪律）。
#
# 用法：
#   bash tools/local_gate.sh            # 全量：Gradle 双变体构建 + 单测 + 全部 Python 门禁
#   bash tools/local_gate.sh --jvm      # 轻量：仅 Python 门禁（无需 JDK / Android SDK）
#   bash tools/local_gate.sh --gradle   # 仅 Gradle（assembleDebug/Release + test）
#
# 环境要求（FR-002：版本钉死，与 AGENTS.md 工具链整组对齐，勿单独升降）：
#   - JDK 17（temurin 推荐）
#   - Android SDK：compileSdk 36 平台包（build-tools 版本由 AGP 8.13.2 自取）
#   - Gradle 无需安装：仓库 wrapper（8.14）自动拉取发行版
#   - Python 3.10+（stdlib 即可，无第三方依赖）
#
# 签名材料（可选，FR-004）：ABYSS_KEYSTORE_FILE / ABYSS_KEYSTORE_PASSWORD /
#   ABYSS_KEY_ALIAS / ABYSS_KEY_PASSWORD 环境变量，或 local.properties 同名项。
#   缺失时 assembleRelease 自动降级为未签名产物（app/build.gradle.kts 既有
#   语义，与 CI fork PR 同款）—— 编译门禁照常执行，发布门禁由 release.yml
#   的发布者指纹校验兜底。
#
# 退出码：0 = 全部门禁通过；非 0 = 存在失败（fail-closed，不静默降级）。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODE="${1:-full}"
case "$MODE" in
  full)     RUN_GRADLE=1; RUN_PYTHON=1 ;;
  --jvm)    RUN_GRADLE=0; RUN_PYTHON=1 ;;
  --gradle) RUN_GRADLE=1; RUN_PYTHON=0 ;;
  *)
    echo "用法: bash tools/local_gate.sh [full | --jvm | --gradle]" >&2
    echo "  full      Gradle 双变体构建 + 单测 + 全部 Python 门禁（默认）" >&2
    echo "  --jvm     仅 Python 门禁（无需 JDK / Android SDK）" >&2
    echo "  --gradle  仅 Gradle 构建与单测" >&2
    exit 2
    ;;
esac

echo "── N-ENG-001 本地门禁 · 模式: $MODE ─────────────────────────────"

# ── Gradle 部分（与 ci.yml build 任务同一组任务）──────────────────────
run_gradle() {
  # FR-004：签名材料缺失 → 显著提示后照常执行（未签名降级语义在 Gradle 侧）
  if [ -z "${ABYSS_KEYSTORE_FILE:-}" ] || [ ! -f "${ABYSS_KEYSTORE_FILE:-}" ]; then
    echo "⚠ 未检测到 release 签名材料（ABYSS_KEYSTORE_FILE）—— assembleRelease 将产出未签名 APK"
    echo "  （与 CI fork PR 同语义；发布门禁由 release.yml 指纹校验兜底）"
  fi
  ./gradlew assembleDebug assembleRelease --stacktrace
  ./gradlew test --stacktrace
  echo "✓ Gradle 门禁通过：assembleDebug + assembleRelease + test"
}

# ── Python 门禁部分（与 ci.yml manifests 任务同一组脚本）──────────────
run_python_gates() {
  echo "── [1/4] 通道清单 / 夹具 / 版本单一事实源（validate_manifests.py）"
  python3 tools/validate_manifests.py

  echo "── [2/4] Spec-Lint（N-ENG-008）"
  python3 tools/spec_lint.py

  echo "── [3/4] UI 文案预算（UI-001）"
  python3 tools/ui_text_budget.py

  echo "── [4/4] 发布说明质量门禁（N-ENG-010，逐文件）"
  shopt -s nullglob
  local_files=(docs/release-notes/v*.md)
  shopt -u nullglob
  if [ "${#local_files[@]}" -eq 0 ]; then
    echo "主仓没有任何 docs/release-notes/v*.md —— N-ENG-010 要求每版本附说明" >&2
    exit 1
  fi
  for f in "${local_files[@]}"; do
    echo "校验 $f"
    python3 tools/validate_release_notes.py --markdown "$f"
  done
  echo "✓ ${#local_files[@]} 份发布说明全部通过质量门禁"
}

if [ "$RUN_GRADLE" -eq 1 ]; then
  run_gradle
fi
if [ "$RUN_PYTHON" -eq 1 ]; then
  run_python_gates
fi

echo "── N-ENG-001 本地门禁全部通过（与 GitHub Actions CI 同一组命令）"
