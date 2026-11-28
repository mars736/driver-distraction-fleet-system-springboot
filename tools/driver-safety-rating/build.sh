#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"
out="$(mktemp -d)"
trap 'rm -rf -- "$out"' EXIT
mkdir -p -- "$out/classes" target
classpath="$(hadoop classpath --glob):$(mapred classpath --glob)"
javac -encoding UTF-8 -source 8 -target 8 -cp "$classpath" -d "$out/classes" src/fleet/rating/DriverSafetyRatingJob.java
jar cfe target/driver-safety-rating.jar fleet.rating.DriverSafetyRatingJob -C "$out/classes" .
printf 'Built %s/target/driver-safety-rating.jar\n' "$PWD"
