#!/usr/bin/env bash
# Commits changed files in docs/screenshots back to the branch that triggered the workflow.
set -euo pipefail

MESSAGE="${1:-Update screenshots}"
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add docs/screenshots
if git diff --cached --quiet; then
  echo "Screenshots unchanged"
  exit 0
fi
git commit -m "$MESSAGE"
for attempt in 1 2 3 4 5; do
  if git pull --rebase origin "$GITHUB_REF_NAME" && git push origin "HEAD:$GITHUB_REF_NAME"; then
    exit 0
  fi
  sleep $((attempt * 5))
done
echo "Could not push screenshots" >&2
exit 1
