#!/usr/bin/env bash
set -euo pipefail

script_dir=${BASH_SOURCE[0]%/*}
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT
mkdir -p "$temporary/walks/a/steps" "$temporary/walks/z/steps"
printf '%s\n' '{"seat":"catalog-02-red","failed_step":5,"error_class":"not-found","optional_skipped":[]}' > "$temporary/walks/a/summary.json"
printf '%s\n' '{"seat":"catalog-01-green","failed_step":null,"error_class":null,"optional_skipped":[]}' > "$temporary/walks/z/summary.json"
printf '## Setup · First\n[screen]\n  text "First entry"\n' > "$temporary/walks/z/steps/002-checkpoint-first.screen.txt"
printf '## Today · Second\n[screen]\n  text "Second entry"\n' > "$temporary/walks/z/steps/010-checkpoint-second.screen.txt"
printf '## Not a checkpoint\n[screen]\n' > "$temporary/walks/z/steps/005-tap-next.screen.txt"
status=0
bash "$script_dir/catalog.sh" "$temporary/walks" test-build > "$temporary/catalog.md" || status=$?
[[ $status -eq 1 ]]
jq -Rse '
  split("\n") as $lines |
  ($lines | index("- catalog-01-green: Setup · First; Today · Second")) as $first_contents |
  ($lines | index("- catalog-02-red: ")) as $second_contents |
  ($lines | index("## Setup · First")) as $first_entry |
  ($lines | index("## Today · Second")) as $second_entry |
  $lines[1] == "build: test-build" and
  $first_contents != null and $second_contents != null and $first_contents < $second_contents and
  $first_entry != null and $second_entry != null and $first_entry < $second_entry and
  ($lines | index("- catalog-02-red: stopped at step 5 (not-found); later screens in this walk are missing.")) != null and
  (contains("Not a checkpoint") | not)
' "$temporary/catalog.md" > /dev/null
printf '%s\n' '{"seat":"catalog-02-red","failed_step":null,"error_class":null,"optional_skipped":[]}' > "$temporary/walks/a/summary.json"
bash "$script_dir/catalog.sh" "$temporary/walks" test-build > "$temporary/catalog.md"
jq -Rse 'contains("## Walk problems") | not' "$temporary/catalog.md" > /dev/null
