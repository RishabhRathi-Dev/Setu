#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
jar_path="$repo_root/setu-server/target/setu-server-1.0.0-SNAPSHOT.jar"
if [[ ! -f "$jar_path" ]]; then
  shopt -s nullglob
  packaged=("$repo_root"/setu-server-*.jar)
  if [[ ${#packaged[@]} == 1 ]]; then jar_path="${packaged[0]}"; fi
fi
if [[ ! -f "$jar_path" ]]; then
  echo 'Build the broker first: bash ./mvnw -pl setu-server -am package' >&2
  exit 1
fi
java_executable="${JAVA_HOME:+$JAVA_HOME/bin/}java"
jvm_arguments=("@$repo_root/config/jvm.options")
exec "$java_executable" "${jvm_arguments[@]}" -jar "$jar_path" "$@"
