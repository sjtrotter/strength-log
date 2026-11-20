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
seats='["catalog-01-green","catalog-02-red"]'
bash "$script_dir/catalog.sh" "$temporary/walks" test-build "$seats" > "$temporary/catalog.md" || status=$?
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
printf '## Workout · Reached\n[screen]\n  text "Reached"\n' > "$temporary/walks/a/steps/004-checkpoint-reached.screen.txt"
bash "$script_dir/catalog.sh" "$temporary/walks" test-build "$seats" > "$temporary/catalog.md"
jq -Rse 'contains("## Walk problems") | not' "$temporary/catalog.md" > /dev/null

status=0
bash "$script_dir/catalog.sh" "$temporary/walks" test-build '["catalog-01-green","catalog-02-red","catalog-03-missing"]' > "$temporary/catalog.md" || status=$?
[[ $status -eq 1 ]]
jq -Rse 'contains("## Walk problems") and contains("- catalog-03-missing: no checkpoint listings; screens in this walk are missing.") and contains("## Today · Second")' "$temporary/catalog.md" > /dev/null

rm "$temporary/walks/a/steps/004-checkpoint-reached.screen.txt"
status=0
bash "$script_dir/catalog.sh" "$temporary/walks" test-build "$seats" > "$temporary/catalog.md" || status=$?
[[ $status -eq 1 ]]
jq -Rse 'contains("- catalog-02-red: no checkpoint listings; screens in this walk are missing.")' "$temporary/catalog.md" > /dev/null

mkdir "$temporary/empty"
status=0
bash "$script_dir/catalog.sh" "$temporary/empty" test-build "$seats" > "$temporary/catalog.md" || status=$?
[[ $status -eq 1 ]]
jq -Rse 'split("\n")[1] == "build: test-build" and contains("- catalog-01-green: no checkpoint listings; screens in this walk are missing.") and contains("- catalog-02-red: no checkpoint listings; screens in this walk are missing.")' "$temporary/catalog.md" > /dev/null
