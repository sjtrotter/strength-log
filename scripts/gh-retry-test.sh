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
    echo queried >> "$GH_RETRY_TEST_DIR/rate-queries"
    printf '{"resources":{"core":{"remaining":%s,"reset":1002}}}\n' "${GH_RETRY_CORE_REMAINING:-0}" | jq -r "$4"
    exit 0
fi
count=0
if [[ -f "$GH_RETRY_TEST_DIR/count" ]]; then count=$(<"$GH_RETRY_TEST_DIR/count"); fi
printf '%s\n' "$((count + 1))" > "$GH_RETRY_TEST_DIR/count"
case ${GH_RETRY_SCENARIO:-rate_then_transient} in
    rate_then_transient)
        case $count in
            0) echo 'HTTP 403: API rate limit exceeded' >&2; exit 1 ;;
            1) echo 'HTTP 502: Bad Gateway' >&2; exit 1 ;;
            *) echo 'ready' ;;
        esac ;;
    not_found)
        echo 'HTTP 404: Not Found (https://api.github.com/repos/example/issue403/actions/runs)' >&2
        exit 1 ;;
    forbidden)
        echo 'HTTP 403: Forbidden' >&2
        exit 1 ;;
    rate_limit)
        if (( count == 0 )); then echo 'HTTP 403: API rate limit exceeded' >&2; exit 1; fi
        echo 'ready' ;;
    secondary_rate_limit)
        if (( count == 0 )); then echo 'HTTP 429: secondary rate limit' >&2; exit 1; fi
        echo 'ready' ;;
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
reset_case() {
    rm -f "$sandbox/count" "$sandbox/sleeps" "$sandbox/rate-queries"
}

exhaustedRateLimitWaitsForResetAndRetriesTransientFailure() {
    reset_case
    local output
    output=$(GH_RETRY_SCENARIO=rate_then_transient GH_RETRY_CORE_REMAINING=0 gh_retry gh run view 123 --json status)
    [[ $output == ready ]]
    [[ $(<"$sandbox/sleeps") == $'7\n30' ]]
    [[ $(<"$sandbox/count") == 3 ]]
    [[ $(<"$sandbox/rate-queries") == queried ]]
}

notFoundWith403InUrlReturnsWithoutSleeping() {
    reset_case
    local status=0
    GH_RETRY_SCENARIO=not_found gh_retry gh run view 123 --json status || status=$?
    [[ $status == 75 ]]
    [[ $(<"$sandbox/count") == 1 ]]
    [[ ! -e "$sandbox/sleeps" && ! -e "$sandbox/rate-queries" ]]
}

forbiddenWithoutRateLimitReturnsWithoutSleeping() {
    reset_case
    local status=0
    GH_RETRY_SCENARIO=forbidden gh_retry gh run view 123 --json status || status=$?
    [[ $status == 75 ]]
    [[ $(<"$sandbox/count") == 1 ]]
    [[ ! -e "$sandbox/sleeps" && ! -e "$sandbox/rate-queries" ]]
}

unexhaustedRateLimitWaits120Seconds() {
    reset_case
    local output
    output=$(GH_RETRY_SCENARIO=rate_limit GH_RETRY_CORE_REMAINING=1 gh_retry gh run view 123 --json status)
    [[ $output == ready ]]
    [[ $(<"$sandbox/sleeps") == 120 ]]
    [[ $(<"$sandbox/count") == 2 ]]
    [[ $(<"$sandbox/rate-queries") == queried ]]
}

secondaryRateLimitWaits120Seconds() {
    reset_case
    local output
    output=$(GH_RETRY_SCENARIO=secondary_rate_limit GH_RETRY_CORE_REMAINING=1 gh_retry gh run view 123 --json status)
    [[ $output == ready ]]
    [[ $(<"$sandbox/sleeps") == 120 ]]
    [[ $(<"$sandbox/count") == 2 ]]
    [[ $(<"$sandbox/rate-queries") == queried ]]
}

exhaustedRateLimitWaitsForResetAndRetriesTransientFailure
notFoundWith403InUrlReturnsWithoutSleeping
forbiddenWithoutRateLimitReturnsWithoutSleeping
unexhaustedRateLimitWaits120Seconds
secondaryRateLimitWaits120Seconds
echo 'gh_retry passed'
