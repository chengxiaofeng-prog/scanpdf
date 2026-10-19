#!/usr/bin/env bash
# 一键：在 GitHub 账号下新建仓库 -> 推送 main -> 触发 Actions 编译
#
# 用法：
#   export GITHUB_TOKEN=ghp_xxxxxxxx     # 需要 repo 权限的 Personal Access Token
#   bash push-to-github.sh scanpdf       # 参数为仓库名，可省略（默认 scanpdf）
#
# 可选环境变量：
#   GH_OWNER   指定归属账号/组织（默认用 token 对应的账号）
#   GH_PRIVATE=1 建私有仓库（默认公开）
#
# 说明：推送完成后会把 origin 里的 token 抹掉，避免明文残留在 .git/config。
set -euo pipefail

REPO_NAME="${1:-scanpdf}"
API="https://api.github.com"
TOKEN="${GITHUB_TOKEN:-}"

if [[ -z "$TOKEN" ]]; then
  echo "缺少 GITHUB_TOKEN。请先执行："
  echo "  export GITHUB_TOKEN=你的PAT"
  echo "PAT 生成地址： https://github.com/settings/tokens （勾选 repo 权限）"
  exit 1
fi

# ---------- 0. 网络预检：直连失败就自动扫本机常见代理端口 ----------
can_reach() { curl -s -o /dev/null --max-time 8 https://api.github.com; }

echo "==> 检查 GitHub API 连通性…"
if ! can_reach; then
  echo "    直连失败，扫描本机代理端口…"
  for p in 7890 7891 10809 10808 1080 2080 20171 33210 8080 8888 55589; do
    if curl -s -o /dev/null --max-time 2 -x "http://127.0.0.1:$p" https://api.github.com; then
      export https_proxy="http://127.0.0.1:$p"
      export http_proxy="http://127.0.0.1:$p"
      echo "    命中代理：127.0.0.1:$p"
      break
    fi
  done
fi

if ! can_reach; then
  echo "仍然无法连接 api.github.com。请确认："
  echo "  1) 代理/VPN 已开启（Clash、V2Ray、Surge 等）"
  echo "  2) 模式为「全局」或「TUN」，或告诉我代理端口：export https_proxy=http://127.0.0.1:你的端口"
  exit 1
fi
echo "    网络正常"

# ---------- 1. 确认账号 ----------
if [[ -z "${GH_OWNER:-}" ]]; then
  echo "==> 读取 token 对应账号…"
  ME=$(curl -sS --max-time 15 -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.github+json" "$API/user")
  GH_OWNER=$(printf '%s' "$ME" | grep -o '"login":[[:space:]]*"[^"]*"' | head -1 \
             | sed 's/.*"\([^"]*\)"$/\1/')
  if [[ -z "$GH_OWNER" ]]; then
    echo "token 校验失败，返回："
    printf '%s\n' "$ME" | head -5
    exit 1
  fi
fi
echo "==> 账号：$GH_OWNER   仓库：$REPO_NAME"

# ---------- 2. 建仓库（已存在则复用） ----------
PRIVATE_FLAG="false"
[[ "${GH_PRIVATE:-0}" == "1" ]] && PRIVATE_FLAG="true"

echo "==> 创建仓库（已存在则复用）…"
CODE=$(curl -s -o /tmp/gh_repo.json -w "%{http_code}" -X POST "$API/user/repos" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -d "{\"name\":\"$REPO_NAME\",\"private\":$PRIVATE_FLAG,\"description\":\"Android 文档扫描：连拍 / 自动裁边 / 去底灰 / 阈值 / OCR / PDF / 微信QQ分享\"}")

case "$CODE" in
  201) echo "    已创建新仓库" ;;
  422) echo "    仓库已存在，直接复用" ;;
  401) echo "token 无效或已过期（HTTP 401）"; exit 1 ;;
  *)   echo "创建仓库失败（HTTP $CODE）："; head -c 500 /tmp/gh_repo.json; echo; exit 1 ;;
esac

# ---------- 3. 提交本地改动 ----------
cd "$(dirname "$0")"

if ! git rev-parse --git-dir >/dev/null 2>&1; then
  git init -q
fi
git branch -M main 2>/dev/null || true

if [[ -z "$(git config user.name || true)" ]]; then
  git config user.name "$GH_OWNER"
fi
if [[ -z "$(git config user.email || true)" ]]; then
  git config user.email "${GH_OWNER}@users.noreply.github.com"
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "==> 提交未提交的改动…"
  git add -A
  git commit -q -m "chore: 同步最新代码"
fi

# ---------- 4. 推送 ----------
echo "==> 推送到 GitHub…"
git remote remove origin >/dev/null 2>&1 || true
git remote add origin "https://x-access-token:$TOKEN@github.com/$GH_OWNER/$REPO_NAME.git"

set +e
git push -u origin main --quiet
PUSH_CODE=$?
set -e

# 无论成功失败，立刻抹掉 token
git remote set-url origin "https://github.com/$GH_OWNER/$REPO_NAME.git"

if [[ $PUSH_CODE -ne 0 ]]; then
  echo "推送失败。常见原因：token 没有 repo 权限、仓库已存在但内容冲突、网络中断。"
  exit $PUSH_CODE
fi

echo ""
echo "==> 已推送： https://github.com/$GH_OWNER/$REPO_NAME"
echo "==> 编译进度： https://github.com/$GH_OWNER/$REPO_NAME/actions"
echo "==> 编译完成后：Actions 运行页底部 Artifacts → ScanPdf-debug-apk → 解压得 app-debug.apk"
