#!/bin/sh
# Copies the DPT conformance corpus of HomeControl into the test fixtures of E-Home and prints the
# HomeControl commit it comes from (to be written into ehome/src/test/fixtures/dpt-corpus/README.md).
#
# Usage: bin/sync-dpt-corpus.sh <homecontrol checkout>
set -e

if [ $# -ne 1 ]; then
	echo "Usage: $0 <homecontrol checkout>" >&2
	exit 1
fi

source="$1/spec/fixtures/dpt"
target="$(dirname "$0")/../ehome/src/test/fixtures/dpt-corpus"

if [ ! -d "$source" ]; then
	echo "No corpus in $source" >&2
	exit 1
fi

mkdir -p "$target"
rm -f "$target"/dpt-*.json
cp "$source"/dpt-*.json "$target"/
cp "$1/spec/dpt-corpus.md" "$target"/

echo "Copied $(ls "$source"/dpt-*.json | wc -l) files; last change of the corpus in haumacher/homecontrol:"
git -C "$1" log -1 --format='%H %ad %s' --date=short -- spec/fixtures/dpt spec/dpt-corpus.md
echo "Checkout at: $(git -C "$1" rev-parse HEAD)"
