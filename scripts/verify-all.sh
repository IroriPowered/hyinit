#!/usr/bin/env bash
# Usage: scripts/verify-all.sh <hyinit-jar> <server-jar> <java-home> <output-dir> [assets]
set -euo pipefail

hyinit_jar=$1
server_jar=$2
java_home=$3
output_dir=$4
assets=${5:-}
scripts_dir=$(cd "$(dirname "$0")" && pwd)

status=0
run() {
    local name=$1 check=$2
    shift 2
    echo "== $name"
    if ! python3 "$scripts_dir/verify-$check.py" --hyinit-jar "$hyinit_jar" --server-jar "$server_jar" \
        --java-home "$java_home" --output-dir "$output_dir/verify-$name" "$@"; then
        status=1
    fi
}
for check in startup capabilities loader-ownership; do
    run "$check" "$check"
done
run capabilities-early-plugin capabilities --early-plugin
if [ -n "$assets" ]; then
    run native-reload native-reload --assets "$assets"
fi
exit $status
