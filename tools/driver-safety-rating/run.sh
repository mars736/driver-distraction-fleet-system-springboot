#!/usr/bin/env bash
set -euo pipefail
if (( $# < 1 || $# > 4 )); then
    printf 'Usage: bash %s JAR [RATING_HDFS_ROOT] [ARCHIVE_HDFS_ROOT] [--once]\n' "$0" >&2
    exit 2
fi
jar=$1
root=${2:-/fleet-safety/ratings}
archive=${3:-/fleet-safety/alert-events}
mode=${4:-}
[[ -r "$jar" && "$root" =~ ^(/[A-Za-z0-9_-]+)+$ && "$archive" =~ ^(/[A-Za-z0-9_-]+)+$ ]]
[[ -z "$mode" || "$mode" == "--once" ]]
exec 9>"$jar.runner.lock"
flock -n 9
hdfs dfs -mkdir -p "$root/yarn-staging"
while true; do
    if paths=$(hdfs dfs -find "$root/batches" -name ready.ok -print 2>/dev/null); then
        while IFS= read -r marker; do
            batch=${marker%/ready.ok}
            [[ "$batch" =~ /batches/[a-f0-9-]{36}$ ]] || continue
            if ! hdfs dfs -test -e "$batch/outcome.tsv"; then
                hadoop jar "$jar" "-Dyarn.app.mapreduce.am.staging-dir=$root/yarn-staging" "$batch" "$archive" \
                    || printf 'Rating job failed for %s\n' "$batch" >&2
            fi
        done <<< "$paths"
    fi
    [[ "$mode" != "--once" ]] || break
    sleep 15
done
