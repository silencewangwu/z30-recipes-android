#!/usr/bin/env bash
# sync-check.sh —— 增量移植同步检查脚本
# 拉取上游 yekong-nasa/Z30-Recipes 自上次同步以来的变更，映射到本工程落点。
# 用法：./tools/sync-check.sh
set -euo pipefail
cd "$(dirname "$0")/.."

UPSTREAM_URL="https://github.com/yekong-nasa/Z30-Recipes.git"
UPSTREAM_BRANCH="main"

# 1. 确保 upstream remote 存在并拉取
if ! git remote get-url upstream >/dev/null 2>&1; then
  git remote add upstream "$UPSTREAM_URL"
  echo "已添加 upstream -> $UPSTREAM_URL"
fi
echo "==> 拉取上游 $UPSTREAM_BRANCH ..."
git fetch upstream "$UPSTREAM_BRANCH" --quiet

# 2. 上次同步点：PORTING.md 第一条记录的 commit hash（短写/长写均可）
LAST=$(grep -E '^\| *[0-9]{4}-[0-9]{2}-[0-9]{2} *\| *`[0-9a-f]{7,40}`' PORTING.md \
        | head -1 \
        | sed -E 's/^[^`]*`([0-9a-f]{7,40})`.*/\1/')
if [ -z "$LAST" ]; then
  LAST=$(git rev-parse --short HEAD)
  echo "（PORTING.md 无同步记录，以上次本地提交 $LAST 为基线）"
else
  echo "上次同步基线：$LAST"
fi

# 3. 上游新提交
echo ""
echo "=== 上游自 $LAST 以来的提交 ==="
git log --oneline "$LAST..upstream/$UPSTREAM_BRANCH" -- Sources/ Tests/ docs/ 2>/dev/null \
  || echo "（无本地历史可对比，请先 git fetch 后重试；或人工对照上游仓库）"

# 4. 变更文件 → Android 落点
echo ""
echo "=== 变更文件 → Android 落点 ==="
git diff --name-only "$LAST" "upstream/$UPSTREAM_BRANCH" 2>/dev/null -- Sources/ Tests/ docs/ \
  | while IFS= read -r f; do
      case "$f" in
        Sources/Core/PTPCodec.swift)
          echo "  $f"
          echo "      -> core/.../ByteUtils.kt Recipes.kt ClassicNcpRecipe.kt（逐行翻译 + 重跑全部测试）" ;;
        Sources/Core/PTPIPProbe.swift)
          echo "  $f"
          echo "      -> core/.../PacketSocket.kt PtpIpSession.kt（逐行翻译 + 重跑全部测试）" ;;
        Sources/App/Assets.xcassets/*|Sources/App/Info.plist)
          echo "  $f"
          echo "      -> 无需同步（iOS 图标/资源/配置，Android 不适用）" ;;
        Sources/App/*.swift)
          echo "  $f"
          echo "      -> app/.../RecipeLibraryViewModel.kt + Compose UI（行为对齐，UI 重写）" ;;
        Tests/CoreTests/*)
          echo "  $f"
          echo "      -> core/src/test/kotlin/.../CodecTests.kt + test/resources/（逐用例翻译，夹具一致）" ;;
        docs/*)
          echo "  $f"
          echo "      -> 阅读吸收，更新代码注释与操作码表" ;;
        *)
          echo "  $f"
          echo "      -> （未映射，人工判断是否需要同步）" ;;
      esac
    done

echo ""
echo "下一步：按 PORTING.md 流程翻译 → :core:test 全绿 → assembleDebug → 真机回归 → 更新 PORTING.md 同步记录"
