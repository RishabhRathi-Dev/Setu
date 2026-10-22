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
cache_path="$(dirname -- "$jar_path")/setu-server.aot"
if [[ "${1:-}" == --train-aot ]]; then
  if [[ $# != 1 ]]; then
    echo '--train-aot does not accept broker configuration arguments' >&2
    exit 1
  fi
  training_cache_path="$cache_path.training"
  "$java_executable" "${jvm_arguments[@]}" "-XX:AOTCacheOutput=$training_cache_path" -jar "$jar_path" --aot-train
  [[ -f "$training_cache_path" ]] || { echo 'The JVM did not generate the AOT cache' >&2; exit 1; }
  mv -f -- "$training_cache_path" "$cache_path"
  echo "AOT cache ready: $cache_path"
  exit 0
fi
if [[ "${1:-}" == --no-aot ]]; then
  shift
elif [[ -f "$cache_path" ]]; then
  jvm_arguments+=(-XX:AOTMode=on "-XX:AOTCache=$cache_path")
  echo "Using AOT cache: $cache_path"
fi
exec "$java_executable" "${jvm_arguments[@]}" -jar "$jar_path" "$@"
