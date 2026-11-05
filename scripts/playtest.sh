#!/usr/bin/env bash
# usage: scripts/playtest.sh <seat> [script.json]
# Run on the networked host. Testers only edit scripts and read downloaded artifacts.
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 || ! $1 =~ ^[A-Za-z0-9][A-Za-z0-9_-]*$ ]]; then
    echo 'usage: scripts/playtest.sh <seat> [script.json]; seat uses letters, numbers, _ or -' >&2
    exit 2
fi
seat=$1
script=${2:-playtest/$seat/script.json}
script=$(realpath "$script")
[[ -f "$script" ]] || { echo "script not found: $script" >&2; exit 2; }
steps=$(jq -er '.steps | if type == "array" then length else error("steps must be an array") end' "$script")
[[ $(jq -er '.seat' "$script") == "$seat" ]] || { echo 'script seat does not match the requested seat' >&2; exit 2; }
branch=playtest/$seat
root=$(git rev-parse --show-toplevel)
cd "$root"
worktree="$root/.playtest-wt/$seat"

# Preserve the seat's history. Recover a remote seat branch on a new host, too.
git fetch -q origin main
if git ls-remote --exit-code --heads origin "$branch" >/dev/null; then
    git fetch -q origin "refs/heads/$branch:refs/remotes/origin/$branch"
else
    remote_status=$?
    [[ $remote_status == 2 ]] || exit "$remote_status"
fi
if [[ -e "$worktree" ]]; then
    [[ $(git -C "$worktree" rev-parse --show-toplevel) == "$worktree" ]] || {
        echo "not the expected worktree: $worktree" >&2; exit 2;
    }
    [[ -z $(git -C "$worktree" status --porcelain) ]] || {
        echo "seat worktree has uncommitted changes: $worktree" >&2; exit 2;
    }
    git -C "$worktree" checkout -q "$branch"
else
    mkdir -p "$(dirname "$worktree")"
    if git show-ref --verify --quiet "refs/heads/$branch"; then
        git worktree add -q "$worktree" "$branch"
    elif git show-ref --verify --quiet "refs/remotes/origin/$branch"; then
        git worktree add -q -b "$branch" "$worktree" "origin/$branch"
    else
        git worktree add -q -b "$branch" "$worktree" origin/main
    fi
fi
if git show-ref --verify --quiet "refs/remotes/origin/$branch"; then
    git -C "$worktree" merge --no-edit "origin/$branch"
fi
if ! git -C "$worktree" merge-base --is-ancestor origin/main HEAD; then
    git -C "$worktree" merge --no-edit origin/main
fi
mkdir -p "$worktree/playtest/$seat"
destination="$worktree/playtest/$seat/script.json"
if [[ "$script" != "$destination" ]]; then cp "$script" "$destination"; fi
git -C "$worktree" add -f "playtest/$seat/script.json"
if ! git -C "$worktree" diff --cached --quiet; then
    git -C "$worktree" commit -q -m "playtest($seat): $steps steps"
fi
git -C "$worktree" push -q origin "$branch"

last_ok=0
if [[ -f "playtest-runs/$seat/.last-ok" ]]; then
    last_ok=$(cat "playtest-runs/$seat/.last-ok")
    [[ $last_ok =~ ^[0-9]+$ ]] || { echo 'invalid .last-ok step count' >&2; exit 2; }
fi
before=$(date -u +%FT%TZ)
head=$(git -C "$worktree" rev-parse HEAD)
gh workflow run playtest.yml --ref "$branch" -f "script=playtest/$seat/script.json" -f "capture_from=$last_ok"
run=''
for _ in $(seq 30); do
    run=$(gh run list --workflow playtest.yml --branch "$branch" --event workflow_dispatch --created ">=$before" \
        --json databaseId,headSha -q ".[] | select(.headSha == \"$head\") | .databaseId" | head -1 || true)
    [[ -n "$run" ]] && break
    sleep 5
done
[[ -n "$run" ]] || { echo 'dispatch not visible after 150 s' >&2; exit 2; }

status=0
gh run watch "$run" --exit-status >/dev/null || status=$?
out="playtest-runs/$seat/$run"
mkdir -p "$out"
gh run download "$run" -D "$out" -n "playtest-$run-0"
summary="$out/summary.json"
[[ -f "$summary" ]] || { echo "summary not found in $out" >&2; exit 2; }
if [[ $status == 0 ]] && jq -e '.failed_step == null and .error_class == null' "$summary" >/dev/null; then
    jq -r '.steps_run' "$summary" > "playtest-runs/$seat/.last-ok"
fi
cat "$summary"
echo "artifact: $out"
exit "$status"
