#!/usr/bin/env bash
# release.sh — feature 브랜치를 main 에 머지하고 push → Railway 자동 배포까지 이어지게 한다.
#
# 사용:
#   ./scripts/release.sh                 # 현재 체크아웃된 브랜치를 main 에 머지
#   ./scripts/release.sh feat/foo        # feat/foo 를 main 에 머지
#   ./scripts/release.sh feat/foo --ff   # fast-forward only (머지 커밋 안 만듦)
#
# 사전조건: 현재 워킹트리가 깨끗해야 한다 (uncommitted 변경 있으면 중단).

set -euo pipefail

cd "$(dirname "$0")/.."

if [[ -n "$(git status --porcelain)" ]]; then
  echo "❌ working tree 가 깨끗하지 않아. 먼저 커밋/stash 해." >&2
  exit 1
fi

current_branch="$(git rev-parse --abbrev-ref HEAD)"
source_branch="${1:-$current_branch}"
ff_only=false
if [[ "${2:-}" == "--ff" ]]; then
  ff_only=true
fi

if [[ "$source_branch" == "main" ]]; then
  echo "❌ main 을 main 에 머지할 수는 없어." >&2
  exit 1
fi

echo "▶ main 최신화"
git checkout main
git pull --ff-only origin main

echo "▶ $source_branch → main 머지"
if $ff_only; then
  git merge --ff-only "$source_branch"
else
  git merge --no-ff "$source_branch" -m "Merge: $source_branch"
fi

echo "▶ origin/main 푸시 → Railway 자동 배포 시작"
git push origin main

echo "▶ feature 브랜치 정리 (로컬)"
if [[ "$source_branch" != "$current_branch" ]]; then
  git branch -d "$source_branch" || true
fi

echo ""
echo "✅ 머지 + 푸시 완료. Railway 빌드 확인:"
echo "   railway logs --service signal-desk-api"
echo ""
echo "프로덕션 엔드포인트 헬스 체크 (2-3분 뒤):"
echo "   curl -s https://signal-desk-api-production.up.railway.app/health"
