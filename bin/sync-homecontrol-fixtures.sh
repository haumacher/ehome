#!/bin/sh
# Copies the runtime image fixtures and the rule conformance corpus of HomeControl into the test
# fixtures of E-Home and prints the HomeControl commit they come from (to be written into
# ehome/src/test/fixtures/homecontrol/README.md). The DPT corpus has its own script, sync-dpt-corpus.sh.
#
# Usage: bin/sync-homecontrol-fixtures.sh <homecontrol checkout>
set -e

if [ $# -ne 1 ]; then
	echo "Usage: $0 <homecontrol checkout>" >&2
	exit 1
fi

source="$1/spec/fixtures"
target="$(dirname "$0")/../ehome/src/test/fixtures/homecontrol"

for dir in images rules; do
	if [ ! -d "$source/$dir" ]; then
		echo "No fixtures in $source/$dir" >&2
		exit 1
	fi
done

# Replace the copies: files removed upstream disappear here too.
for dir in images rules; do
	find "$target/$dir" -type f \( -name '*.json' -o -name '*.md' \) -delete 2>/dev/null || true
	mkdir -p "$target/$dir"
	(cd "$source/$dir" && find . -type f \( -name '*.json' -o -name '*.md' \)) | while read -r file; do
		mkdir -p "$target/$dir/$(dirname "$file")"
		cp "$source/$dir/$file" "$target/$dir/$file"
	done
done

echo "Copied $(find "$source/images" -name '*.json' | wc -l) image fixtures and $(find "$source/rules" -name '*.json' | wc -l) rule cases; last change in haumacher/homecontrol:"
git -C "$1" log -1 --format='%H %ad %s' --date=short -- spec/fixtures/images spec/fixtures/rules
echo "Checkout at: $(git -C "$1" rev-parse HEAD)"
