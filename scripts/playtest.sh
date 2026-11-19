#!/usr/bin/env bash
# usage: scripts/playtest.sh <seat> [script.json]
# Run on the networked host. Testers only edit scripts and read downloaded artifacts.
set -euo pipefail

# shellcheck source=scripts/gh-retry.sh
source "$(dirname "${BASH_SOURCE[0]}")/gh-retry.sh"

git_retry() {
    local attempt
    for attempt in 0 1 2; do
        if "$@"; then return 0; fi
        if (( attempt < 2 )); then sleep 30; fi
    done
    echo 'Git origin operation failed after three attempts' >&2
    return 75
}

if [[ $# -lt 1 || $# -gt 2 || ! $1 =~ ^[A-Za-z0-9][A-Za-z0-9_-]*$ ]]; then
    echo 'usage: scripts/playtest.sh <seat> [script.json]; seat uses letters, numbers, _ or -' >&2
    exit 2
fi
seat=$1
script=${2:-playtest/$seat/script.json}
script=$(realpath "$script") || exit 2
[[ -f "$script" ]] || { echo "script not found: $script" >&2; exit 2; }
steps=$(jq -er '.steps | if type == "array" then length else error("steps must be an array") end' "$script") || exit 2
[[ $(jq -er '.seat' "$script") == "$seat" ]] || { echo 'script seat does not match the requested seat' >&2; exit 2; }
branch=playtest/$seat
root=$(git rev-parse --show-toplevel)
cd "$root"
worktree="$root/.playtest-wt/$seat"

base_ref=${PLAYTEST_BASE:-origin/main}
if [[ -n ${PLAYTEST_BASE:-} ]]; then
    if ! build=$(git rev-parse --verify --end-of-options "$base_ref^{commit}" 2>/dev/null); then
        git_retry git fetch -q -- origin "$base_ref" || exit 75
        if ! build=$(git rev-parse --verify 'FETCH_HEAD^{commit}' 2>/dev/null); then
            echo "PLAYTEST_BASE does not resolve to a commit: $base_ref" >&2
            exit 2
        fi
    fi
    base_ref=$build
else
    git_retry git fetch -q origin main || exit 75
    build=$(git rev-parse --verify 'origin/main^{commit}')
fi
echo "build: $build"

# Preserve the seat's history. Recover a remote seat branch on a new host, too.
if git ls-remote --exit-code --heads origin "$branch" >/dev/null; then
    git_retry git fetch -q origin "refs/heads/$branch:refs/remotes/origin/$branch" || exit 75
else
    remote_status=$?
    [[ $remote_status == 2 ]] || exit 75
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
        git worktree add -q -b "$branch" "$worktree" "$base_ref"
    fi
fi
if git show-ref --verify --quiet "refs/remotes/origin/$branch"; then
    git -C "$worktree" merge --no-edit "origin/$branch"
fi
if ! git -C "$worktree" merge-base --is-ancestor "$base_ref" HEAD; then
    git -C "$worktree" merge --no-edit "$base_ref"
fi
mkdir -p "$worktree/playtest/$seat"
destination="$worktree/playtest/$seat/script.json"
if [[ "$script" != "$destination" ]]; then cp "$script" "$destination"; fi
git -C "$worktree" add -f "playtest/$seat/script.json"
# A relative start.restore travels with the script; CI cannot see the host's files.
restore=$(jq -r '.start.restore // empty' "$script")
if [[ -n "$restore" && "$restore" != /* ]]; then
    [[ $restore =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ && $restore != script.json && $restore != summary.json ]] || {
        echo 'start.restore must be a file name next to the script, other than script.json or summary.json' >&2; exit 2;
    }
    source_dir=$(dirname "$script")
    [[ -f "$source_dir/$restore" ]] || { echo "restore backup not found: $source_dir/$restore" >&2; exit 2; }
    for file in "$restore" summary.json; do
        if [[ ! -f "$source_dir/$file" ]]; then
            # A summary left by an earlier restore must not supply this backup's clock.
            git -C "$worktree" rm -q -f --ignore-unmatch "playtest/$seat/$file"
            continue
        fi
        if [[ "$source_dir/$file" != "$worktree/playtest/$seat/$file" ]]; then cp "$source_dir/$file" "$worktree/playtest/$seat/$file"; fi
        git -C "$worktree" add -f "playtest/$seat/$file"
    done
fi
if ! git -C "$worktree" diff --cached --quiet; then
    git -C "$worktree" commit -q -m "playtest($seat): $steps steps"
fi
git_retry git -C "$worktree" push -q origin "$branch" || exit 75

last_ok=0
if [[ -f "playtest-runs/$seat/.last-ok" ]]; then
    last_ok=$(cat "playtest-runs/$seat/.last-ok")
    [[ $last_ok =~ ^[0-9]+$ ]] || { echo 'invalid .last-ok step count' >&2; exit 2; }
fi
before=$(date -u +%FT%TZ)
head=$(git -C "$worktree" rev-parse HEAD)
gh_dispatch_existing_run() {
    gh_retry gh run list --workflow playtest.yml --branch "$branch" --event workflow_dispatch --created ">=$before" \
        --json databaseId,headSha -q "[.[] | select(.headSha == \"$head\")] | first | .databaseId // empty"
}
dispatch=$(gh_retry gh workflow run playtest.yml --ref "$branch" -f "script=playtest/$seat/script.json" -f "capture_from=$last_ok") || exit 75
run=$(jq -Rr 'select(test("^[0-9]+$"))' <<<"$dispatch")
# One GitHub call a minute per seat at most, plus the retries.
for ((poll = 0; poll < 10; poll++)); do
    [[ -z "$run" ]] || break
    sleep 60
    run=$(gh_dispatch_existing_run) || exit 75
done
[[ -n "$run" ]] || { echo 'dispatch not visible after 10 minutes' >&2; exit 75; }

conclusion=''
for ((poll = 0; poll < 45; poll++)); do
    sleep 60
    result=$(gh_retry gh run view "$run" --json status,conclusion) || exit 75
    if [[ $(jq -r '.status' <<<"$result") == completed ]]; then
        conclusion=$(jq -r '.conclusion' <<<"$result")
        break
    fi
done
[[ -n "$conclusion" ]] || { echo 'run not completed after 45 minutes' >&2; exit 75; }
out="playtest-runs/$seat/$run"
mkdir -p "$out"
printf '%s\n' "$build" > "$out/build.txt"
sleep 60
gh_retry gh run download "$run" -D "$out" -n "playtest-$run-0" || exit 75
summary="$out/summary.json"
[[ -f "$summary" ]] || { echo "summary not found in $out" >&2; exit 75; }
status=1
if [[ $conclusion == success ]] && jq -e '.failed_step == null and .error_class == null' "$summary" >/dev/null; then
    status=0
    jq -r '.steps_run' "$summary" > "playtest-runs/$seat/.last-ok"
fi
cat "$summary"
echo "artifact: $out"
exit "$status"
