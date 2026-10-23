#!/usr/bin/env bash
# 把本機的 release 簽章設成 GitHub Actions secrets，給 .github/workflows/release.yml 用。
#
# ⚠️ 由使用者本人在自己的電腦執行（不是給 Claude 跑的）：會把簽章 keystore 與密碼上傳到 GitHub。
# 值從專案根目錄的 keystore.properties（不進版控）直接讀出、經 stdin 交給 gh，不會印在畫面上。
#
# secrets 放進只准 main 使用的 release 環境——public repo 在 Free 方案也能設環境；
# 設不起來時（例如 repo 改成 private 又沒有 Pro）會先問過才退回 repo 層級。
set -euo pipefail

REPO=waltwait/PassProtect
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PROPS="$ROOT/keystore.properties"

[ -f "$PROPS" ] || { echo "找不到 $PROPS"; exit 1; }
prop() { sed -n "s/^$1=//p" "$PROPS" | head -1; }

store=$(prop storeFile)
case "$store" in
  /*) ;;
  *) store="$ROOT/$store" ;;
esac
[ -f "$store" ] || { echo "找不到 keystore（keystore.properties 的 storeFile）"; exit 1; }
for k in storePassword keyAlias keyPassword; do
  [ -n "$(prop "$k")" ] || { echo "keystore.properties 缺 $k"; exit 1; }
done

scope=(-R "$REPO")
if gh api -X PUT "repos/$REPO/environments/release" --input - >/dev/null 2>&1 <<'JSON'
{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}
JSON
then
  gh api -X POST "repos/$REPO/environments/release/deployment-branch-policies" -f name=main -f type=branch >/dev/null 2>&1 || true
  if gh api "repos/$REPO/environments/release/deployment-branch-policies" -q '.branch_policies[].name' 2>/dev/null | grep -qx main; then
    scope+=(--env release)
    echo "✅ release 環境已設定：只有 main 上的 workflow 拿得到簽章"
  fi
fi
if [ ${#scope[@]} -eq 2 ]; then
  echo "⚠️ 無法把 secrets 限制在 main，會改放 repo 層級（任何能推分支並改 workflow 的人都讀得到）"
  read -r -p "仍要繼續上傳嗎？[y/N] " ok
  [ "$ok" = y ] || { echo "已取消，沒有上傳任何東西"; exit 1; }
fi

base64 -i "$store" | tr -d '\n' | gh secret set PASSPROTECT_KEYSTORE_BASE64 "${scope[@]}"
printf '%s' "$(prop storePassword)" | gh secret set PASSPROTECT_RELEASE_STORE_PASSWORD "${scope[@]}"
printf '%s' "$(prop keyAlias)" | gh secret set PASSPROTECT_RELEASE_KEY_ALIAS "${scope[@]}"
printf '%s' "$(prop keyPassword)" | gh secret set PASSPROTECT_RELEASE_KEY_PASSWORD "${scope[@]}"

echo
echo "已設定（只列名稱）："
gh secret list "${scope[@]}"
