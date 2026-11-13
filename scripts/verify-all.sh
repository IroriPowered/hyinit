#!/usr/bin/env bash
# Usage: scripts/verify-all.sh <hyinit-jar> <server-jar> <java-home> <output-dir>
set -euo pipefail

hyinit_jar=$1
server_jar=$2
java_home=$3
output_dir=$4
scripts_dir=$(cd "$(dirname "$0")" && pwd)

status=0
for check in startup capabilities loader-ownership; do
    echo "== $check"
    if ! python3 "$scripts_dir/verify-$check.py" --hyinit-jar "$hyinit_jar" --server-jar "$server_jar" \
        --java-home "$java_home" --output-dir "$output_dir/verify-$check"; then
        status=1
    fi
done
exit $status
