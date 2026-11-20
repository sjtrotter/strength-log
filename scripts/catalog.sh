#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 || ! -d $1 || -z $2 ]]; then
  echo 'usage: scripts/catalog.sh <walks-dir> <build-sha> <walk-seats-json>' >&2
  exit 2
fi
if ! jq -e 'type == "array" and length > 0 and all(.[]; type == "string" and length > 0)' <<<"$3" > /dev/null; then
  echo 'Walk seats must be a nonempty JSON array of names' >&2
  exit 2
fi
shopt -s nullglob
summaries=("$1"/*/summary.json)
if [[ ${#summaries[@]} -gt 0 ]]; then
  ordered=$(jq -nr '[inputs | {seat, file: input_filename}] | sort_by(.seat) | .[].file' "${summaries[@]}")
  mapfile -t summaries <<<"$ordered"
fi
problems=()
mapfile -t expected_seats < <(jq -r 'sort | unique[]' <<<"$3")
for seat in "${expected_seats[@]}"; do
  listings=()
  for summary in "${summaries[@]}"; do
    if [[ $(jq -r '.seat' "$summary") == "$seat" ]]; then
      listings+=("${summary%/*}"/steps/*-checkpoint-*.screen.txt)
    fi
  done
  if [[ ${#listings[@]} -eq 0 ]]; then
    problems+=("$seat: no checkpoint listings; screens in this walk are missing.")
  fi
done
for summary in "${summaries[@]}"; do
  problem=$(jq -r '
    if .error_class != null then
      "\(.seat): stopped at step \(.failed_step // "launch") (\(.error_class)); later screens in this walk are missing."
    elif (.optional_skipped | length) > 0 then
      "\(.seat): skipped steps \(.optional_skipped | map(tostring) | join(", ")); screens at these steps may be missing."
    elif .backup_error != null then
      "\(.seat): backup failed (\(.backup_error.class)); the walk did not finish cleanly."
    else empty end' "$summary")
  if [[ -n $problem ]]; then problems+=("$problem"); fi
done
printf '# Screen catalog\nbuild: %s\n\n' "$2"
printf '%s\n\n' 'Every screen below was drawn by the app on this build during a scripted walk. Names, numbers and dates are the walk’s own, and each entry’s after: line lists the actions that led there from the entry before it.'
if [[ ${#problems[@]} -gt 0 ]]; then
  printf '## Walk problems\n'
  printf -- '- %s\n' "${problems[@]}"
  printf '\n'
fi
printf '## Contents\n'
for summary in "${summaries[@]}"; do
  seat=$(jq -r '.seat' "$summary")
  entries=("${summary%/*}"/steps/*-checkpoint-*.screen.txt)
  names=()
  for entry in "${entries[@]}"; do
    IFS= read -r header < "$entry"
    names+=("${header#\#\# }")
  done
  printf -- '- %s: ' "$seat"
  for index in "${!names[@]}"; do
    if [[ $index -gt 0 ]]; then printf '; '; fi
    printf '%s' "${names[$index]}"
  done
  printf '\n'
done
for summary in "${summaries[@]}"; do
  printf '\n# %s\n' "$(jq -r '.seat' "$summary")"
  entries=("${summary%/*}"/steps/*-checkpoint-*.screen.txt)
  for entry in "${entries[@]}"; do
    while IFS= read -r line || [[ -n $line ]]; do printf '%s\n' "$line"; done < "$entry"
    printf '\n'
  done
done
if [[ ${#problems[@]} -gt 0 ]]; then exit 1; fi
