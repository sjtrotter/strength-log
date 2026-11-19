#!/usr/bin/env bash
set -euo pipefail
# shellcheck source=scripts/gh-retry.sh
source "$(dirname "${BASH_SOURCE[0]}")/gh-retry.sh"

sandbox=$(mktemp -d)
trap 'rm -rf "$sandbox"' EXIT
export GH_RETRY_TEST_DIR=$sandbox
export PATH="$sandbox:$PATH"
cat > "$sandbox/gh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
if [[ ${1:-} == api && ${2:-} == rate_limit ]]; then
    echo 1002
    exit 0
fi
count=0
if [[ -f "$GH_RETRY_TEST_DIR/count" ]]; then count=$(<"$GH_RETRY_TEST_DIR/count"); fi
printf '%s\n' "$((count + 1))" > "$GH_RETRY_TEST_DIR/count"
case $count in
    0) echo 'HTTP 403: API rate limit exceeded' >&2; exit 1 ;;
    1) echo 'HTTP 502: Bad Gateway' >&2; exit 1 ;;
    *) echo 'ready' ;;
esac
STUB
cat > "$sandbox/date" <<'STUB'
#!/usr/bin/env bash
echo 1000
STUB
cat > "$sandbox/sleep" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$1" >> "$GH_RETRY_TEST_DIR/sleeps"
STUB
chmod +x "$sandbox/gh" "$sandbox/date" "$sandbox/sleep"
output=$(gh_retry gh run view 123 --json status)
[[ $output == ready ]]
[[ $(<"$sandbox/sleeps") == $'7\n30' ]]
[[ $(<"$sandbox/count") == 3 ]]
echo 'gh_retry passed'
