#!/usr/bin/env bash

# Keep stdout available to callers that parse GitHub's response.
gh_retry() {
    local rate_waits=0 transient_retries=0 output error_file error reset now wait_seconds primary
    error_file=$(mktemp) || return 75
    while true; do
        if output=$("$@" 2>"$error_file"); then
            rm -f "$error_file"
            printf '%s\n' "$output"
            return 0
        fi
        error=$(<"$error_file")
        primary=false
        if [[ ${error,,} =~ (bad\ credentials|authentication\ required|not\ logged|authentication\ failed|resource\ not\ accessible|requires\ authentication) ]]; then
            printf '%s\n' "$error" >&2
            rm -f "$error_file"
            return 75
        fi
        if [[ ${error,,} == *'api rate limit exceeded'* ||
              $error =~ (403|429) ||
              ${error,,} == *'secondary rate limit'* ]]; then
            if (( rate_waits >= 5 )); then
                printf '%s\n' "$error" >&2
                rm -f "$error_file"
                return 75
            fi
            reset=0
            if [[ ${error,,} != *'secondary rate limit'* ]]; then
                # Do not recursively query the reset if this unmetered endpoint itself fails.
                if [[ ${1:-} == gh && ${2:-} == api && ${3:-} == rate_limit ]]; then
                    printf '%s\n' "$error" >&2
                    rm -f "$error_file"
                    return 75
                fi
                reset=$(gh_retry gh api rate_limit --jq .resources.core.reset) || reset=0
                [[ $reset =~ ^[0-9]+$ ]] || reset=0
                now=$(date +%s)
                if (( reset > now )); then
                    wait_seconds=$((reset - now + 5))
                    (( wait_seconds <= 3900 )) || wait_seconds=3900
                    primary=true
                fi
            fi
            if [[ $primary == false ]]; then wait_seconds=120; fi
            printf 'GitHub rate limit; waiting %s s before retrying\n' "$wait_seconds" >&2
            rate_waits=$((rate_waits + 1))
        elif [[ $error =~ (HTTP[^0-9]*5[0-9][0-9]|502|503|504) ||
                ${error,,} =~ (timeout|timed\ out|connect|could\ not\ resolve|temporary\ failure|eof|tls\ handshake|network) ||
                ( ${2:-} == run && ${3:-} == download &&
                  ${error,,} =~ (no\ artifacts|no\ valid\ artifacts|artifact.*not\ found) ) ]]; then
            if (( transient_retries >= 3 )); then
                printf '%s\n' "$error" >&2
                rm -f "$error_file"
                return 75
            fi
            case $transient_retries in
                0) wait_seconds=30 ;;
                1) wait_seconds=60 ;;
                2) wait_seconds=120 ;;
            esac
            transient_retries=$((transient_retries + 1))
            printf 'GitHub unavailable; waiting %s s before retrying\n' "$wait_seconds" >&2
        else
            printf '%s\n' "$error" >&2
            rm -f "$error_file"
            return 75
        fi
        sleep "$wait_seconds"
        if [[ ${1:-} == gh && ${2:-} == workflow && ${3:-} == run ]] &&
            declare -F gh_dispatch_existing_run >/dev/null; then
            if ! output=$(gh_dispatch_existing_run); then
                rm -f "$error_file"
                return 75
            fi
            if [[ -n "$output" ]]; then
                rm -f "$error_file"
                printf '%s\n' "$output"
                return 0
            fi
        fi
    done
}
