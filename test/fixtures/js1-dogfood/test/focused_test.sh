#!/bin/sh
set -eu

expected='(ns fixture.dogfood)

(def dogfood-state :green)'
actual=$(cat src/dogfood.clj)

if [ "$actual" = "$expected" ]; then
  printf '%s\n' 'JS1-DOGFOOD-FOCUSED-GREEN fixture.dogfood-test'
  exit 0
fi

printf '%s\n' 'JS1-DOGFOOD-FOCUSED-RED fixture.dogfood-test expected dogfood-state :green'
printf '%s\n' 'The first :red candidate is intentional; repair src/dogfood.clj with a second anchored edit.'
cat > red-evidence.txt <<'EOF'
JS1-DOGFOOD-FOCUSED-RED
fixture.dogfood-test expected dogfood-state :green and observed the intentional :red candidate.
Repair src/dogfood.clj with the second anchored project/edit, then call done again.
EOF
exit 1
